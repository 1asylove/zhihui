"""智汇 · 向量化服务

职责：只做「文本列表 -> 向量列表」，不碰数据库、不碰文件，保持无状态。
由 Java 侧通过 HTTP 调用，契约见 contracts/vectorize.openapi.yaml。
"""
import asyncio
import logging
import os
import time
from pathlib import Path
from typing import List, Literal, Optional

import httpx
from fastapi import FastAPI, Header, HTTPException
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
AGENT_TIMEOUT = float(os.getenv("AGENT_TIMEOUT", "20"))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
log = logging.getLogger("zhihui.api")


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


class HistoryItem(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class AgentChatRequest(BaseModel):
    question: str
    userId: int
    admin: bool = False
    sessionId: Optional[int] = None
    topK: int = 5
    mode: Optional[str] = None
    history: List[HistoryItem] = Field(default_factory=list)


class Citation(BaseModel):
    documentId: int
    chunkIndex: int
    fileName: str = ""
    score: float = 0.0
    content: str = ""


class ToolUsage(BaseModel):
    name: str
    ok: bool
    hitCount: int = 0
    tookMs: int = 0


class AgentChatResponse(BaseModel):
    answer: str
    citations: List[Citation] = Field(default_factory=list)
    route: Literal["chitchat", "knowledge"]
    toolsUsed: List[ToolUsage] = Field(default_factory=list)
    tookMs: int


_graph = None


def get_graph():
    """懒加载：langchain 依赖异常时不拖垮向量化服务。"""
    global _graph
    if _graph is None:
        from app.agent.graph import GRAPH

        _graph = GRAPH
    return _graph


@app.post("/internal/agent/chat", response_model=AgentChatResponse)
async def agent_chat(
    req: AgentChatRequest,
    authorization: Optional[str] = Header(default=None),
) -> AgentChatResponse:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="缺少或无效的 Authorization")

    question = req.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="question 不能为空")
    if req.mode and req.mode not in ("vector", "keyword", "hybrid"):
        raise HTTPException(status_code=400, detail="mode 只能是 vector / keyword / hybrid")

    started = time.perf_counter()
    state = {
        "question": question,
        "history": [m.model_dump() for m in req.history],
        "auth": authorization,
        "top_k": max(1, min(req.topK, 20)),
        "mode": req.mode or "hybrid",
        "intent": "",
        "tool": "",
        "tools_used": [],
        "citations": [],
        "answer": "",
    }
    try:
        result = await asyncio.wait_for(get_graph().ainvoke(state), timeout=AGENT_TIMEOUT)
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail=f"Agent 执行超时（{AGENT_TIMEOUT}s）")

    took_ms = int((time.perf_counter() - started) * 1000)
    log.info(
        "agent: sessionId=%s intent=%s tool=%s 耗时=%sms",
        req.sessionId,
        result.get("intent"),
        result.get("tool"),
        took_ms,
    )
    return AgentChatResponse(
        answer=result.get("answer") or "",
        citations=result.get("citations") or [],
        route=result.get("intent") or "knowledge",
        toolsUsed=result.get("tools_used") or [],
        tookMs=took_ms,
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8000")))
