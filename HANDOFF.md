# HANDOFF

> 最后更新：2026-11-13（**M1~M6 全部闭环，项目收官**：M6 云部署完成浏览器全流程验收 + swap 持久化 + 安全收尾）

## 项目标识
- 项目名称：「智汇」央国企智能知识中枢（由「基于 RAG 的智能知识库问答系统」升级改版而来）
- 项目目录：d:\idea个人项目\ai
- 当前阶段：**M1~M6 全部闭环**。前端（web/，Vue3+Vite）三页齐备实测通过；M6 阿里云全栈部署上线并完成公网浏览器验收
- 迁移总纲：d:\idea个人项目\ai\MIGRATION_PLAN.md（阶段编号与本文件不同，**里程碑以本文件为准**）
  - 本文件口径：M3 = 切片→向量→Milvus（已完成）；**M4 = RAG 问答**（检索 → Prompt → SSE → 引用）；M5 = Agent（Python LangGraph）；M6 = MQ 异步 / 微服务 / 部署
  - PLAN 口径：M3 = RAG 问答、M4 = Agent + 微服务。功能相同，只是编号错位，**以本文件为准**
- 规范：SDD（先 specs/ 定规格、contracts/ 定契约，再编码）
- 注意：PROGRESS.md 停留在 2026-10-14，内容已过期，**以本文件为准**

## 我的身份（协作模式，务必保持）
我是用户的「专属项目指导老师」：给方向、拆任务卡、讲原理、给引导线索、review 代码；**代码默认由用户自己敲（不代写）**；报错引导排查；每阶段结束总结"学到什么 + 面试怎么讲"。用户在自己 IDEA 里写代码，用 Apifox 测接口，一次一小步。
- 例外：用户明确说「你直接帮我改」「执行吧」「帮我提交」时可以代做；默认仍按上面的模式。

## git 基线（按时间）
- `d2da283` 初始化项目（JWT 认证 + Spring AI 对话 + 多格式解析）
- `77474bf` M1 Step2 文档模块 SDD 规格 + OpenAPI 契约
- `82ac75d` auth-rbac 鉴权规格定稿
- `b3312a9` / `ce86492` M1 Step3.1 收尾
- `9fb0916` M1 Step3.2 接入 RBAC 角色体系
- `2e616c0` M1 Step3.3.1 权限点表与实体
- `c06feb2` M1 Step3.3.2 开启方法级权限
- `f1009c8` 清理死代码
- `9041772` 补全 auth 与 document 契约 → **M1 闭环**
- `afe6761` M2 Step2-3 document 表/实体/分页插件 + 上传落库与状态流转
- `b7000d2` M2 Step4 文档列表/详情/按 id 逻辑删除
- `f11778c` M2 收尾 413/400 响应 → **M2 闭环**
- `a8e4a2b` M3 Step2 文本切片与 chunk 落库
- `251ecf6` M3 Step3 向量化契约与 Python 服务
- `646d62d` M3 Step4 向量写入 Milvus 与删除联动
- `0c73003` M3 Step4.2 接入 Zilliz Cloud（token 走环境变量，连接超时 15s）→ **M3 闭环**
  - M3 Step5（真实 bge-m3 冒烟 + 召回验证）只跑 `%TEMP%` 脚本，未改代码，无对应提交
- `673c410` M4 Step5 混合检索（MySQL ngram + RRF + Rerank）→ **M4 闭环**
- `c9c895b` M5 Step1~4 Agent 统一入口（规格契约 + Python LangGraph 图 + 工具联调 + Java 网关与降级，13/13 验收通过）→ **M5 闭环**
- `fd33a44` 前端 web/（Vue3+Vite）接入 /agent/chat 与 /qa/stream，浏览器实测全过
- 工作区现状：干净，未跟踪仅 `AGENTS.md`（**永不提交**）与 `src/test/test-upload.http`（本地测试产物，已移出版本库）

## 已完成（按里程碑）
### M1（闭环）
- Step 0：冻结基线、.gitignore、API Key 改环境变量
- Step 1：Boot 4.1.1→3.5.0、Spring AI 2.0→1.0.0、starter 改名、MyBatis-Plus 3.5.17
- Step 2：specs/ contracts/ 建立，document 与 auth-rbac 规格定稿
- Step 3.1：SecurityConfig（BCrypt、filterChain、STATELESS）+ AuthenticationEntryPoint 401 JSON
- Step 3.2：`role` + `user_role` 表、实体与 Mapper、CustomUserDetailsService（`ROLE_` + roleCode）、MeController
- Step 3.3.1：`permission` + `role_permission` 表与数据；authorities 同时装 `ROLE_xxx` 与 `perm_code`
- Step 3.3.2：`@EnableMethodSecurity` + `@PreAuthorize`；AccessDeniedException → 403
- Step 4：auth/document 契约补全（含 DELETE），Apifox 导入验证

### M2（闭环）
- Step 1：`specs/document/document-ingestion.md` 定稿（document 表、状态机、逻辑删、可见性）
- Step 2：`document` 表 + 实体 + Mapper + VO + 分页插件；补 `mybatis-plus-jsqlparser:3.5.17`
- Step 3：上传落库；三个 parser 改为抛异常；`savePending/markParsing/markSuccess/markFailed`；实测 4/4
- Step 4：`GET /documents`（列裁剪、deleted=0、ADMIN 全量/USER 仅自己）、`GET /documents/{id}`、`DELETE /documents/{id}`；新增 `PageResult<T>`、`BizException`；实测 12/12
- 收尾：413（`MaxUploadSizeExceededException`）、400（`MethodArgumentTypeMismatchException`）；实测 7/7

### M3（闭环）
- **Step 1 完成**：`specs/document/chunking.md` 定稿（切片规则、chunk 表、Milvus 契约、验收）；`chunk` 表已在 `ai_kb` 建好（11 字段 + idx_document_id / idx_status / uk_gauss）
  - 规格已修正：片长 250~500 浮动（边界回退），1200 字预期 **3~5 片**，原"必然 3 片"表述作废
