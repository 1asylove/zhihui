# M5 规格：Agent 问答（LangGraph 最小闭环）

> 版本 0.1.0 ｜ 2026-11-12 ｜ 对应契约 `contracts/agent.openapi.yaml`

## 1. 目标与范围

让 Python 侧用 **LangGraph** 编排一个单 Agent，统一入口承接用户提问：

- 能判断「闲聊」还是「知识问题」，分别走直接回答 / 工具回答
- 知识问题通过 **工具调用** 使用 Java 侧已有的 `/search`、`/qa/ask` 能力
- Java 保留 `/qa/ask` 作为**降级通道**：Python 不可用或超时，Java 直接走原 RAG 问答
- 会话存储、权限、数据访问**仍全部在 Java**；Python 无状态、不碰 MySQL、不碰文件系统

首版只做「最小闭环」，不做多 Agent、不做计划分解后的并行工具、不做自检反思循环。

## 2. 架构与调用链

```
前端
 │  POST /agent/chat  (Bearer JWT)
 ▼
Java  AgentController           ← 会话创建/校验、历史加载、消息落库
 │  POST /internal/agent/chat  (Bearer JWT 透传 + history + userId/admin)
 ▼
Python FastAPI /internal/agent/chat
 │
 ├─ route 节点（LLM 结构化输出）─ chitchat ─→ answer 节点 ─→ END
 │
 └─ route 节点 ─ knowledge ─→ tools 节点
                               │  Authorization 透传
                               ├─ search_knowledge → Java POST /search
                               └─ rag_answer       → Java POST /qa/ask (persist=false)
                               ▼
                             answer 节点 ─→ END
```

**身份透传**：Java 网关把当前请求的 `Authorization` 头原样传给 Python；Python 调 Java 工具时带回同一个头。权限判定只发生在 Java，Python 不解析 token。

**落库职责**：只有 Java 网关落 `chat_message`。Python 调 `/qa/ask` 时必须带 `persist=false`，避免「网关落一次 + 内部问答又落一次」的重复消息。

## 3. Java 对外接口

`POST /agent/chat`（权限 `chat:ask`）

请求：
```json
{ "question": "制度由哪个部门负责解释", "sessionId": 9, "topK": 5, "mode": "hybrid" }
```

响应 `data`：
```json
{
  "sessionId": 9,
  "answer": "制度由信息技术部负责解释…[1]",
  "citations": [{ "documentId": 13, "chunkIndex": 2, "fileName": "...", "score": 0.82, "content": "..." }],
  "route": "knowledge",
  "toolsUsed": [{ "name": "search_knowledge", "ok": true, "hitCount": 3, "tookMs": 210 }],
  "degraded": false,
  "tookMs": 3200
}
```

行为约束：
- `sessionId` 缺省 → 自动新建（title 取问题前 30 字）；传入 → 属主校验，越权/不存在/已删除一律 **404**
- 调 Python 前先落 **user** 消息；拿到回答后落 **assistant** 消息（含 `citations_json`）
- Python 调用失败/超时 → 降级：Java 本地执行一次原 `/qa/ask` 流程，返回 `degraded=true`、`route=fallback`
- 无检索命中时回答为固定话术，`citations=[]`，且不编造编号

## 4. Python 内部接口

`POST /internal/agent/chat`，必须带 `Authorization: Bearer <用户 JWT>`

请求：
```json
{
  "sessionId": 9,
  "question": "制度由哪个部门负责解释",
  "userId": 4,
  "admin": true,
  "topK": 5,
  "mode": "hybrid",
  "history": [{ "role": "user", "content": "..." }, { "role": "assistant", "content": "..." }]
}
```

响应：
```json
{ "answer": "...", "citations": [...], "route": "knowledge", "toolsUsed": [...], "tookMs": 2900 }
```

- `history` 由 Java 按已有规则裁剪后传入（最近 3 轮 / 1200 字），Python 不再二次裁剪
- Python 不返回 `sessionId`，会话 id 由 Java 维护，避免两边各自建会话

## 5. Agent 图设计（最小）

