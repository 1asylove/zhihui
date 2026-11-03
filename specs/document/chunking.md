# 切片与向量入库规格（Chunking & Vector Ingestion Spec）

> 承接：`specs/document/document-ingestion.md`（文档落库与治理已闭环，本文只规定"正文 → 切片 → 向量 → Milvus"）
> 阶段：M3。内部里程碑编号与 `MIGRATION_PLAN.md` 的阶段表不完全一致，以 HANDOFF / 本文为准。

## 1. 目标

把 `document.content` 切成可检索的语义片段，写入 `chunk` 表并完成向量化，向量落 Milvus，为下一阶段的混合检索提供数据底座。

## 2. 范围

### 做

- 文本切分（固定长度 + 重叠，按段落/句子边界优先）
- `chunk` 表落库
- Java → Python 向量化契约（同步调用）
- 向量写入 Milvus（`zhihui_chunk` collection）
- 文档逻辑删除时联动清理 chunk 与向量

### 不做

- 检索、混合检索、Rerank、问答生成（M4）
- ES 全文索引（M5）
- MQ 异步链路、失败重试、断点续跑（M5）
- 重切片入口（重新上传生成新 document 即可；重切留 M5）

## 3. 切片规则

### 3.1 输入与前置条件

- 仅处理 `document.status = SUCCESS` 且有 `content` 的记录
- 解析失败（FAILED）的文档不产生 chunk

### 3.2 参数

| 参数 | 值 | 配置键 |
|---|---|---|
| 切片长度 | 500 字符 | `chunk.size` |
| 重叠长度 | 100 字符 | `chunk.overlap` |
| 向量维度 | 1024 | `embedding.dimension` |
| Embedding 模型 | `BAAI/bge-m3` | `embedding.model` |
| 向量化批大小 | 16 片/次 | `embedding.batch-size` |

按**字符**而非 token 计量：中文场景下字符数更稳定，不依赖具体 tokenizer。

### 3.3 切分算法（优先级从高到低）

1. 文本归一化：`\r\n` → `\n`；不压缩空白、不改写正文
2. 按段落切：以 `\n\n` 为界，段落 ≤ 500 字符则独立成片
3. 段落超长时按句子切：以 `。` `！` `？` `\n` 为界，累加到接近 500 字符
4. 单句仍 > 500 字符时硬切：每 500 字符一刀
5. 边界回退：片长接近 500 时向前搜索最近的自然边界（先找 `\n\n`，再找 `。！？\n`），搜索下界为 `start + 250`（即片长至少 250 字符，避免碎片化）；窗口内找不到边界才硬切到 500
6. 重叠：下一片起点 = 上一片终点 − 100（按原文坐标计算；`trim` 后实际共享字符可能略少于 100）
7. 每片首尾 `trim()`；丢弃纯空白片

> 说明：因为按自然边界回退，**片长与步长是浮动的**（片长 250~500，步长 150~400），不能再用 `ceil(len/500)` 估算片数。

### 3.4 边界

- `content` 为空或纯空白 → 0 片，document 保持 SUCCESS，写 WARN 日志，不算失败
- `chunk_index` 从 0 连续递增
- 单片长度 1~500；最后一片可短于 500
- 已知风险：超大文本（如 20MB txt）会产生数万片，M3 同步链路不做数量上限，验收只覆盖常规文档；限额与异步化留 M5

## 4. 数据模型

### 4.1 表 `chunk`

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| id | BIGINT | 否 | 主键，自增 |
| document_id | BIGINT | 否 | 关联 `document.id` |
| chunk_index | INT | 否 | 片序号，从 0 开始，同文档内连续 |
| content | TEXT | 否 | 切片原文（500 字符上限，TEXT 足够） |
| char_count | INT | 否 | 字符数，排查切片异常用 |
| milvus_id | VARCHAR(64) | 是 | Milvus 主键；未向量化时为 NULL |
| status | VARCHAR(20) | 否 | PENDING / SUCCESS / FAILED，默认 PENDING |
| error_msg | VARCHAR(500) | 是 | 失败原因，超长截断到 500 |
| create_time | DATETIME | 否 | 插入时间 |
| update_time | DATETIME | 否 | 更新时间 |
| deleted | TINYINT | 否 | 0 在用 / 1 已删除，默认 0 |

索引：`idx_document_id(document_id)`、`idx_status(status)`、`idx_deleted(deleted)`。

**DDL（直接在 Navicat 执行，注意先选库）**

```sql
USE ai_kb;

CREATE TABLE chunk (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  document_id BIGINT       NOT NULL COMMENT '所属文档 id',
  chunk_index INT          NOT NULL COMMENT '片序号，从 0 开始',
  content     TEXT         NOT NULL COMMENT '切片原文',
  char_count  INT          NOT NULL COMMENT '字符数',
  milvus_id   VARCHAR(64)  DEFAULT NULL COMMENT 'Milvus 主键，未向量化时为 NULL',
  status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
  error_msg   VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
  create_time DATETIME     NOT NULL COMMENT '创建时间',
  update_time DATETIME     NOT NULL COMMENT '更新时间',
  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '0 在用 / 1 已删除',
  PRIMARY KEY (id),
  KEY idx_document_id (document_id),
  KEY idx_status (status),
  KEY idx_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档切片表';
```