- **Step 2 完成**：`TextSplitter`（滑动窗口 + 段落/句子边界回退 + 100 重叠）、`ChunkService`（切片落库 PENDING）、上传解析后独立 try/catch 调切片；算法验证 3 组用例通过；用户实测 111.txt → 3 片（387/495/339，全 PENDING，milvus_id NULL）
- **Step 3 完成（真实模型冒烟已通过）**：`contracts/vectorize.openapi.yaml`、Python FastAPI（`python/app/main.py`，httpx 调 SiliconFlow `/v1/embeddings`）、Java `VectorizeClient`（RestClient + 维度校验 + 分批 16）、DTO；本地 mock 上游 7 项测试全通过（200/dimension=1024/400/422/502）
- **Step 4.1 完成**：`specs/document/vector-store.md` 定稿；`MilvusService`（连接/collection/索引三层懒加载，AUTOINDEX + COSINE，6 字段，pk=`milvus_id`）；`ChunkService.vectorize`（16 片一批，失败只标该批）+ `logicalDeleteByDocument` + `markChunkSuccess/Failed`；`DocumentService.existsActive`；上传接向量化、删除改四段式；`pom` 加 `milvus-sdk-java:2.4.9`；properties 加 4 项 milvus 配置
  - **无需 Milvus 的 4 条验收全过**：编译；Milvus 离线启动（5.556s）；上传失败隔离（3 片全 FAILED + error_msg，接口 200/SUCCESS）；删除降级（document 与 3 片同秒 deleted=1）
- **Step 4.2 完成（2026-11-11，Zilliz Cloud 免费 Serverless · AWS eu-central-1）**
  - 连接：`uri=https://in03-ba85acd5e09110f.serverless.aws-eu-central-1.cloud.zilliz.com:443`；token 走环境变量 `MILVUS_TOKEN`，格式 `<集群用户名>:<密码>`（properties 里只留 `${MILVUS_TOKEN:}`）
  - 实测 5 条通过：collection 自动创建（6 字段与规格一致）；`milvus_id` 回写 `d10_c0/c1/c2`；云端 `count(*)=3` 与 MySQL 一致；按文档召回 top1 命中自身 score=1.000000；删除后云端 `count(*)=0` + MySQL `deleted=1`
  - 第 6 条「幂等 upsert」由确定性主键 `d{docId}_c{idx}` 保证，**留待有重试入口时实测**
  - 向量由本地 mock 服务生成（1024 维、同文本同向量），只验证链路，不代表语义质量
- **Step 5 完成（2026-11-11，真实模型 + 召回质量）**
  - 真实 bge-m3 冒烟：`POST /internal/vectorize` 返回 `model=BAAI/bge-m3`、`dimension=1024`；语义相近 0.7938 / 无关 0.36~0.40
  - 真实全链路：document id=13 → 3 片全 SUCCESS（`d13_c0/c1/c2`），片长 342/405/363 字符（`LENGTH()` 按字节算，1026/1215/1089）
  - 语义召回：3 个查询 top1 全中（相关 0.5544 / 0.5984，无关 0.31），**区分度 0.23**；召回用 Zilliz 直连脚本验证（脚本在 `%TEMP%`，非项目文件）

## 未完成 / 阻塞项
1. ~~真实 bge-m3 冒烟~~ **已解决（2026-11-11）**：`python/.env` 已配 `SILICONFLOW_API_KEY` + `MILVUS_TOKEN`；真实模型 `BAAI/bge-m3` 返回 1024 维，语义区分度正常
2. ~~Milvus 部署方式待拍板~~ **已解决（2026-11-11）**：选用 **Zilliz Cloud 免费 Serverless 实例**（AWS eu-central-1；Free 计划不开放新加坡区）。本机 Docker / WSL 仍未安装，不影响开发。要点：URI 必须带 `:443`；token 用 `<集群用户名>:<密码>`（控制台 "Download User and Password" 获取），或改用 API Key；`withConnectTimeout` 已从 3s 调到 15s 以容忍 Serverless 冷启动。
3. ~~Java `VectorizeClient` 还没接入流程~~ **已解决**：上传链路已接 `chunkService.vectorize`

## M4 计划（RAG 问答）
| Step | 内容 | 状态 |
|---|---|---|
| 1 | 检索服务：`POST /search`（query → bge-m3 → Milvus → 回表取原文 → 带来源的 TopK） | **已完成**（规格 `specs/qa/retrieval.md` + 契约 `contracts/search.openapi.yaml`；代码 6 处；实测通过，见下） |
| 2 | Prompt 拼接 + LLM 生成（非流式），回答附带引用片段 | **已完成**（规格 `specs/qa/rag-answer.md` + 契约 `contracts/qa.openapi.yaml`；实测通过） |
| 3 | SSE 流式输出（`Flux<ServerSentEvent>`，逐 token 推送） | **已完成**（规格 `specs/qa/rag-stream.md` + 契约 `contracts/qa-stream.openapi.yaml`；实测通过，见下） |
| 4 | 多轮会话（会话表 + 上下文裁剪） | **已完成**（规格 `specs/qa/multi-turn.md` + 契约 `contracts/qa-session.openapi.yaml`；`/qa/ask`、`/qa/stream` 增 `sessionId`；实测 19/19 通过） |
| 5 | 混合检索 + Rerank（改用 MySQL ngram 全文，不引入 ES） | **已完成**（规格 `specs/qa/hybrid-retrieval.md` + 契约 `contracts/search.openapi.yaml`；实测 13/13 通过） |

**M4 Step1 关键约束（从 M3 召回验证得出）**
- **禁止用绝对分数阈值过滤**：实测相关查询只有 0.55~0.60，无关 0.31，用 `score > 0.7` 会把正确答案全丢掉；必须用 **TopK 相对排序**
- 检索必须带权限过滤：`uploader_id` 或 `document_id in (可见集合)`，防止越权召回
- 回表校验 `document.deleted=0`，兜底 M3 遗留的孤儿向量
- 返回结构必须含 `documentId / chunkIndex / fileName / score / content`，供前端做引用溯源

**M4 Step1 已落地（2026-11-11，实测通过）**
- 改动：`MilvusService.search(vec, topK, expr)` + `Hit` 内部类；`dto/SearchRequest|SearchHitVO|SearchResponse`；`service/SearchService`；`SearchController`；properties 加 `search.*` 四项
- 实测（Trae 沙箱内 `spring-boot:run` + 真实 bge-m3 + Zilliz）：
  - test1（ADMIN）问「制度由哪个部门负责解释」→ 5 条命中，top1 `documentId=13` score **0.5251**（chunk 2，正文含「本说明由信息技术部负责解释」）；冷启动首次 5113ms，热态 **750~820ms**
  - test2（USER）同一 query → **0 条**（`uploader_id` 过滤生效）
  - 回表兜底：把 doc 12 临时置 `deleted=1` → 命中 5→4，doc 12 消失；已还原为 `deleted=0`
  - 边界：空串/超长/空 body → HTTP **400**；`topK=999`→20、`topK=0`→1
  - 日志佐证：`原始命中=9 有效命中=4`（9 条里 5 条是已删/无效文档的残留向量）
