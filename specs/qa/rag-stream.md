# RAG 流式问答规格（SSE）

> 承接：`specs/qa/rag-answer.md`（Step2 非流式）
> 阶段：M4 Step3。只改「输出通道」，Prompt 与检索逻辑完全复用。

## 1. 目标

把非流式的「等 6~7 秒一次性返回」改成**首字尽快、逐 token 推送**，降低感知延迟；引用片段在流开始前先推送，前端可先渲染来源。

## 2. 范围

### 做

- `POST /qa/stream`：返回 `text/event-stream`
- 事件：`meta`（引用）、`delta`（增量文本）、`done`（结束）、`error`（异常）
- 无命中：不调模型，推 `meta`（空）+ `delta`（固定话术）+ `done`
- 超时/客户端断开时释放模型流

### 不做

- 多轮会话（Step4）
- 断线续传、`Last-Event-ID`
- 前端页面（本项目暂无前端）

## 3. 接口

契约见 `contracts/qa-stream.openapi.yaml`。请求体与 `POST /qa/ask` 完全相同。

```
POST /qa/stream        权限：chat:ask      响应：text/event-stream
{ "question": "...", "topK": 5 }
```

事件序列（正常）：

```
event: meta
data: {"citations":[{...}]}          ← 先给来源，前端可立即渲染

event: delta
data: 制度由

event: delta
data: 信息技术部

...

event: done
data: [DONE]
```

事件序列（无命中）：

```
event: meta
data: {"citations":[]}

event: delta
data: 知识库中未找到与该问题相关的内容。请换一种问法，或先上传相关资料后再试。

event: done
data: [DONE]
```

异常：

```
event: error
data: {"message":"模型调用失败：..."}
```

约束：

- `citations` 下标 + 1 仍等于回答中的 `[n]`
- 一旦开始推送就无法再改 HTTP 状态码，错误只能走 `error` 事件
- 参数校验 / 检索失败发生在建流之前，仍是普通 JSON 错误（400 / 502 / 503）
- 超时由 `qa.sse-timeout-ms`（默认 120000）控制；客户端断开立即取消模型订阅
- **前端必须用 `fetch` + `ReadableStream` 消费**：浏览器原生 `EventSource` 只支持 GET，不支持 POST 带 body

## 4. 实现要点

- `QaService.prepare()`：校验 + 检索 + 组装 Prompt，返回 `(citations, userPrompt, empty)`
- `QaService.streamAnswer(prompt)`：`chatClient.prompt().stream().content()` → `Flux<String>`
- `QaController`：映射成 `Flux<ServerSentEvent<String>>`（meta → delta… → done）
- 连接关闭由框架处理；不需要手动 `subscribe` / `dispose`，客户端断开即取消上游
- 引用列表由 `ObjectMapper` 序列化成字符串再 `data(...)`，避免 SSE 对对象做消息转换

## 5. 配置项

```properties
spring.mvc.async.request-timeout=120000
```

> 流式返回用 `Flux<ServerSentEvent<String>>`（Spring MVC 响应式返回值），收尾与 chunked 终止由框架负责；
> 超时用 MVC 异步超时控制，不再自建 `SseEmitter`。

## 6. 验收标准

- [ ] `curl -N` 能看到 `meta` 先到、`delta` 多条逐步到、最后 `done`
- [ ] 首个 `delta` 到达时间明显早于非流式的 6~7 秒（目标 1~2 秒内）
- [ ] `citations` 数量与 `POST /qa/ask` 同 query 一致
- [ ] 无关问题 → `meta` 空 + 固定话术一条 `delta` + `done`，且不调模型
- [ ] test2 同一问题 → `citations: []` + 固定话术
- [ ] 空问题 / 空 body → HTTP 400（普通 JSON，不是 SSE）
- [ ] 模型不可用 → 收到 `error` 事件，连接正常关闭
- [ ] 中途关闭 curl → 服务端日志无异常堆积，模型流被取消
- [ ] 回归：`POST /qa/ask`、`POST /search` 不受影响

## 7. 风险与待定

| 项 | 说明 | 处置 |
|---|---|---|
| 反向代理缓冲 | Nginx 默认缓冲会让 SSE 变成"一次性到达" | 后续部署时加 `proxy_buffering off` + `X-Accel-Buffering: no` |
| 首 token 延迟 | 检索 750ms + 模型首 token，合计约 1.5~2s | 可先推 `meta` 让前端出骨架；M6 再优化检索耗时 |
| 断线丢失 | 无 `Last-Event-ID`，重连必须重问 | Step4 存会话后再考虑补 |
| 连接数 | 每问占用一个异步请求线程 | Tomcat 默认够用；生产要限流（M6） |
