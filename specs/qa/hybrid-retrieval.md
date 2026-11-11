# 混合检索 + Rerank 规格

> 承接：`specs/qa/retrieval.md`（Step1 向量检索）、`specs/qa/rag-answer.md`（Step2）
> 阶段：M4 Step5（可选增强）。不新增中间件：关键词检索用 MySQL ngram 全文索引，精排用硅基流动 rerank API。

## 1. 目标

解决纯向量检索的两个短板：

- **专有名词/编号/数字**：向量容易「语义相近但字面不对」，用关键词兜底
- **TopK 排序粗**：向量召回 20 条后用 cross-encoder 精排出前 5 条，回答引用的资料更准

## 2. 检索模式

| mode | 链路 | 用途 |
|---|---|---|
| `vector` | 向量召回 → 排序 | 对照/降级 |
| `keyword` | MySQL 全文召回 → 排序 | 调试关键词效果 |
| `hybrid`（默认） | 向量 + 关键词 → RRF 融合 → Rerank 精排 | 生产默认 |

- 请求 `mode` 为空时取 `search.mode`（默认 `hybrid`）
- 非法 mode → 400

## 3. 数据流

```
query
 ├─ bge-m3 向量化 → Milvus TopN ─┐
 └─ MySQL ngram 全文 TopN ───────┴─ RRF 融合 → 候选 TopN → Rerank → TopK
```

### 3.1 关键词召回（MySQL ngram）

`chunk.content` 上建全文索引，中文用内置 ngram parser（默认 2-gram）：

```sql
ALTER TABLE chunk ADD FULLTEXT INDEX ft_content (content) WITH PARSER ngram;
```

召回 SQL 约束（与向量侧权限一致）：

- `chunk.deleted = 0 AND chunk.status = 'SUCCESS'`
- `document.deleted = 0 AND document.status = 'SUCCESS'`
- USER 追加 `document.uploader_id = 当前用户`；ADMIN 不过滤
- `MATCH(content) AGAINST(? IN NATURAL LANGUAGE MODE) > 0`，按相关度倒序

### 3.2 RRF 融合

两路各取 `fetchN` 条，按名次融合，不依赖两边分数可比：

```
score(d) = Σ 1 / (60 + rank_i(d))
```

- `rank` 从 1 开始，常数 60 为文献默认值
- 同一片段（documentId + chunkIndex）只保留一份，分数累加
- 融合后按 score 倒序

### 3.3 Rerank 精排

- 取融合候选前 `rerank.max-candidates`（默认 20）条
- 调 `POST https://api.siliconflow.cn/v1/rerank`，模型 `BAAI/bge-reranker-v2-m3`
- 按 `relevance_score` 重排，只返回 TopK
- 响应 `score` 语义随模式变化：`vector`=余弦相似度；`keyword`=全文相关度；`hybrid` 未精排=RRF 分；精排后=rerank 分
- **失败降级**：rerank 超时/报错只记 warn，保留融合排序；`/qa/*` 主流程不因精排失败而失败

## 4. 接口变更（向后兼容）

`POST /search` 请求新增可选 `mode`；响应新增 `mode`、`reranked`：

```json
{ "query": "五个工作日", "topK": 3, "mode": "hybrid" }
→ { "query":"...", "topK":3, "mode":"hybrid", "reranked":true, "tookMs":980,
    "hits":[{"documentId":13,"chunkIndex":0,"fileName":"...","score":0.87,"content":"..."}] }
```

`/qa/ask`、`/qa/stream` 不暴露 mode，固定走 `search.mode`，Prompt 与引用编号逻辑不变。

## 5. 配置

```properties
search.mode=hybrid
rerank.enabled=true
rerank.base-url=https://api.siliconflow.cn
rerank.model=BAAI/bge-reranker-v2-m3
rerank.max-candidates=20
rerank.timeout-ms=8000
```

- 两路召回条数：`fetchN = rerank.enabled ? max(max-candidates, topK) : max(topK, topK*3)`，上限 50
- rerank 复用 `spring.ai.openai.api-key`（同一个硅基流动 Key）

## 6. 降级策略

| 失败点 | 行为 |
|---|---|
| 关键词 SQL 报错/index 缺失 | hybrid 降级为 vector（warn）；`mode=keyword` 返回 503 |
| Rerank 超时/非 200 | 保留 RRF 排序（warn），响应 `reranked=false` |
| 向量化失败 | 保持原行为：502（hybrid 不吞掉该错误） |
| 向量库失败 | 保持原行为：503 |

## 7. 验收标准

- [ ] `mode=keyword` 用「五个工作日」能命中文档 13，且 USER 越权查不到 test1 的文档
- [ ] `mode=vector` 与改造前行为一致（同 query 命中集合稳定）
- [ ] `mode=hybrid` 返回 `mode=hybrid`、`reranked=true`，score 降序
- [ ] 关键词与向量同时命中的片段只出现一次（RRF 去重）
- [ ] `/qa/ask`、`/qa/stream` 默认走 hybrid，引用编号与答案仍正常
- [ ] 关掉 rerank（`rerank.enabled=false`）时返回 `reranked=false`，结果不报错（配置级验证）
- [ ] 回归：空 query 400、超长 query 400、无命中 200 + 空数组

## 8. 已知限制

- ngram 全文对超短 query（1 个字）召回弱，属 MySQL ngram 特性
- RRF 常数、候选数、rerank 模型都写在配置/常量里，暂不做请求级可调
- 关键词召回直接查 MySQL，文档量很大时需换 ES/OpenSearch（M6 再评估）
