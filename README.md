# Spring AI 智能对话问答系统

基于 Spring Boot 3 + Spring AI 的后端服务，集成大模型对话能力，包含用户注册登录、JWT 鉴权、多格式文档解析等模块。大模型通过 OpenAI 兼容协议接入硅基流动（SiliconFlow）托管的 Qwen 开源模型，无需本地部署模型。

## 技术栈

| 分类 | 技术 | 版本 |
| --- | --- | --- |
| 基础框架 | Spring Boot | 3.5.0 |
| AI 框架 | Spring AI（spring-ai-starter-model-openai） | 1.0.0 |
| 大模型 | Qwen2.5-7B-Instruct（硅基流动，OpenAI 兼容接口） | - |
| ORM | MyBatis-Plus（spring-boot3-starter） | 3.5.17 |
| 数据库 | MySQL | 8.x |
| 安全 | BCrypt 密码加密（spring-security-crypto）、JJWT | 0.12.6 |
| 参数校验 | spring-boot-starter-validation | - |
| 文档解析 | Apache PDFBox（PDF）、Apache POI（Word） | 3.0.4 / 5.5.1 |
| 构建 / 运行环境 | Maven、JDK | 17 |

## 已实现功能

- **用户注册与登录**：密码使用 BCrypt 加盐哈希存储，登录成功签发 JWT。
- **JWT 鉴权拦截**：自定义拦截器统一校验 `Authorization: Bearer <token>`，保护对话接口，未登录返回 401。
- **AI 对话**：基于 Spring AI 的 `ChatClient`，通过 OpenAI 兼容接口调用 Qwen 大模型完成问答。
- **文档上传与解析**：上传 txt / pdf / docx 文件并落盘；采用**策略模式**按文件类型分发到不同解析器，解析出纯文本。
- **统一规范**：统一返回体 `Result<T>`、全局异常处理、`@Valid` 注册参数校验。

## 项目结构

```
src/main/java/com/xiaohu/ai
├── AiApplication.java          # 启动类
├── HelloController.java        # 健康检查 /hello
├── AuthController.java         # 注册 /auth/register、登录 /auth/login
├── ChatController.java         # AI 对话 /chat（需登录）
├── DocumentController.java     # 文件上传 /documents/upload
├── common/                     # Result 统一返回、GlobalExceptionHandler
├── config/                     # SecurityConfig、WebConfig、JwtInterceptor
├── dto/                        # RegisterRequest / LoginRequest（含参数校验）
├── entity/  mapper/            # User 实体、UserMapper
├── service/
│   ├── UserService.java        # 注册/登录业务逻辑
│   ├── DocumentService.java    # 按策略分发解析器
│   └── parser/                 # DocumentParser 接口 + Txt/Pdf/Docx 实现（策略模式）
└── util/
    └── JwtUtil.java            # JWT 生成与解析
```

## 快速开始

### 1. 准备环境

- JDK 17、Maven 3.6.3+、MySQL 8.x
- 注册硅基流动账号并在「API 密钥」中创建密钥（`sk-` 开头）：https://cloud.siliconflow.cn

### 2. 初始化数据库

```sql
CREATE DATABASE IF NOT EXISTS ai_kb DEFAULT CHARACTER SET utf8mb4;
USE ai_kb;

CREATE TABLE user (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  username    VARCHAR(50)  NOT NULL UNIQUE COMMENT '用户名',
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密后的密码',
  nickname    VARCHAR(50)  COMMENT '昵称',
  create_time DATETIME     COMMENT '创建时间'
);
```

### 3. 配置 API Key（不要把密钥写进代码）

将 `src/main/resources/application.example.properties` 复制为 `application.properties`，并在 IDEA「运行/调试配置」→「环境变量」中添加：

```
SILICONFLOW_API_KEY=你的sk密钥
```

数据库账号密码、上传目录等在 `application.properties` 中按本地环境调整。

### 4. 启动

运行 `AiApplication`，看到 `Started AiApplication` 即成功，服务默认端口 `8080`。

## 接口说明

| 方法 | 路径 | 是否需要登录 | 说明 |
| --- | --- | --- | --- |
| GET | `/hello?name=xx` | 否 | 健康检查 |
| POST | `/auth/register` | 否 | 注册，body：`{username, password, nickname}` |
| POST | `/auth/login` | 否 | 登录，body：`{username, password}`，返回 JWT |
| GET | `/chat?message=你好` | 是 | AI 对话，请求头携带 `Authorization: Bearer <token>` |
| POST | `/documents/upload` | 否 | 上传文件，表单字段 `file`（支持 txt/pdf/docx） |

调用示例（登录后对话）：

```bash
# 登录
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}'

# 对话（把 <token> 换成登录返回的 data）
curl "http://localhost:8080/chat?message=你好" \
  -H "Authorization: Bearer <token>"
```

## 后续规划

当前已完成基础对话、用户体系与文档解析，后续将演进为基于 RAG 的智能知识库：

- 文档切片（Chunking）与 Embedding 向量化；
- 向量数据库存储与语义检索（Top-K）；
- 检索结果注入 Prompt，实现基于私有文档的问答；
- 对话接口 SSE 流式输出。

## 安全说明

- API Key 通过环境变量 `SILICONFLOW_API_KEY` 注入，配置文件中仅保留占位符，不提交真实密钥。
- 本地 `application-local.properties`、`.idea/`、`target/`、`uploads/` 均已通过 `.gitignore` 忽略。
