# 检索服务规格（Retrieval Spec）

> 承接：`specs/document/chunking.md`（切片）、`specs/document/vector-store.md`（向量写入）
> 阶段：M4 Step 1。里程碑口径以 HANDOFF 为准（M4 = RAG 问答）。

## 1. 目标

给定用户问题，把问题向量化，在 Milvus `zhihui_chunk` 中做 TopK 相似检索，回表校验并把结果组装成**带来源的引用片段**，为 Step2 的 Prompt 拼接提供输入。

只做「检索」，不生成答案。

## 2. 范围

### 做

- `POST /search`：query → bge-m3 → Milvus → 回表 → TopK 引用片段
- 权限过滤：ADMIN 全量，USER 只看自己上传的文档
- 回表兜底：`document.deleted=0`，过滤孤儿向量
- 耗时统计与结构化日志

### 不做

- Prompt 拼接、LLM 生成、SSE 流式（Step2 / Step3）
- 多轮会话与上下文裁剪（Step4）
- ES 全文、混合检索、Rerank（Step5 可选）
- 检索结果缓存（M5 Redis 再做）

## 3. 接口

契约见 `contracts/search.openapi.yaml`。

```
POST /search          权限：chat:ask
Request:  { "query": "...", "topK": 5 }
Response: { "code":200, "message":"成功", "data": { "query", "topK", "tookMs", "hits":[...] } }
```

单条命中（SearchHit）：

| 字段 | 类型 | 说明 |
|---|---|---|
| documentId | Long | 来源文档 id |
| chunkIndex | Integer | 片序号，从 0 开始 |
| fileName | String | 来源文件名，供前端展示引用 |
| score | Double | COSINE 相似度，仅用于排序与展示，**不用于过滤** |
| content | String | 片段原文 |

约束：

- `query` 去首尾空白后不能为空，长度 ≤ `search.max-query-length`（默认 500），超限 400
- `topK` 缺省 `search.default-topk`（5），夹紧到 `[1, search.max-topk]`（20），越界不报错
- 检索无命中 → 200 + `hits: []`，不是 404
- 响应不包含 `uploader_id`、`file_path`、`milvus_id` 等内部字段

## 4. 流程

```
POST /search
   │
   ├─① 校验 query / topK
   ├─② vectorizeClient.embed(List.of(query)) → 1024 维查询向量
   ├─③ milvusService.search(vector, topK, expr)
   │     expr: ADMIN → "document_id > 0"（恒真）
   │           USER  → "uploader_id == {当前用户 id}"
   ├─④ 取命中 documentId 集合 → 一次 SELECT 回表 document
   │     条件：id IN (...) AND deleted = 0 AND status = 'SUCCESS'
   ├─⑤ 丢弃回表查不到的命中（孤儿向量 / 已删文档）
   ├─⑥ 组装 SearchHit（fileName 来自回表，其余来自 Milvus）
   └─⑦ 返回，记录 tookMs + 命中数
```

约束：

- 回表**必须一次批量查询**，不允许逐条 `selectById`（N+1）
- 回表结果允许为空：全部命中被过滤 → `hits: []`
- Milvus 返回的 `content` 直接用，不再回表查 `chunk.content`（避免第二次查询）

## 5. 权限过滤

`uploader_id` 已在 M3 写入 collection，正是为检索权限预留：

| 角色 | Milvus 过滤表达式 | 说明 |
|---|---|---|
| ADMIN | `document_id > 0` | 全量；用恒真表达式而非空 expr，规避 SDK 版本差异 |
| USER | `uploader_id == {userId}` | 在向量库侧就完成隔离，避免"先召回再丢"造成 TopK 被自己无权看的片段挤占 |

双重校验：

1. 向量库侧 `uploader_id` 过滤（主）
2. 回表 `document.deleted=0 AND status='SUCCESS'`（兜底）

> 不额外校验 `document.uploader_id`：若 Milvus 的 `uploader_id` 与 MySQL 不一致，回表时可用 `uploader_id` 再确认一次；Step1 先不做，留待 Step2 评估。

