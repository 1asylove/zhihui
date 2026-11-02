# 文档入库与治理规格（Document Ingestion & Governance Spec）

> 承接：`specs/document/document-upload.md`（上传与解析规则不变，本文只规定"落库之后"的事）

## 1. 目标

上传成功后，文档由"磁盘上的一个文件"变为"库中一条有状态、可查询、可删除的记录"。

## 2. 范围

### 做

- 上传结果落库（`document` 表）
- 支持格式：txt / md / pdf / docx（md 与 txt 复用 `TxtParser`）
- 状态流转：PENDING → PARSING → SUCCESS / FAILED
- 文档列表（分页）、文档详情
- 逻辑删除
- 上传人归属与列表可见性

### 不做

- 上传入口、格式识别与解析器分发：沿用 `document-upload.md`
- 切片、向量化、写入 Milvus / ES：M3
- 上传链路异步化（MQ）：M5；本文状态机已为其预留 PENDING / PARSING 中间态

## 3. 数据模型

### 3.1 表 `document`

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| id | BIGINT | 否 | 主键，自增 |
| file_name | VARCHAR(255) | 否 | 上传时的原始文件名（含扩展名） |
| file_path | VARCHAR(500) | 否 | 落盘后的绝对路径；物理文件名 = 唯一标识（UUID）+ 扩展名 |
| file_size | BIGINT | 否 | 文件字节数，列表展示与审计用 |
| file_type | VARCHAR(20) | 否 | 小写扩展名，如 `txt` / `pdf` / `docx` |
| content | LONGTEXT | 是 | 解析出的正文；PENDING / PARSING / FAILED 时为空 |
| status | VARCHAR(20) | 否 | 见第 4 节，默认 PENDING |
| error_msg | VARCHAR(500) | 是 | 失败原因，超长截断到 500 字符 |
| uploader_id | BIGINT | 否 | 上传人，对应 `user.id` |
| create_time | DATETIME | 否 | 创建时间，插入时写入 |
| update_time | DATETIME | 否 | 更新时间，每次状态变更写入 |
| deleted | TINYINT | 否 | 0 在用 / 1 已删除，默认 0 |

索引建议：`idx_uploader_id(uploader_id)`、`idx_deleted_create_time(deleted, create_time)`。

字段用途自检：`file_size` 用于列表展示与审计；`error_msg` 只在 FAILED 时有值；`deleted` 同时是列表与详情的过滤条件。

## 4. 状态流转

```
上传落盘成功
    │
    ▼
 PENDING ──> PARSING ──> SUCCESS   （终态）
                │
                └─────> FAILED    （终态，写 error_msg）
```

| 状态 | 写入时机 | 触发方 |
|---|---|---|
| PENDING | 文件落盘成功后、解析开始前，插入记录 | 上传服务 |
| PARSING | 调用解析器前，更新记录 | 上传服务 |
| SUCCESS | 解析成功，`content` 与 `status` 在同一条 update 中写入 | 上传服务 |
| FAILED | 解析抛异常，写 `status=FAILED` + `error_msg`，`content` 置空 | 上传服务（catch 分支） |

约束：

- SUCCESS / FAILED 为终态，**不回退、不重试**；FAILED 不提供重试入口（M5 引入 MQ 后再议）
- 任意状态变更都必须同时更新 `update_time`
- 删除（`deleted=1`）与 `status` 无关，任何状态都可被逻辑删除
- 进程中断可能遗留 PENDING / PARSING 记录；M2 不做补偿，留给 M5 的 MQ 重试机制

## 5. 功能需求

- FR-1：`POST /documents/upload` 在主链路中依次写入 PENDING → PARSING → SUCCESS；成功后返回该文档记录（含 id、文件名、状态），不再返回纯文本
- FR-2：解析失败时仍返回 HTTP 200，返回体中 `status=FAILED` 且带 `errorMsg`；记录保留、不删除，`content` 为空
- FR-3：文件为空或扩展名不支持时，沿用 `document-upload.md` 的拒绝逻辑，**不落库、不落盘**
- FR-4：同一用户重复上传同名文件，允许存在多条记录，互不覆盖（`file_name` 不唯一）；落盘文件名必须加唯一标识（UUID 或时间戳），保证每条记录的 `file_path` 各不相同
- FR-5：`GET /documents?page=&size=` 分页返回 `deleted=0` 的记录，按 `create_time` 倒序；**响应中不含 `content`**；ADMIN 可见全部，USER 仅可见 `uploader_id` 为自己的记录
- FR-6：`GET /documents/{id}` 返回单条记录（**含 `content`**），可见性规则同 FR-5；记录不存在或 `deleted=1` 时返回 404
- FR-7：`DELETE /documents/{id}` 仅置 `deleted=1` 并更新 `update_time`，**物理文件保留**；已删除或不存在的记录再次删除返回 404
- FR-8：权限沿用 M1 权限点：上传 `doc:upload`、删除 `doc:delete`；列表与详情仅要求登录（无独立权限点）

## 6. 接口契约

见 `contracts/document.openapi.yaml`。本文定稿后，M2 Step 2 需要按下面清单扩写契约：

| 接口 | 变更 |
|---|---|
| `POST /documents/upload` | 响应由 `text/plain` 改为 JSON `Result<DocumentVO>`（`data` 含 id / fileName / status / errorMsg） |
| `GET /documents` | 新增，参数 `page`（默认 1）、`size`（默认 10），返回分页对象 |
| `GET /documents/{id}` | 新增，返回含 `content` 的详情 |
| `DELETE /documents/{filename}` | **路径参数由 `filename` 改为 `id`**：文件名可重复、可被改写，id 才唯一稳定；响应由纯文本改为 `Result<Void>` |

统一响应沿用 `Result<T>`（`code` / `message` / `data`）；错误码：400 参数格式错误、401 未登录、403 缺少权限、404 记录不存在、413 文件超过 20MB。

契约变更后需同步更新 Apifox 中的接口定义。

## 7. 验收标准

- [ ] 上传 txt → 库中一条记录，`status=SUCCESS`，`content` 非空，`uploader_id` 为当前用户
- [ ] 上传损坏的 pdf → HTTP 200，`status=FAILED`，`error_msg` 有内容，`content` 为空
- [ ] 上传 `.exe` → 被拒绝，库中无新增记录
- [ ] 同用户上传两次 `a.txt` → 两条记录，均 SUCCESS，且磁盘上是两个文件、`file_path` 不同
- [ ] USER 调用列表 → 只看到自己的文档；ADMIN 调用 → 看到全部
- [ ] 列表第 1 页 → 响应中无 `content` 字段，按创建时间倒序
- [ ] 详情按 id → 返回 `content`；删除后再查 → 404
- [ ] USER 删除 → 403；ADMIN 删除 → 200，DB 中 `deleted=1`，磁盘文件仍存在
- [ ] 无 token 调任一接口 → 401