- 残留数据：doc 11、doc 12（`zhihui_step4_isolation.txt`，test1）在 Zilliz 有旧向量，稳定以 score≈0.02 出现，属正常；Step2 可把 `search.min-score` 设 0.2 过滤
- 上传类验收未在沙箱内跑（写 `D:/ai/uploads` 被拒），上传回归仍需在 IDEA 内验证

**M4 Step2 已落地（2026-11-11，实测通过）**
- 改动：`dto/QaRequest|QaResponse`；`service/QaService`（System Prompt 强约束 + 上下文按 `qa.max-context-chars` 截断 + 无命中短路不调模型）；`QaController`（`POST /qa/ask`，`chat:ask`）；properties 加 `qa.temperature=0.2`、`qa.max-context-chars=3000`
- 实测（真实 Qwen2.5-7B + bge-m3 + Zilliz）：
  - test1 问「制度由哪个部门负责解释」→ 6.4s，answer「制度由信息技术部负责解释…[1]」，citations=3（doc13 三片）
  - 无关问题「今天北京天气怎么样」→「资料中未提及北京天气情况。」，**无编号**（首版会硬标 [1][2][3]，已改 System Prompt 第 2 条）
  - test2 同一问题 → citations=0 + 固定话术，879ms，**未调用模型**
  - 空问题 → HTTP 400
- 已验证的机制：`citations` 下标 +1 = 回答中的 `[n]`；被上下文截断的片段不会出现在 citations 里，保证编号一致
- 已知瑕疵：模型标号可能与实际依据片段不完全对应（依据 chunk0 却标 [2]）；非流式 6~7s 等待明显

**M4 Step3 已落地（2026-11-11，实测通过）**
- 改动：`QaService` 拆出 `prepare()`（校验+检索+Prompt 组装）与 `streamAnswer()`（返回 `Flux<String>`）+ `QaPrompt` record；`QaController` 新增 `POST /qa/stream`，返回 `Flux<ServerSentEvent<String>>`，事件序列 `meta → delta… → done`，异常走 `error`；`application.properties` 加 `spring.mvc.async.request-timeout=120000`
- 关键修复：`JwtAuthenticationFilter` 覆盖 `shouldNotFilterAsyncDispatch()` 返回 `false`（原因见踩坑 47）
- 实测（真实 Qwen2.5-7B + bge-m3 + Zilliz，httpx 流式读取）：
  - 有命中：`meta` 首帧 → 首 `delta` 约 0.65s 后 → `done` 收尾，**连接正常结束**，客户端无 `RemoteProtocolError`
  - 无命中（test2）：`meta` 中 `citations=[]` → 固定话术 `delta` → `done`，**1463ms，未调模型**
  - 空问题：建流前失败，返回普通 **HTTP 400 JSON**（不是 SSE）
  - 重启后日志中 `AuthorizationDeniedException` / `response is already committed` 均为 **0 条**，无 ERROR
- 已验证机制：校验/检索失败仍在建流前抛出，可返回正常 JSON 错误；一旦开始推流，错误只能走 `error` 事件
- 未实测：模型调用中途失败时的 `error` 事件（需断网/换错 Key 触发）

**M4 Step4 已落地（2026-11-11，实测 19/19 通过）**
- 规格 `specs/qa/multi-turn.md`；契约新增 `contracts/qa-session.openapi.yaml`，`qa.openapi.yaml` / `qa-stream.openapi.yaml` 增 `sessionId`
- 表：`chat_session`（id/user_id/title/created_at/updated_at/deleted + idx_user）、`chat_message`（id/session_id/role/content/citations_json/created_at/deleted + idx_session），均在 `ai_kb` 建好
- 改动：`entity/ChatSession|ChatMessage`、两个 Mapper、`dto/SessionVO|MessageVO`、`service/ChatHistoryService`（建会话/属主校验/最近消息/追加/列表/详情/逻辑删）、`QaService`（`prepare` 注入历史 + 落 user 消息，`ask` 落 assistant）、`QaController`（meta 带 sessionId、`doOnComplete` 落 assistant）、新增 `SessionController`（GET /sessions、GET /sessions/{id}/messages、DELETE /sessions/{id}）、properties 加 `chat.history.max-rounds=3`、`chat.history.max-chars=1200`
- 实测（httpx 脚本在 `%TEMP%`，非项目文件）：
  - 无 `sessionId` → 200，自动建会话（title=问题前 30 字），回答带 citations
  - 同 `sessionId` 追问 → 200；消息表按 `user/assistant` 顺序落 4 条，assistant 的 `citations_json` 非空
  - 流式：`meta` 含 `sessionId`，delta 10 条 + done，结束后消息 +2（共 6 条）
  - 越权：test2 读消息 / 用 test1 会话提问 / 删 test1 会话 → 全部 **404**
  - 删除：200 → 详情 404 → 列表消失 → 重复删除 404
  - 轮数裁剪：第 4/5 轮日志 `历史消息=6条`（3 轮上限生效）
  - 字符裁剪：插入 6 条 500 字消息后提问，日志 `历史消息=2条`（1200 上限生效）
  - 回归：空问题 400、`/search` 200
- 模型调用失败（偶发 TLS `bad_record_mac`）时只落 user 消息、不落 assistant，符合设计；测试会话已全部逻辑删除