| 节点 | 职责 | 实现要点 |
|---|---|---|
| `route` | 判断意图 + 选工具 | LLM 结构化输出 `{intent: chitchat\|knowledge, tool: search_knowledge\|rag_answer}`；解析失败按 `knowledge + search_knowledge` 兜底 |
| `tools` | 执行工具 | 只执行一次（首版不做循环）；失败记录 `toolsUsed[].ok=false`，不抛给用户 |
| `answer` | 生成回答 | 有工具结果 → 带引用约束的 Prompt；chitchat → 直接回答，禁止编造引用 |

边：`START → route`；`route → answer`（chitchat）；`route → tools → answer`（knowledge）；`answer → END`。

硬约束：
- 工具调用上限 **1 次**（配置项 `agent.max-tool-calls=1`），超出直接进 `answer`
- 总超时 **20s**（`agent.timeout-ms=20000`），超时由 Java 侧判定并降级
- 模型温度 0.2，与 `/qa/ask` 保持一致

## 6. 工具定义

| 工具 | 调用 | 参数 | 返回 |
|---|---|---|---|
| `search_knowledge` | `POST /search` | `{query, topK, mode}` | `hits[]`（documentId/chunkIndex/fileName/score/content） |
| `rag_answer` | `POST /qa/ask` | `{question, topK, persist:false}` | `{answer, citations[]}` |

- 两个工具都必须透传 `Authorization`
- `rag_answer` 的 `persist=false` 是**新增可选字段**（默认 true），只影响会话落库，不改变问答逻辑
- 工具结果统一转成 `{text, citations, meta}` 再交给 `answer` 节点

## 7. 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `agent.base-url` | `http://127.0.0.1:8100` | Python Agent 服务地址 |
| `agent.timeout-ms` | `20000` | Java 调 Python 的读超时 |
| `agent.max-tool-calls` | `1` | Python 侧工具调用上限 |
| `agent.enabled` | `true` | 关闭后 `/agent/chat` 直接走降级 |

Python 侧（`python/.env` 或环境变量）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_MODEL` | `Qwen/Qwen2.5-7B-Instruct` | 编排/生成模型（与 Java 侧保持一致） |
| `AGENT_BASE_URL` | `https://api.siliconflow.cn/v1` | OpenAI 兼容地址 |
| `JAVA_BASE_URL` | `http://127.0.0.1:8080` | 工具回调地址 |
| `AGENT_MAX_TOOL_CALLS` | `1` | 工具调用上限 |
| `AGENT_TIMEOUT` | `20` | 单次图执行超时（秒） |

## 8. 降级与错误码

| 场景 | 表现 |
|---|---|
| Python 连接失败 / 超时 / 5xx | 200 + `degraded=true`、`route=fallback`，回答来自 Java `/qa/ask` |
| Python 返回 4xx（参数问题） | 500（属实现缺陷，不静默降级） |
| 工具调用失败 | 不降级，`toolsUsed[].ok=false`，回答退化为「未检索到依据」固定话术 |
| 用户 JWT 无效 | `route` 前即失败，Java 返回 401 |
| 会话越权 | 404，不调用 Python |

## 9. 验收标准

1. `POST /agent/chat` 问「制度由哪个部门负责解释」→ 200，`route=knowledge`，`toolsUsed` 含 `search_knowledge`，回答含 `[1]`，`citations` 非空
2. 问「你好」→ 200，`route=chitchat`，`toolsUsed=[]`，`citations=[]`
3. 带 `sessionId` 追问 → 复用会话，消息表按 `user/assistant` 追加，Python 收到 `history`
4. 不存在的 `sessionId` / 他人会话 → 404
5. 关掉 Python（或改错 `agent.base-url`）→ 200 + `degraded=true`，回答正常
6. 日志可见每步耗时：`route`、工具、`answer`、总耗时
7. test2（USER）问知识问题 → 工具返回 0 命中，回答固定话术，`citations=[]`
8. `/qa/ask`、`/qa/stream`、`/search` 回归正常

## 10. 非目标（后续版本）

- 多工具并行 / 计划分解 / 自检反思重试
- Agent 流式输出（首版非流式；流式仍走 `/qa/stream`）
- Python 直连数据库或向量库
- 多 Agent 协作、长期记忆、工具动态注册
