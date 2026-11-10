# 向量存储与写入规格（Vector Store & Ingestion Spec）

> 承接：`specs/document/chunking.md`（切片规则与 `chunk` 表已定稿，本文只规定"chunk → 向量 → Milvus → 状态回写 → 删除联动"）
> 阶段：M3 Step 4。里程碑编号以 HANDOFF 为准。

## 1. 目标

把 `chunk` 表中的切片文本经 Python 向量化服务转成 1024 维向量，写入 Milvus `zhihui_chunk` collection，并在 MySQL 侧回写 `milvus_id` 与 `status`，让 M4 的混合检索有可查的数据底座。

## 2. 范围

### 做

- Milvus 连接与 collection 自动初始化（存在即复用，不存在则创建 + 建索引 + load）
- 切片批量向量化 → upsert 写入 → 回写 `chunk.status/milvus_id/update_time`
- 失败隔离：单批失败只影响该批
- 文档逻辑删除时清理 chunk 与向量

### 不做

- 检索接口、混合检索、Rerank、问答（M4）
- ES 全文索引（M5）
- MQ 异步、失败重试、断点续跑、补偿任务（M5）
- 切片重算、向量重建接口（重传文档即可）

## 3. 部署形态

同一套代码支持两种后端，只改配置：

| 形态 | `milvus.uri` | `milvus.token` | 说明 |
|---|---|---|---|
| 本地 Docker standalone | `http://localhost:19530` | 空 | 开发默认；依赖 WSL2 + Docker Desktop |
| Zilliz Cloud 免费实例 | `https://<cluster>.<region>.cloud.zilliz.com:443` | `<user>:<password>` | 免安装，需联网 |

不支持 Milvus Lite（仅 Python 客户端，Java SDK 无法连接）。

## 4. Collection 设计 `zhihui_chunk`

### 4.1 Schema

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| milvus_id | VarChar(64) | 主键，autoID=false | 值 = `d{documentId}_c{chunkIndex}` |
| document_id | Int64 | 必填 | 检索/删除的过滤键 |
| chunk_index | Int32 | 必填 | 回溯定位 |
| uploader_id | Int64 | 必填 | 权限过滤预留（M4 用） |
| content | VarChar(2048) | 必填 | 切片原文，供 M4 直接返回引用（不必回表） |
| vector | FloatVector(1024) | 必填 | bge-m3 输出 |

约束：

- 关闭 `autoID`，主键由业务生成 → 支持幂等 upsert
- 关闭 dynamic field（`withEnableDynamicField(false)`），字段写错立刻报错而不是静默塞进动态列
- `content` 的 `max_length` 按**字节**计算：中文 3 字节/字，500 字约 1500 字节，取 2048 留余量
- collection 维度必须等于 `vectorize.expected-dimension`；不一致时启动即报错，不允许"带病写入"

### 4.2 索引

| 项 | 值 |
|---|---|
| index_type | `AUTOINDEX`（standalone 与 Zilliz Cloud 均支持；后续可换 HNSW） |
| metric_type | `COSINE` |
| index 名 | `vector_idx` |
| 建索引后 | 必须 `loadCollection` 才能检索 |

> 选 COSINE 而非 L2：bge-m3 向量已归一化语义相似度更稳定，且 COSINE 对长度差异不敏感。

## 5. 写入流程（同步链路）

```
上传 → 解析 SUCCESS → 切片 → chunk(PENDING)
                                  │
                                  ▼
                        listByDocument(documentId) 取待处理片
                                  │
                                  ▼
                   按 batch-size(16) 分批，逐批执行：
                   ① POST /internal/vectorize 取 1024 维向量
                   ② Milvus upsert（6 字段全量写入）
                   ③ 回写 chunk.status=SUCCESS, milvus_id, update_time
                                  │
                   任一批任一步失败 →
                   ④ 该批 chunk.status=FAILED, error_msg(≤500), update_time
                   ⑤ 继续处理下一批
```

约束：