**M4 Step5 已落地（2026-11-11，实测 13/13 通过）**
- 方案：**轻量混合**（不引入 ES）—— MySQL 8 ngram 全文 + Milvus 向量双路召回 → RRF 融合 → 硅基流动 `BAAI/bge-reranker-v2-m3` 精排
- 规格 `specs/qa/hybrid-retrieval.md`；契约 `contracts/search.openapi.yaml` 增 `mode` / `reranked`，`score` 语义随模式变化
- 改动：`ChunkMapper.keywordSearch`（`MATCH ... AGAINST` + 权限/状态双重过滤）、`service/vector/RerankClient`（`/v1/rerank`，connect 3s / read 8s）、`SearchService` 重写（双路召回 + RRF + rerank + 降级）、`SearchRequest|SearchResponse` 加 `mode|reranked`、`SearchController` 透传 mode、properties 加 `search.mode=hybrid`、`rerank.*` 五项
- 数据库：`ALTER TABLE chunk ADD FULLTEXT INDEX ft_content (content) WITH PARSER ngram;`（**必须 ngram**，默认分词器切不了中文）
- RRF 公式 `score += 1/(60+rank)`（rank 从 1 起）；两路各取 `fetchN`：rerank 开启时 `max(max-candidates=20, topK)`，关闭时 `max(topK, topK*3)`，上限 50
- 实测（真实 bge-m3 + Zilliz + 真实 rerank）：
  - `mode=keyword` 问「制度由哪个部门负责解释」→ 命中 doc13 的 chunk 0/1/2，全文相关度降序；test2（USER）同问 → **0 条**（`d.uploader_id` 过滤生效）
  - `mode=vector` → 余弦分、不精排；`mode=hybrid` → `reranked=true`、无重复、分数降序、含关键词命中片段
  - rerank 日志：`Rerank 完成：候选=9 耗时=159~272ms`；检索日志 `mode=hybrid 向量=9 关键词=9 候选=9 返回=5 reranked=true`
  - 降级（另起 8081 实例把 `rerank.base-url` 指向 `http://127.0.0.1:9`）：`status=200 mode=hybrid reranked=false`，分数为纯 RRF 值 `0.0164/0.0161/0.0159`，日志 `Rerank 失败…Connection refused`，主流程不受影响
  - 非法 `mode` → HTTP 400；缺省不传 mode → 取 `search.mode`（hybrid）
  - 回归：`/qa/ask` 正常；`/qa/stream` delta 17 条 + done=true

## M5 进度（Python LangGraph Agent）
| Step | 内容 | 状态 |
|---|---|---|
| 1 | 规格 `specs/agent/agent-chat.md` + 契约 `contracts/agent.openapi.yaml`；`/qa/ask` 增 `persist` 字段（防双写） | **已完成** |
| 2 | Python 骨架 `python/app/agent/{state,tools,graph}.py` + `/internal/agent/chat`（route/tools/answer 三节点图，真实模型跑通） | **已完成** |
| 3 | 工具真实 HTTP 联调：`search_knowledge`→`/search`、`rag_answer`→`/qa/ask(persist=false)`，5 项用例通过 | **已完成** |
| 4 | Java 网关 `POST /agent/chat` + 会话落库 + Python 不可用降级到本地 `/qa/ask` | **已完成（2026-11-12，13/13 实测通过）** |

**M5 Step4 落地细节（2026-11-12）**
- 新增：`AgentController`、`service/AgentChatService`（会话/历史/落库/降级全在 Java，Python 只编排）、`dto/AgentChatRequest|AgentChatResponse|ToolUsage`；properties 加 `agent.base-url=http://127.0.0.1:8100`、`agent.timeout-ms=20000`、`agent.max-tool-calls=1`、`agent.enabled=true`
- 身份透传：Java 网关把 `Authorization` 原样传给 Python，Python 调工具时带回，权限判定只在 Java
- 实测（验收脚本 `%TEMP%\zhihui_m5_verify.ps1`，非项目文件，13/13）：
  - C1 知识问题 → `route=knowledge`、`toolsUsed=[search_knowledge]`、citations=5、回答带 `[3]`（**编号不固定，验收按 `\[\d+\]`**）
  - C2「你好」→ `route=chitchat`、无工具无引用；C3 同 sessionId 追问 → 消息成对追加（user/assistant ×2）
  - C4 `sessionId=999999` → 404；C5 杀掉 8100 → 200 + `degraded=true` + `route=fallback` + citations=5
  - C6 test2（USER）→ hitCount=0、固定话术；回归 `/qa/ask`、`/search`、`/qa/stream` 全过
- 已知数据问题：doc 11/12 与 doc13 内容重复（残留向量），不影响功能

## 前端（web/，Vue3 + Vite，2026-11-12 落地并浏览器实测通过）
- 位置：`web/`（`web/node_modules`、`web/dist` 已入 .gitignore）；启动 `cd web && npm.cmd install && npm.cmd run dev` → http://localhost:5173
- **环境坑**：本机 PowerShell 执行策略禁 `npm.ps1`，一律用 `npm.cmd`；Node v24.19.0 + Vite 5.4 可用；esbuild postinstall 被拦不影响运行
- 结构：`api.js`（fetch 封装：Result 信封拆解、401 跳登录、SSE 流式读取 `streamQa`）；`router.js`（token 路由守卫）；`LoginView.vue`；`ChatView.vue`（核心）
- ChatView 功能：会话列表（新建/切换/删除/发送后自动刷新标题排序）；**双模式切换**：Agent（`POST /agent/chat`）/ 流式 RAG（`POST /qa/stream`，fetch+ReadableStream 解析 SSE）
- Agent 结果展示：route 徽章（chitchat 绿「闲聊」/knowledge 蓝「知识检索」/fallback 橙「降级」）、toolsUsed 工具标签（命中数+耗时）、**degraded 黄色警告条**、总耗时；引用卡片 `[n] 文件名 · 片段i · score` 点击展开原文
- vite.config.js 开发代理 5173→8080（/auth /sessions /agent /qa /search /documents /charts），免跨域；生产部署需另配 Nginx 反代
- 浏览器实测（browser_use，2026-11-12）：登录 → Agent 知识问 → 蓝色徽章 + `search_knowledge · 5 命中` + 5 引用卡片；流式模式逐字输出 + 引用卡片；「你好」→ 绿色「闲聊」徽章无引用；新会话自动出现在列表顶部
- 未实测：degraded 警告条（需停 8100 后页面发问验证）、会话删除按钮、401 跳转（逻辑简单，风险低）