## 6. TopK 与阈值策略

**禁止绝对分数阈值过滤。**

M3 实测（bge-m3 + COSINE）：相关片段 0.5544 / 0.5984，无关片段 0.31。任何 `score > 0.7` 的规则都会把正确答案全部丢掉。

策略：

- 只用 **TopK 相对排序**，Milvus 已按 score 降序返回
- 可选兜底 `search.min-score`（默认 0，即关闭）；设 0.2 只为过滤"明显无关"，**不得设高于 0.5**
- `score` 原样返回给前端做展示/引用强度提示，不参与业务判断

## 7. 配置项

```properties
# ===== 检索 =====
search.default-topk=5
search.max-topk=20
search.max-query-length=500
search.min-score=0
```

## 8. 异常与降级

| 场景 | HTTP | Result.code | 说明 |
|---|---|---|---|
| query 为空 / 超长 / 请求体缺失 | 400 | 400 | `BizException(400)` |
| 向量化服务不可用 | 502 | 502 | `VectorizeClient` 已抛 `BizException(502)` |
| Milvus 不可用 | 503 | 503 | `MilvusService` 已抛 `BizException(503)` |
| 正常但无命中 | 200 | 200 | `hits: []` |

> `BizException` 由 `GlobalExceptionHandler` 映射为**真实 HTTP 状态码**（与 `GET /documents` 的 404 一致），响应体仍是 `Result`。

## 9. 验收标准

前置：Python 向量服务在线（真实 bge-m3）；Zilliz `zhihui_chunk` 中有 document id=13 的 3 条向量；test1=ADMIN、test2=USER。

- [ ] test1 调 `POST /search`，query 取「制度由哪个部门负责解释」→ `hits` 非空，top1 `documentId=13`，`score` 约 0.55~0.60
- [ ] 返回字段完整：`documentId / chunkIndex / fileName / score / content`，无 `uploader_id` / `milvus_id`
- [ ] `fileName` 为 `zhihui_step4_isolation.txt`，与 MySQL 一致
- [ ] query 换成无关内容（如「今天天气怎么样」）→ 仍可能返回结果，但 score 明显更低（约 0.3）；确认代码里**没有**绝对分数阈值
- [ ] test2（USER，id=5）调同一 query → `hits: []`（id=13 属 test1），证明 `uploader_id` 过滤生效
- [ ] test2 上传一篇文档后再查该文档相关内容 → 能召回自己的，仍看不到 test1 的
- [ ] 把 document id=13 逻辑删除后重查 → `hits` 不含 13（回表兜底生效；Milvus 中向量可能仍存在）
- [ ] `query` 传空串 → code 400；传 501 字 → code 400
- [ ] `topK=0` → 按 1 处理；`topK=999` → 按 20 处理；均不报错
- [ ] 停掉 Python → code 502；停掉/改错 Milvus → code 503；接口本身不 500
- [ ] `tookMs` 有值且量级合理（本机 + 跨洋 Zilliz 约 300~2000ms）
- [ ] 回归：`GET /documents`、`GET /documents/{id}`、`DELETE /documents/{id}`、上传均不受影响

## 10. 风险与待定

| 项 | 说明 | 处置 |
|---|---|---|
| Zilliz 跨洋延迟 | 单次检索 300ms+，叠加 LLM 更慢 | Step3 SSE 先让首字尽快出来；M6 再考虑缓存 |
| TopK 召回噪声 | 无关 query 也会返回 0.3 的结果 | Step2 Prompt 中明确"若资料不足请说明"；Step5 用 Rerank 收紧 |
| 同一文档多片重复 | TopK 可能被同一文档的相邻片占满 | Step1 不去重，Step2 观察后再定（可按 documentId 限制条数） |
| 权限字段不一致 | Milvus `uploader_id` 与 MySQL 不同步 | 删除走"先删向量"；检索靠回表兜底；M5 补偿任务 |
| 长 query 语义偏移 | bge-m3 对超长问题效果下降 | 限制 500 字；Step2 做 query 改写/压缩 |
