"""智汇 · 向量化服务

职责：只做「文本列表 -> 向量列表」，不碰数据库、不碰文件，保持无状态。
由 Java 侧通过 HTTP 调用，契约见 contracts/vectorize.openapi.yaml。
"""
import os
from pathlib import Path
from typing import List, Optional

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field


def _load_env() -> None:
    """极简 .env 加载，避免额外依赖；已存在的环境变量优先。"""
    env_file = Path(__file__).resolve().parent.parent / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


_load_env()

API_KEY = os.getenv("SILICONFLOW_API_KEY", "")
BASE_URL = os.getenv("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1")
DEFAULT_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")


class VectorizeRequest(BaseModel):
    texts: List[str] = Field(...)
    model: Optional[str] = None


class VectorizeResponse(BaseModel):
    model: str
    dimension: int
    vectors: List[List[float]]


app = FastAPI(title="智汇向量化服务", version="0.1.0")


@app.get("/internal/health")
def health() -> dict:
    return {"status": "ok", "model": DEFAULT_MODEL}


@app.post("/internal/vectorize", response_model=VectorizeResponse)
async def vectorize(req: VectorizeRequest) -> VectorizeResponse:
    if len(req.texts) > 32:
        raise HTTPException(status_code=400, detail="单批最多 32 条文本")

    texts = [t.strip() for t in req.texts if t and t.strip()]
    if not texts:
        raise HTTPException(status_code=400, detail="texts 不能为空")

    if not API_KEY:
        raise HTTPException(status_code=500, detail="SILICONFLOW_API_KEY 未配置")

    model = req.model or DEFAULT_MODEL
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(
                f"{BASE_URL}/embeddings",
                headers={"Authorization": f"Bearer {API_KEY}"},
                json={"model": model, "input": texts, "encoding_format": "float"},
            )
    except httpx.HTTPError as e:
        raise HTTPException(status_code=502, detail=f"上游请求异常：{e}") from e

    if resp.status_code != 200:
        raise HTTPException(status_code=502, detail=f"上游返回 {resp.status_code}: {resp.text[:200]}")

    data = resp.json().get("data") or []
    if len(data) != len(texts):
        raise HTTPException(
            status_code=502,
            detail=f"向量数量不符：请求 {len(texts)} 条，返回 {len(data)} 条",
        )

    vectors = [item["embedding"] for item in data]
    return VectorizeResponse(model=model, dimension=len(vectors[0]), vectors=vectors)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8000")))
