# 向量化 / Agent 共用镜像：同一份 FastAPI 应用，靠 PORT 环境变量区分实例
FROM python:3.12-slim
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# 密钥走 compose 注入的环境变量，不拷贝 .env
ENV PORT=8000
EXPOSE 8000
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port $PORT"]
