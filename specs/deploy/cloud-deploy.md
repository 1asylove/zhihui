# 云部署（cloud-deploy）

## 背景
M1~M5 全部闭环、前端三页实测通过后，项目从本机开发迁移到云服务器，对外提供访问。
本机为轻薄本，不承担任何容器/服务运行；全部组件跑在云端单台服务器上。

## 已确认决策

| 决策项 | 结论 |
|---|---|
| 云厂商 | 阿里云 ECS |
| 配置 | **2核4G 起步**（1核2G 不够：Java + MySQL + 2×Python + Nginx），建议另加 2G swap |
| 操作系统 | Ubuntu 22.04 |
| 部署形态 | 单机 Docker + docker compose，容器全在云端 |
| 域名/备案 | **不买域名、不备案**，直接公网 IP 访问（HTTP 80 端口） |
| 代码托管 | GitHub（**私有仓库**，因 init.sql 含测试账号哈希；国内云拉 GitHub 可能慢，必要时配镜像/代理） |
| 外部依赖 | Zilliz Cloud（向量库）与硅基流动（LLM/Embedding/Rerank）为云上服务，**不部署**，只配密钥 |

## 架构

```
浏览器
  │ HTTP :80
  ▼
Nginx 容器 ── 静态托管前端（npm build 产物）
  │ 反代 /auth /sessions /agent /qa /search /documents /charts
  ▼
Java 容器 :8080（Spring Boot）
  ├──► MySQL 容器 :3306（ai_kb，volume 持久化）
  ├──► python-vectorize 容器 :8000（/internal/vectorize）
  ├──► python-agent 容器 :8100（/internal/agent/chat，回调 Java /search、/qa/ask）
  ├──► Zilliz Cloud（外部，MILVUS_TOKEN）
  └──► 硅基流动（外部，SILICONFLOW_API_KEY）
```

- 容器间用 compose 服务名互访（`mysql` / `java`），不暴露 8080/3306/8000/8100 到公网，只有 80 对外
- 前端 `api.js` 全部走**相对路径**，无需改代码；Nginx 按前缀反代
- SSE（`/qa/stream`）经 Nginx 必须 `proxy_buffering off` + 长超时，否则流式输出会被整段缓冲

## 新增文件清单

| 文件 | 作用 |
|---|---|
| `Dockerfile`（根目录） | Java 多阶段构建：maven 编译 → jre 运行 |
| `python/Dockerfile` | Python 镜像，vectorize 与 agent 两个容器共用，靠 `PORT` 环境变量区分 |
| `web/Dockerfile` | 前端多阶段构建：node 构建 dist → nginx 托管 |
| `web/nginx.conf` | 静态托管 + history 路由回退 + API 反代（含 SSE 配置） |
| `docker-compose.yml`（根目录） | 编排 mysql / python×2 / java / nginx 五个服务 |
| `deploy/.env.example` | 服务器端密钥与密码模板（复制为 `.env`，**不提交真实值**） |
| `deploy/mysql/init.sql` | 数据库初始化（本地 mysqldump 导出，**随仓库提交**） |

## application.properties 改动（环境变量占位）
本地开发默认值全部保留，Docker 里用环境变量覆盖：

```properties
spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/ai_kb?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:root}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:123456}
jwt.secret=${JWT_SECRET:原默认值}
upload.dir=${UPLOAD_DIR:D:/ai/uploads}
vectorize.base-url=${VECTORIZE_BASE_URL:http://localhost:8000}
agent.base-url=${AGENT_BASE_URL:http://127.0.0.1:8100}
```

- `SILICONFLOW_API_KEY` / `MILVUS_TOKEN` 原本就走环境变量，无需改
- 用显式 `${VAR:default}` 占位，不依赖 relaxed binding（`base-url` 的环境变量名有坑）