### 4.2 Milvus collection `zhihui_chunk`

| 字段 | 类型 | 说明 |
|---|---|---|
| id | VARCHAR(64) | 主键，值 = `milvus_id` |
| vector | FLOAT_VECTOR(1024) | bge-m3 输出 |
| document_id | INT64 | 检索时按文档过滤 |
| chunk_index | INT32 | 回溯定位 |
| uploader_id | INT64 | 权限过滤预留 |

- `milvus_id` 生成规则：`d{documentId}_c{chunkIndex}`，**确定性生成**，便于幂等 upsert
- 索引：HNSW，`metric_type=COSINE`，`M=16`，`efConstruction=200`

## 5. 状态流转

```
document.status = SUCCESS
    │
    ▼
 切片（内存） ──> 批量写 chunk(PENDING)
                      │
                      ├─> 向量化成功 ──> chunk.status=SUCCESS + milvus_id 回写
                      └─> 任一批失败 ──> 该批 chunk.status=FAILED + error_msg
```

约束：

- 已 SUCCESS 的片不会被后续失败回滚；失败只影响对应批次
- 切片/向量化失败**不回写 document.status**（document 的 SUCCESS 只表示"解析成功"）
- 每片状态变更必须同时更新 `update_time`

## 6. 向量化契约（Java → Python）

> 契约文件在 Step 3 落地为 `contracts/vectorize.openapi.yaml`，本文先定语义。

**请求** `POST {python.base-url}/internal/vectorize`

```json
{
  "model": "BAAI/bge-m3",
  "texts": ["第一片正文", "第二片正文"]
}
```

**响应 200**

```json
{
  "model": "BAAI/bge-m3",
  "dimension": 1024,
  "vectors": [[0.0123, -0.0456, "...共 1024 个"], ["..."]]
}
```

约束：

- `texts` 条数 1~16；返回 `vectors` 条数与 `texts` 严格一致
- 顺序必须一一对应（Java 侧按下标回写 `milvus_id`）
- Python 侧失败返回 4xx/5xx + `{"code","message"}`；Java 侧整批记 FAILED
- 网络超时 30s；不做自动重试（M5 异步链路再加重试）

## 7. 功能需求

- FR-1：上传解析成功后，同步执行"切片 → 写 chunk(PENDING) → 批量向量化 → 写 Milvus → 回写 milvus_id/SUCCESS"
- FR-2：切片严格按 3.2/3.3 参数；任意片 `char_count ≤ 500`，`chunk_index` 从 0 连续
- FR-3：重叠按原文坐标计算：下一片起点 = 上一片终点 − 100；片长上限 500、下限 250（末片例外）
- FR-4：`content` 为空 → 不产生 chunk，接口仍返回成功
- FR-5：`milvus_id` 确定性生成，Milvus 用 upsert 写入，重复执行不产生重复向量
- FR-6：`DELETE /documents/{id}` 联动：`chunk.deleted=1` + 按 `document_id` 删除 Milvus 向量
- FR-7：向量化失败 → 该批 chunk 记 FAILED + error_msg，其余批次继续（失败可观测，不静默）
- FR-8：上传接口响应体不变（仍返回 DocumentVO），切片结果不影响上传返回

## 8. 验收标准

- [ ] 上传 1 篇约 1200 字的 txt → `chunk` 表片数为 **3~5 片**（边界回退导致浮动，不再是固定 3 片），`chunk_index` 从 0 连续递增
- [ ] 每片 `char_count ≤ 500`（末片可 < 250），DB 抽查相邻片共享约 100 字符（因 trim 允许 90~100）
- [ ] 所有片 `status=SUCCESS` 且 `milvus_id` 非空，`milvus_id` 形如 `d5_c0`
- [ ] Milvus `zhihui_chunk` 实体数 = 该文档 SUCCESS 片数
- [ ] 直调 Python `/internal/vectorize`（2 条文本）→ 返回 `dimension=1024`，`vectors` 长度 2
- [ ] 停掉 Python 服务后上传 → chunk 全为 FAILED 且 `error_msg` 有值；document 仍 SUCCESS；接口仍返回 200
- [ ] 删除该文档 → `chunk.deleted=1`，Milvus 按 `document_id` 查不到该文档向量
- [ ] 回归：`GET /documents`、`GET /documents/{id}` 行为不变

## 9. 配置项

```properties
# 切片
chunk.size=500
chunk.overlap=100
# 向量化
embedding.model=BAAI/bge-m3
embedding.dimension=1024
embedding.batch-size=16
# Python 向量服务
python.base-url=http://localhost:8000
```

## 10. 风险与待定

| 项 | 说明 | 处置 |
|---|---|---|
| 超大文档 | 同步链路切片数不可控 | M3 验收只覆盖常规文档，限额/异步留 M5 |
| bge-m3 维度 | 需以实际返回为准 | Step 3 冒烟时校验 `dimension=1024`，不一致改规格与 collection |
| 硅基流动 embedding 限流 | 免费档有 QPS/批量限制 | 批大小 16、串行调用；遇 429 记 FAILED，不重试 |
| 切片切断句子 | 重叠可缓解，不保证语义完整 | M4 引入 Rerank 后再调参数 |