**前端增强（2026-11-12，方向 C，浏览器复测全过）**
- ① 文档管理页 `/docs`（DocumentsView.vue）：拖拽/点击上传（多文件顺序）、状态徽章（PENDING 黄/PARSING 蓝/SUCCESS 绿/FAILED 红 + errorMsg 悬停）、分页列表、删除确认；`uploadDocument` 单独走 multipart fetch（不走 JSON 的 request()）；后端上传是同步的，大文件需等解析+切片+向量化跑完
- ② 数据看板 `/dashboard`（DashboardView.vue）：6 张统计卡片 + 类型分布横向条形 + 近 7 天上传纵向柱状（纯 CSS，不引 echarts）；后端新增 `GET /charts/overview`（ChartController + ChartService + ChartOverviewVO/NameCountVO/DateCountVO），对 document/chunk/chat_session 实时聚合，ADMIN 全量 / USER 仅自己，近 7 天 Java 侧补零；规格 `specs/chart/dashboard-stats.md`、契约 `contracts/chart.openapi.yaml`
- 浏览器实测：看板 6 卡片真实数字（文档 10 / 切片 18 / 会话 19）+ 7 天趋势 7 个日期标签；文档页 10 行真实数据；两页无报错
- 遗留待实测：真实文件上传链路（拖 txt 看状态徽章流转）、test2 登录看板应全 0（USER 口径）

## M5 前置说明（Agent 用 Python，别在 Java 里硬写）
- 归属：**M5 的 Agent 全部放 Python**（LangGraph），Java 侧只做「业务 + 权限 + 数据」和「降级通道」
- 为什么 M4 的检索/问答在 Java：权限（`uploader_id`）、`document.deleted` 校验、JWT 身份都在 Java；且 RAG 是固定流水线，不是模型决策
- 为什么 `/search`、`/qa/ask` 做成 HTTP 接口而不是内部方法：M5 的 Python Agent 直接把它们注册成 tool 调用
- M5 交付形态：Python 新增 `/internal/agent/chat`（意图识别 → 计划分解 → 工具调用 → 自检重试），Java 保留 `/qa/ask` 作为非流式降级（Python 挂/超时就回落）
- 边界铁律：Python 不直连 MySQL、不碰文件系统，只通过 Java 的 HTTP 接口拿数据和权限上下文

## M3 关键设计（已实现，M4 复用）
- `milvus_id` 确定性规则 `d{documentId}_c{chunkIndex}`（幂等 upsert）
- 每批 16 片：embedding → upsert → 回写 `chunk.SUCCESS` + `milvus_id`；单批失败只标该批 FAILED，不中断
- 向量化失败**不回写** `document.status`（document 的 SUCCESS 只代表解析成功）
- 检索过滤必须带 `document_id in (...)`，防止跨文档串味
- Milvus **三层懒加载**：建连接 → 建 collection → 建索引 + load（缺一层就会启动即崩，见坑 39）

## 数据库 / 数据现状（2026-11-11）
- 账号：test1 / 123456（user id=4，ADMIN）；test2 / 123456（user id=5，USER）
- `document` 表：原有 id 1~3（test1，id=3 为坏 pdf FAILED）、id=4（test2 种子，已逻辑删）；M3 测试新增若干（含 111.txt，SUCCESS）
- `chunk` 表：已建；已有 Step2 测试切片（如 111.txt 对应文档 3 片，PENDING）；M4 Step5 新增全文索引 **`ft_content`（`FULLTEXT(content) WITH PARSER ngram`）**，关键词召回依赖它
- `chat_session` / `chat_message`：M4 Step4 新建；实测产生的会话（id 1~6）已全部逻辑删 `deleted=1`，表可作为空表使用
- `charts` 表：**经核实不存在**（2026-11-12 用 information_schema 全连接排查无果，旧项目遗留信息有误）；首页看板改为对 document/chunk/chat_session 实时聚合，见前端章节
- M3 Step4.1 测试数据：document id=8（`zhihui_step4_isolation.txt`，3 片 346/409/367，全 FAILED，已逻辑删 deleted=1）
- M3 Step4.2 测试数据：document id=9（token 前缀重复导致认证失败，3 片 FAILED，未删）；id=10（同文件重传，3 片 SUCCESS，`milvus_id=d10_c0/c1/c2`，已逻辑删；云端已归零）
- **M3 Step5 测试数据（有效数据，保留）**：document id=13（`zhihui_step4_isolation.txt`，《企业知识管理制度说明》六章，SUCCESS；3 片 SUCCESS `d13_c0/c1/c2`，342/405/363 字符）。Zilliz `zhihui_chunk` 现有 3 条实体，均属该文档，可直接用于 M4 检索联调
- 历史现象：id 1~3 的 `file_path` 指向 `C:\Users\...\Temp\zhihui-uploads\`，与 `upload.dir=D:/ai/uploads` 不一致（上轮临时改过，不影响功能）
- 从 Trae 沙箱启动的 Java 进程写 `D:\ai\uploads` 会"拒绝访问"，**上传类验收必须在 IDEA 里跑**；Python 服务可在 Trae 里跑

## 规则与约定
- Controller 放根包 `com.xiaohu.ai`；解析器放 `service.parser`；切片放 `service.chunk`；向量客户端放 `service.vector`
- 上传目录 `D:/ai/uploads`（纯英文路径）
- SDD：每模块先规格 + 契约再编码；契约改了要同步 Apifox
- git：每小步验证通过后提交；`AGENTS.md` 不进提交；密钥入环境变量；测试产物不进仓库
- `hasRole("ADMIN")` 比对 `ROLE_ADMIN`；`hasAuthority("doc:delete")` 比对裸串 —— authorities 两套都要装
- 逻辑删不用 MP `@TableLogic`，查询显式 `deleted=0`
- 切片参数 `chunk.size=500`、`chunk.overlap=100`；向量化 `vectorize.base-url=http://localhost:8000`、`batch-size=16`、`expected-dimension=1024`

