# 多轮会话规格（会话表 + 上下文裁剪）

> 承接：`specs/qa/rag-answer.md`（Step2）、`specs/qa/rag-stream.md`（Step3）
> 阶段：M4 Step4。只增加「会话与历史」，检索、Prompt、流式通道全部复用。

## 1. 目标

让问答从「单次提问」变成「可追问的会话」：

- 每次提问自动归属一个会话，返回 `sessionId`
- 追问时把最近几轮对话注入 Prompt，模型能理解「它」「这个制度」等指代
- 会话可列表、看历史消息、逻辑删除，且**只有属主能访问**

## 2. 范围

### 做

- 新表 `chat_session`、`chat_message`
- `POST /qa/ask`、`POST /qa/stream` 请求体增加可选 `sessionId`，响应/`meta` 返回 `sessionId`
- `GET /sessions`、`GET /sessions/{id}/messages`、`DELETE /sessions/{id}`
- 历史注入：最近 3 轮、总字符上限 1200，放在【参考资料】之前
- 消息落库：user 消息在调模型前写入；assistant 消息在成功结束后写入

### 不做

- query 改写（用历史问题补全指代）
- 断线续传、`Last-Event-ID`
- 会话重命名、置顶、跨会话检索
- 前端页面（本项目暂无前端）

## 3. 接口

契约见 `contracts/qa-session.openapi.yaml`；`/qa/ask`、`/qa/stream` 的增量见各自契约。

### 3.1 提问（沿用原接口）

```
POST /qa/ask      权限：chat:ask
{ "question": "...", "topK": 5, "sessionId": 123 }   // sessionId 可省略

→ { "code":200, "data": { "sessionId":123, "question":"...", "answer":"...",
                          "citations":[...], "tookMs":1234 } }
```

- `sessionId` 省略/为 null：新建会话，`title` 取问题去空白后的前 30 字
- `sessionId` 存在：校验属主与 `deleted=0`，不属于当前用户一律 **404**（不暴露存在性）
- `POST /qa/stream` 的 `meta` 事件变为 `{"sessionId":123,"citations":[...]}`

### 3.2 会话管理

```
GET    /sessions                 权限：chat:ask   → 当前用户会话，updated_at 倒序
GET    /sessions/{id}/messages   权限：chat:ask   → 消息按 id 升序，含 citations
DELETE /sessions/{id}            权限：chat:ask   → 逻辑删会话，消息一并逻辑删
```

越权 / 不存在 / 已删除统一返回 `404 会话不存在或已删除`。

## 4. 数据模型

```sql
CREATE TABLE chat_session (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  user_id    BIGINT       NOT NULL,
  title      VARCHAR(100) DEFAULT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted    TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_user (user_id, deleted, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_message (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  session_id     BIGINT      NOT NULL,
  role           VARCHAR(16) NOT NULL,          -- user / assistant
  content        TEXT,
  citations_json TEXT         DEFAULT NULL,     -- assistant 消息的引用数组
  created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted        TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_session (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> 与既有表一致：主键自增、显式 `deleted` 字段，**不使用 `@TableLogic`**，查询手动带 `deleted = 0`。

## 5. 上下文策略

取当前会话**最近 3 轮**（即最近 6 条 user/assistant 消息），从新到旧累计字符，超过 **1200** 停止，再按时间正序拼进 Prompt：

```
【历史对话】
用户：制度由哪个部门负责解释
助手：由信息技术部负责解释 [1]

【参考资料】
[1] 来源：xxx.txt（第 1 片）
...

【问题】它由谁监督执行
请依据以上资料回答，并在引用处标注编号。
```

- 历史只用于理解指代，**不参与引用编号**；`[n]` 仍只对应本次 `citations`
- 超限按「整条消息」丢弃，不截断半句
- 无命中时 Prompt 不发给模型，历史只在有命中时生效

## 6. 实现要点

- `ChatHistoryService`：建会话 / 属主校验 / 取最近消息 / 追加消息 / 列表 / 详情 / 逻辑删
- `QaService.prepare()`：先取历史 → 检索 → 落 user 消息 → 组装 Prompt（历史在资料之前）
- 非流式：`ask()` 成功返回前落 assistant 消息
- 流式：`doOnNext` 累积 token，`doOnComplete` 落 assistant 消息；**出错走 `error` 事件时不落库**
- 无命中：`ask` 与 `stream` 都落固定话术；`citations_json` 为空数组
- 会话 `updated_at` 每次追加消息时刷新（列表排序依据）

## 7. 配置项

```properties
chat.history.max-rounds=3
chat.history.max-chars=1200
```

## 8. 验收标准

- [ ] 不带 `sessionId` 提问 → 自动建会话，返回新 `sessionId`，`title` 为问题前 30 字
- [ ] 带 `sessionId` 追问指代型问题 → 回答能利用上文（如「它由哪个部门解释」）
- [ ] `POST /qa/stream` 的 `meta` 含 `sessionId`，流正常结束后 assistant 消息入库
- [ ] `GET /sessions` 只返回自己的会话，按最近活跃排序
- [ ] `GET /sessions/{id}/messages` 返回 user/assistant 完整消息，assistant 带 citations
- [ ] `DELETE /sessions/{id}` 后再取列表/详情均不可见
- [ ] test2 访问 test1 的 `sessionId` → 404（提问、详情、删除三个入口都验证）
- [ ] 历史裁剪生效：超过 3 轮只带最近 3 轮，总字符不超 1200
- [ ] 回归：`/qa/ask`、`/qa/stream`、`/search` 原有用例不受影响

## 9. 风险与待定

| 项 | 说明 | 处置 |
|---|---|---|
| 指代检索失败 | 追问「它由谁解释」的向量可能召回不到原文档 | 本期不改写 query；Step5 前可考虑「历史 + 当前问题」拼接检索 |
| 中途断开不落库 | SSE 被取消不是 `onComplete`，assistant 消息丢失 | 可接受；后续用 `doFinally` 判断是否已产出完整答案 |
| 会话列表无分页 | 当前用户会话全量返回 | 会话量上来后加分页（接口保持兼容） |
| 无并发保护 | 同一会话并发提问时消息顺序可能交错 | 当前单用户使用，忽略 |