## docker-compose 要点
- `mysql`：`mysql:8.0`，utf8mb4，`mysql_data` volume，挂载 `init.sql` 到 `/docker-entrypoint-initdb.d/`（**仅首次启动执行**），healthcheck 探活
- `python-vectorize` / `python-agent`：同镜像两实例，`PORT=8000/8100`；agent 额外注入 `JAVA_BASE_URL=http://java:8080`
- `java`：`depends_on: mysql (service_healthy)`；`JAVA_TOOL_OPTIONS=-Xmx768m`（4G 内存保护）；上传目录挂 volume `upload_data`
- `nginx`：唯一暴露端口 `80:80`
- 密钥只进 `.env`（compose 自动读取），不进 compose 文件本身

## 环境变量清单（服务器 .env）

| 变量 | 用途 | 来源 |
|---|---|---|
| MYSQL_ROOT_PASSWORD | MySQL root 密码 | 新设强密码 |
| JWT_SECRET | JWT 签名密钥 | 新设长随机串 |
| SILICONFLOW_API_KEY | 硅基流动 | 现有值 |
| MILVUS_TOKEN | Zilliz Cloud | 现有值（`<集群用户名>:<密码>`） |

## 数据库初始化（init.sql）
在**本机** PowerShell 导出全量（结构 + 数据，含角色/权限种子与测试账号）：

```powershell
mysqldump -uroot -p123456 --default-character-set=utf8mb4 --result-file=D:\idea个人项目\ai\deploy\mysql\init.sql ai_kb
```

- **必须用 `--result-file`，禁止用 `>` 重定向**：Windows PowerShell 的 `>` 会把 mysqldump 的 UTF-8 输出按 GBK 解码再写文件，中文全部乱码（实测踩过）
- 若 mysqldump 不在 PATH，用全路径，如 `& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" ...`
- 导出后抽查 `INSERT INTO permission` 中文正常即合格
- 知识库数据（document/chunk/会话）一并带走；Zilliz 是云端共享实例，milvus_id 对得上，向量无需迁移
- 上传的文件本体在 `D:/ai/uploads`，不迁移（系统无下载功能，不影响使用）

## 部署步骤（服务器侧）
1. 买阿里云 ECS：2核4G / Ubuntu 22.04 / 按量或包月 / 记下**公网 IP**；安全组放行 **22（SSH）与 80（HTTP）**
2. 装 Docker：`curl -fsSL https://get.docker.com | bash`，再 `sudo usermod -aG docker $USER` 重新登录
3. （建议）加 2G swap，防止构建期 OOM：
   `sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile`
4. `git clone <私有仓库地址>`，进入项目目录
5. `cp deploy/.env.example .env`，填入 4 个真实值
6. `docker compose up -d --build`（首次构建拉镜像较慢，Maven/npm 走容器内构建，服务器无需装 JDK/node）
7. `docker compose ps` 五个服务全 Up；`docker compose logs -f java` 看启动日志

## 验收（浏览器访问 `http://<公网IP>`）
1. 登录页用 test1/123456 登录成功
2. 问答页提问知识库已有内容 → SSE 逐字输出 + 引用溯源
3. `/docs` 上传一个新 txt → 状态徽章流转到 SUCCESS
4. 用新上传文档提问 → 命中引用（验证上传→切片→向量化→Zilliz 全链路）
5. Agent 模式提问 → 正常回答（验证 python-agent ↔ java 容器互调）
6. `/dashboard` 数字与实际一致
7. test2 登录 → 看不到 test1 的文档（权限隔离在云端同样生效）

## 风险与回滚
- **GitHub 拉取慢**：给 git 配代理，或用阿里云镜像仓库中转；最坏情况本机 `git bundle` 后 scp 上传
- **构建 OOM**：先加 swap 再构建；Java 构建阶段最吃内存
- **MySQL 首启失败**：`init.sql` 只在空库首次启动执行；改 sql 后需 `docker compose down -v` 清 volume 重来
- **回滚**：云实例不动，本机开发环境完全不受影响；线上问题随时 `docker compose down` 停服