## 踩坑记录（新增重点）
26. **删方法体别忘删注解**：孤立的 `@DeleteMapping` / `@PreAuthorize` 会挂到下一个方法上（曾致 `GET /documents` 对 USER 直接 403）
27. **`new PageResult<>(a,b,c,d,e)` 报 `cannot be applied`** = 缺全参构造器；补后隐式无参构造器消失（出参无需反序列化，无影响）
28. 只有 `BizException` 能让 404 变真 HTTP 404；`RuntimeException` 兜底仍 200 + code:500
29. 413/400 异常都继承 `RuntimeException`，**必须单独 handler**
30. 判断管理员用 authorities 中的 `ROLE_ADMIN`，不要用 `ADMIN`
31. 上传超限日志出现 `Failed to parse multipart servlet request` 属正常（Tomcat 断开超限请求体）
32. PowerShell 不支持 bash heredoc；多行提交信息用多个 `-m`
33. Trae 里 `git status` 偶发 `.git/index.lock` 被回收站事件干扰，重试即可
34. **Apifox 报 `Invalid protocol: ttp:`** = URL 少了 `h`（填成 `ttp://...`）；检查地址栏与"环境"Base URL，必要时关代理
35. **Navicat 报 `1064 near '<id>'`** = `<id>` 是占位符，必须替换成真实数字；查最新 id 用 `SELECT id ... ORDER BY id DESC LIMIT 3`
36. **Navicat 报 `1046 No database selected`** = 查询窗口没绑库；首行加 `USE ai_kb;`
37. **FastAPI + Pydantic**：字段缺失返回 **422**，不属于 400；契约里已声明 422。若想让空/超限返回 400，需去掉 `min_length/max_length` 手动校验（当前实现即如此）
38. **切片片长不可用 `len/500` 估算**：边界回退使片长 250~500，重叠 100，片数比估算多
39. **`new MilvusServiceClient(...)` 在构造器里就建连接**：Milvus 不在线时会阻塞约 10 秒后抛 `RuntimeException`，Bean 创建失败 → 整个 Spring 容器启动崩溃（`documentController → chunkService → milvusService` 链）。"懒初始化"必须**连 client 创建一起推迟**，并设 `withConnectTimeout(3, TimeUnit.SECONDS)` 快速失败。任何"启动时连外部服务"的组件都适用
40. **`patch.setErrorMsg(null)` 在 MP `updateById` 下不生效**：默认 NOT_NULL 策略跳过 null 字段，清空历史错误须用 `LambdaUpdateWrapper.set(Chunk::getErrorMsg, null)`。当前无重试入口，暂无影响
41. **properties 粘贴时把 key 一起粘进 value**：写成 `milvus.token=milvus.token=db_xxx:pwd`，导致 token 变成 `milvus.token=db_xxx`，Zilliz 返回 `UNAUTHENTICATED: Token not found or unauthorized`。**认证失败先看长度和冒号位置**：`db_ba85acd5e09110f` 固定 18 字符，第一个冒号必须在第 19 位
42. **`get_collection_stats` 的 `row_count` 有滞后**：刚 upsert 的数据还在 growing segment，统计仍为 0，但 `query` 能查到。核对实体数用 `query(filter="", output_fields=["count(*)"])`，不要信 row_count
43. **Zilliz Cloud Serverless 连接三要素**：URI 必须带 `:443`（不加会按 19530 连本地）；token 传 `<用户名>:<密码>` 字符串；冷启动可能超过 3 秒，`withConnectTimeout` 用 15 秒。另外 Serverless 的 `delete` 支持按 filter 删（`document_id == X`），行为与本地 standalone 一致
44. **禁止用绝对分数阈值过滤召回结果**：真实 bge-m3 + COSINE 下，相关片段只有 **0.55~0.60**，无关片段 **0.31** 左右。写 `score > 0.7` 会把所有正确答案丢掉。检索只能做 **TopK 相对排序**，阈值最多用于「明显无关」兜底（如 < 0.25），且要可配置
45. **切片 overlap 起点未对齐句子边界**：第三片可能以「围与关键词」这种半截词开头（前一片尾部倒切 100 字导致）。不影响召回，但影响 Prompt 可读性。`TextSplitter` 优化方向：overlap 起点向前回退到最近的句号/换行
46. **Windows 上 `%TEMP%` 脚本读不到 Spring 的 `${MILVUS_TOKEN:}` 字面量**：Spring 只在自身进程内解析占位符，外部脚本读到的是 `${MILVUS_TOKEN:}` 原样字符串，必然 `UNAUTHENTICATED`。外部脚本要自己读 `python/.env`
47. **SSE/异步请求收尾被 `AuthorizationDeniedException` 掐断**：Spring Security 6.1+ 的 `AuthorizationFilter` 默认在**所有派发类型**（含 ASYNC）上重新授权。异步请求结束时容器会再做一次 ASYNC 派发，而 `OncePerRequestFilter.shouldNotFilterAsyncDispatch()` 默认 `true`，JWT 过滤器跳过了这次派发 → 安全上下文为空 → `/qa/stream` 被拒；此时响应已提交，`ExceptionTranslationFilter` 只能报 `Unable to handle the Spring Security Exception because the response is already committed` 并断开连接，客户端表现为 `incomplete chunked read` / curl exit 18。**修复：JWT 过滤器覆盖 `shouldNotFilterAsyncDispatch()` 返回 `false`**，让 ASYNC 派发也重新认证（header 仍在，可正常解析）
48. **硅基流动偶发 TLS `bad_record_mac`**：`/qa/ask` 首次调用报 `I/O error ... (bad_record_mac) Received fatal alert`，同请求重试即成功。属上游/链路抖动，不是代码问题；此时 user 消息已落库、assistant 未落库（符合 Step4 设计）。连续失败先重试，再查网络/代理
49. **流式落库放在 `doOnComplete` 而不是 `onErrorResume` 之后**：`doOnComplete` 在错误信号下不触发，天然满足「出错不存 assistant」；若放到 `onErrorResume` 下游会被 error 事件误判为正常结束
50. **RRF 融合不能叠加原始分**：首版 `addRrf` 用 `new Candidate(hit)` 初始化，score 继承了余弦分（0.5478），再累加 `1/(60+rank)`，结果 hybrid 分数看着像余弦分、排序也不对。**融合分必须从 0 起算**，只能用 RRF 增量；排查方法看日志 `reranked=false` 时的 score 是否约 0.016（=1/61）
51. **MySQL 中文全文必须 `WITH PARSER ngram`**：默认分词器按空格切词，中文整段被当成一个 token，`MATCH ... AGAINST` 几乎恒为 0。建索引语句 `ALTER TABLE chunk ADD FULLTEXT INDEX ft_content (content) WITH PARSER ngram;`，2-gram 对短查询（如「制度由哪个部门负责解释」）效果够用
52. **两路召回的权限/状态过滤必须一致**：向量侧靠 Milvus `expr`（`uploader_id == X`）+ 回表校验，关键词侧靠 SQL `JOIN document` 的 `uploader_id/deleted/status`。少任何一侧都会越权或召回脏片；实测 test2 在 keyword 模式必须返回 0 条才算对
53. **`spring-boot:run` 显示 `Nothing to compile` 不代表代码已生效**：若编辑发生在进程启动之后，跑的还是旧 class。改完先 `.\mvnw.cmd -q -DskipTests compile`，再杀端口进程重启；`Get-NetTCPConnection -LocalPort 8080 -State Listen | % { Stop-Process -Id $_.OwningProcess -Force }`
54. **硅基流动模型会下线**：`Qwen/Qwen2-7B-Instruct` 已 403 Model disabled，对话/编排模型统一 `Qwen/Qwen2.5-7B-Instruct`（properties 与 python/.env 都要一致）
55. **Agent 调内部问答必须 `persist=false`**：Java 网关已落 user 消息，工具再落一次就双写；`QaService.ask(...,persist)` 六参重载即为此而生
56. **Java 读超时与 Python 图超时要对齐**：Python 图 20s 自掐，Java `agent.timeout-ms=20000` 稍晚收尾降级；Python 4xx 属契约缺陷直接 500（不静默降级），只有连接/超时/5xx 才降级
57. **Trae 内置终端被安全软件按「利用PowerShell执行可疑脚本」拦截**：一切命令 exit -1 且无输出（进程根本没起来）。在安全软件防护日志放行，或直接用外部 PowerShell（Win+R → powershell）跑命令
58. **PS 5.1 把 UTF-8 无 BOM 的 .ps1 按 GBK 读**：中文会吞掉后面的 `{`/`"`，报 `MissingEndCurlyBrace`。脚本保持纯 ASCII（中文用 Unicode 码点构造）；另注意 `/hello` 需 `?name=` 参数，不能裸 GET 做健康探测
59. **Vite 代理缺路径时前端拿到的是 index.html**：fetch 未匹配 proxy 的 API 路径不会 404，而是命中 SPA 回退返回 HTML，`resp.json()` 解析失败报 `HTTP 200`（状态码正常、数据全空）。现象是"页面渲染正常但数据全空/暂无数据"，排查：先直接打后端端口验证接口本身，再对照 vite.config.js 的 proxy 表
60. **前端路由路径不能与后端 API 路径同名**：前端 `/documents` 页面路由 vs 后端 `GET /documents` 列表接口，一旦配同名代理，浏览器刷新该页面会把导航请求也代理到后端，拿到 JSON 而不是页面。前端路由改用 `/docs` 避开
61. **PowerShell `>` 重定向 mysqldump 会毁中文**：PS 5.1 把进程 stdout 的 UTF-8 字节按 GBK 解码再写文件，中文全变「鏂囨。」式乱码。正确做法：`mysqldump --result-file=...`，让 mysqldump 自己写文件
62. **get.docker.com 国内不可直连**：`SSL_connect: Connection reset by peer`。放弃一键脚本，改阿里云镜像 apt 安装 docker-ce
63. **mirrors.cloud.aliyuncs.com（VPC 内网镜像）会撞同步窗口**：`File has unexpected size ... Mirror sync in progress`。`sed` 换成公网 `mirrors.aliyun.com` 即可
64. **裸 IP 用浏览器访问要注意 https 自动升级**：新版浏览器对裸输的 IP 会先尝试 https（443 无证书 →"页面打不开"），但 curl 走 http 完全正常。排查先分清"浏览器打不开"还是"服务不通"；另外观察到 `GET /` 返回 307 → `/index.html`（Server: nginx/1.27.5，配置里并无重定向），链路最终 200 正常，**原因待查**
65. **网站突然全不通先查实例状态，再查安全组端口**：2026-11-13 网站无法访问，排查路径：本机 curl 80/22 均超时 → 控制台实例「运行中」→ 22 恢复通但 80 不通 → 服务器内 `curl 127.0.0.1` 返回 200 + `ss -tlnp` 确认 0.0.0.0:80 监听 → 锁定安全组。根因：**安全组入方向只有 SSH(22)/RDP(3389)/ICMP，没有 80 规则**（此前能访问说明规则曾被删或未加成功）。修复：手动添加 TCP 80/80、源 0.0.0.0/0，立即生效。教训：①22 通 80 不通 = 端口级拦截，直奔安全组；②ECS 意外重启后 Docker 容器靠 compose 重启策略自动拉起，服务本身无需干预；③新建安全组规则时「访问来源」填 IP（0.0.0.0/0），「访问目的」才填端口（80/80），填串会报 IPv4 格式错误