- **只处理 `status=PENDING` 的片**，已 SUCCESS 的片跳过（重入安全）
- 每批成功后立即回写，不做"全部成功才提交"，避免中途失败导致状态与 Milvus 不一致
- 向量化失败**不回写 `document.status`**（document 的 SUCCESS 只代表解析成功）
- 整个向量化过程在独立 try/catch 内，异常不影响上传接口返回（仍 200 + DocumentVO）
- 向量化总耗时随片数线性增长；M3 不做超时熔断，M5 异步化后再说

## 6. 删除联动

`DELETE /documents/{id}` 执行顺序：

```
① 查文档存在且 deleted=0（不存在 → 404，与现状一致）
② Milvus 按表达式 document_id == {id} 删除向量（失败仅 WARN，不阻断）
③ chunk 按 document_id 批量置 deleted=1 + update_time
④ document 逻辑删除 deleted=1 + update_time
```

约束：

- **先删向量、再删元数据**：反过来若向量删除失败，MySQL 已查不到文档，孤儿向量将永久失去归属
- 向量删除失败不阻断文档删除（Milvus 维护窗口下仍要允许业务侧删除），但必须打 WARN 日志留痕
- 孤儿向量由 M4 检索兜底：检索命中后回 MySQL 校验 `document.deleted=0`，已删文档的向量不参与召回
- 重复删除仍返回 404（与 M2 行为一致，由 ① 保证）

## 7. 幂等与一致性

| 场景 | 行为 |
|---|---|
| 同一文档重复向量化 | `milvus_id` 确定性生成 + upsert → 覆盖不重复 |
| 上传成功但向量化失败 | chunk=FAILED，可用 SQL 重跑（M5 提供重跑入口） |
| Milvus 有向量但 chunk=FAILED | 不会发生：先 upsert 成功才回写 SUCCESS；若回写失败，下次重跑 upsert 覆盖同一主键 |
| Milvus 不可用 | 整批 FAILED + error_msg，document 仍 SUCCESS，上传仍 200 |

## 8. 配置项

```properties
# ===== Milvus =====
milvus.uri=http://localhost:19530
milvus.token=
milvus.collection=zhihui_chunk
milvus.metric=COSINE
```

维度复用既有 `vectorize.expected-dimension=1024`，不重复定义。

## 9. 验收标准

前置：Milvus 19530 可连通、Python 向量服务可用（无 key 期间用本地 mock 返回 1024 维）。

- [ ] 启动应用，`zhihui_chunk` collection 自动创建；Milvus 中 `describe collection` 可见 6 字段，主键 `milvus_id`
- [ ] 上传 1 篇约 1200 字 txt → `chunk` 全部 `status=SUCCESS`，`milvus_id` 形如 `d{id}_c0`
- [ ] Milvus 实体数 = 该文档 `status=SUCCESS` 的片数（无重复、无缺失）
- [ ] 上传同一文件两次（生成两个 document）→ 两套 `milvus_id` 互不覆盖
- [ ] 同一文档重复执行向量化 → Milvus 实体数不变（upsert 幂等）
- [ ] 停掉 Python 服务后上传 → chunk 全 FAILED 且 `error_msg` 有值；document 仍 SUCCESS；接口仍 200
- [ ] 停掉 Milvus（或改错 uri）后上传 → 同上，接口不 500
- [ ] 删除文档 → `chunk.deleted=1`；Milvus 以 `document_id == id` 查询结果为 0
- [ ] 重复删除 → 404
- [ ] 回归：`GET /documents`、`GET /documents/{id}` 行为不变

## 10. 风险与待定

| 项 | 说明 | 处置 |
|---|---|---|
| Milvus 删除失败留孤儿向量 | 不阻断业务删除 | M4 检索回表校验 `document.deleted=0` 兜底；M5 补偿任务 |
| 维度不一致 | collection 已建为 1024，换 embedding 模型需重建 | 启动校验维度，不匹配直接失败；换模型视为破坏性变更 |
| 大批量写入 | 同步链路，片数多则耗时线性增长 | M3 只覆盖常规文档；M5 改 MQ 异步 + 限速 |
| Zilliz Cloud 网络依赖 | 演示环境断网即不可用 | 面试演示优先本地 Docker |
| upsert 语义 | Milvus 2.4 的 upsert 实际是 delete+insert | 只用于同主键覆盖，不做局部字段更新；写入必须 6 字段全量 |