## 环境要点
- Spring Boot 3.5.0；Spring AI 1.0.0；MyBatis-Plus `mybatis-plus-spring-boot3-starter:3.5.17`；MySQL 8.0.40；jjwt 0.12.6；pdfbox 3.0.4；poi-ooxml 5.5.1；Java 17
- 大模型：硅基流动 base-url `https://api.siliconflow.cn`（对话时无 /v1）；对话模型 `Qwen/Qwen2.5-7B-Instruct`（旧的 `Qwen/Qwen2-7B-Instruct` 已被硅基流动下线）；Embedding `BAAI/bge-m3`（1024 维）
- Python：3.12；虚拟环境 `python/.venv`（fastapi/uvicorn/httpx/pymilvus 已装，.venv 已 gitignore）
- **向量库：Zilliz Cloud 免费 Serverless（AWS eu-central-1）**，collection `zhihui_chunk`（AUTOINDEX + COSINE，6 字段，pk=`milvus_id` VarChar(64)）。连接信息在 properties 的 `milvus.*`；**token 只放环境变量 `MILVUS_TOKEN`**（IDEA Run Configuration → Environment variables，格式 `db_ba85acd5e09110f:<密码>`，多变量用 `;` 分隔）。控制台：https://cloud.zilliz.com
- 本地 mock 向量化服务（仅测试用，非项目文件）：`python/.venv/Scripts/python.exe %TEMP%\zhihui_mock_embed.py`，监听 8000，返回 1024 维确定性归一化向量；**与真实 Python 服务互斥，同一端口**
- Python 服务启动：`cd python; .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000`（**推荐**：凭据已写入 `python/.env`，服务启动时自动加载，无需每次设环境变量）
- `python/.env`（已 gitignore）当前含两项：`SILICONFLOW_API_KEY=sk-...`、`MILVUS_TOKEN=db_ba85acd5e09110f:<密码>`；外部脚本（pymilvus 验证等）也从这个文件读
- IDEA 侧仍需在 Run Configuration → Environment variables 配 `SILICONFLOW_API_KEY` 与 `MILVUS_TOKEN`（Java 进程不读 python/.env）；多变量用 `;` 分隔，**务必写成 `KEY=value`**
- ⚠️ 待办：`SILICONFLOW_API_KEY` 曾在 properties 中明文存在过，属于已泄露，**应到硅基流动控制台重新生成并替换**
- MySQL 库 `ai_kb`，服务名 MySQL80（root/123456），CLI `D:\MySQL\MySQL Server 8.0\bin\mysql.exe`
- JWT：`jwt.expiration=86400000`（24h）
- 命令行构建 `.\mvnw.cmd -DskipTests compile`；IDEA 内 Ctrl+F9
- 测试账号 test1（ADMIN）/ test2（USER），密码均 123456
- 简历口述按实际版本与供应商说（Spring Boot 3.5.0 + 硅基流动），勿写 Spring Boot 3 / 通义千问 / Spring AI Alibaba

## 接续口令（新会话第一句，粘贴即可）
「继续 d:\idea个人项目\ai 的《智汇》项目。请先读 HANDOFF.md、MIGRATION_PLAN.md、specs/ 与记忆里的 project_memory，然后：(1) 复述项目背景、已完成内容、当前阶段、规则约定；(2) 列出接下来优先推进的任务与注意事项；(3) 继续以我的专属项目指导老师身份推进，代码默认我自己敲。当前 **M1~M6 全部闭环，项目已收官**（阿里云 ECS 47.114.49.129 全栈上线，浏览器验收通过，安全收尾完成）。后续可选方向：MQ 异步 / 微服务拆分（简历加分项）、切片 overlap 边界对齐优化（坑 45）、或开启新项目。」

## M4 Step1 开工前的既有资产（避免重复造轮子）
- `VectorizeClient.embed(List<String>)` —— Java 侧已有，可直接拿来把 query 转成 1024 维向量
- `MilvusService.searchInDocument(...)` —— 目前只在「文档内自检索」验收时用过，Step1 需要新增跨文档 `search(vector, topK, filter)` 方法（同文件，复用 `ensureReady()`）
- `chunk` 表已有 `document_id / chunk_index / content / milvus_id / status / deleted`
- `document` 表已有 `file_name / uploader_id / deleted / status`
- 可直接用于联调的数据：document id=13，3 条向量在 Zilliz 云端

## M6 · 云部署（**已完成**，2026-11-13 收官）

**目标**：单机阿里云全栈部署，本机零负担；不买域名不备案，公网 IP 直连。

**规格与文件（已提交 `7209f80`）**
- 规格 `specs/deploy/cloud-deploy.md`；根 `Dockerfile`（Java 多阶段构建）、`python/Dockerfile`、`web/Dockerfile` + `web/nginx.conf`（静态托管 + API 反代 + SSE 关缓冲）、`docker-compose.yml`、`deploy/.env.example`
- `application.properties` 6 处改 `${VAR:默认值}` 占位（DB 地址/账号/密码、JWT secret、vectorize/agent 地址），本地开发默认值不变
- `deploy/mysql/init.sql`：本机 ai_kb 全量导出（**必须用 `mysqldump --result-file`**，见坑 61），compose mysql 首启自动灌入

**云资源**
- 阿里云 ECS **免费试用**（个人版 300 元额度，约可跑 1181 小时）：**2核4G 经济型 e（ecs.e-c1m2.large）/ Ubuntu 22.04 / 华东1（杭州）**，实例 `i-bp19tnh5gpimgect3kn1`
- 公网 = **弹性公网 IP（EIP）`47.114.49.129`**（按使用流量；实例开通时未分配公网 IP，后绑的）
- 安全组入方向已放行 22、80
- root 密码：用户自设（控制台「重置实例密码」+ 重启后，SSH 密码登录才可用）

**服务器状态（代码在 `/opt/zhihui`，main 分支 clone）**
- Docker CE 29.9.3 + compose v5.7.1：apt 手动装，源 `mirrors.aliyun.com/docker-ce`（坑 62/63）
- 已加 2G swap（`fallocate -l 2G /swapfile` + `mkswap` + `swapon`）；**未写 /etc/fstab，重启即丢，待办**
- `.env` 已配 4 项：MYSQL_ROOT_PASSWORD、JWT_SECRET（新生成 64 位随机串，**与本地不同**）、SILICONFLOW_API_KEY、MILVUS_TOKEN（同本地 python/.env）。**真实值只存服务器 `/opt/zhihui/.env`，不写进本文档（本文档会提交到 GitHub）**
- `docker compose up -d --build` 完成：5 容器（mysql/java/nginx/python-vectorize/python-agent）全 Up，mysql healthy；首次构建约 60 分钟（maven 拉依赖 44 分钟，层已缓存，二次构建快）
- 后端启动正常：`Started AiApplication in 13.829 seconds`

**验收进度**
- ✅ `docker compose ps` 全 Up；`Started AiApplication`；`curl POST /auth/login` 返回 code 200（后端+DB+Nginx 链路通）
- ✅ 本机 `curl http://47.114.49.129` 拿到前端 HTML（标题「智汇 · 智能知识中枢」）
- ⚠️ 浏览器登录页曾打开但报 401（curl 证明后端正常，高度疑密码输入问题/中文输入法）；之后用户反馈「页面打不开」（疑浏览器 https 自动升级，见坑 64），curl 一切正常
- ✅ 2026-11-13 排障恢复：网站全不通，根因为**安全组缺 80 端口入方向规则**（实例曾意外重启，Docker 容器自动拉起无影响），手动添加 TCP 80/80 + 0.0.0.0/0 后外部访问恢复（HTTP 200），详见坑 65
- ✅ 2026-11-13 浏览器全流程验收通过：登录（test1）→ Agent 知识问答（徽章 + 引用卡片）→ 流式 RAG（SSE 逐字输出）→ 闲聊（无引用）→ 文档管理页 → 数据看板
- ✅ 2026-11-13 收尾完成：swap 已写入 `/etc/fstab` 持久化；GitHub 仓库已改回 private；`ghp_` PAT 已撤销
- **M6 正式闭环，项目收官**

**运维速查**
- 连服务器：本机 PowerShell `ssh root@47.114.49.129`（密码盲打不显示字符）
- 看状态 `docker compose ps`；看日志 `docker compose logs --tail=50 java`；重启 `docker compose restart`
- 更新代码：服务器上 `cd /opt/zhihui && git pull && docker compose up -d --build`（有缓存，很快）
- 不用时控制台「停机不收费」或「释放」，避免烧免费额度
