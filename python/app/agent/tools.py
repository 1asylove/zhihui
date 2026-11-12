"""Agent 工具层：通过 HTTP 调用 Java 侧能力，权限靠透传的 Authorization。

- search_knowledge → POST /search
- rag_answer       → POST /qa/ask（persist=false，不写会话）
约定返回 {"text": str, "citations": list, "meta": dict}；失败不抛异常，
由上层退化为「未检索到依据」固定话术。
"""
import logging
import os
import time
from typing import Any, Dict

import httpx

log = logging.getLogger("zhihui.agent")

NO_HIT_TEXT = "根据现有资料，未找到与该问题相关的内容。"

JAVA_BASE_URL = os.getenv("JAVA_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
SEARCH_TIMEOUT = float(os.getenv("JAVA_SEARCH_TIMEOUT", "30"))
QA_TIMEOUT = float(os.getenv("JAVA_QA_TIMEOUT", "60"))


def _ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _headers(auth: str) -> Dict[str, str]:
    return {"Authorization": auth, "Content-Type": "application/json"}


def _fail(name: str, started: float, reason: str) -> Dict[str, Any]:
    log.warning("%s 调用失败：%s", name, reason)
    return {
        "text": "",
        "citations": [],
        "meta": {"name": name, "ok": False, "hitCount": 0, "tookMs": _ms(started)},
    }


def _body(resp: httpx.Response) -> Dict[str, Any]:
    """Java 统一返回 Result<T>；非 JSON 响应按空处理，避免解析异常盖住真实错误。"""
    try:
        data = resp.json()
    except ValueError:
        return {}
    return data if isinstance(data, dict) else {}


async def _post(path: str, auth: str, payload: Dict[str, Any], timeout: float) -> Dict[str, Any]:
    async with httpx.AsyncClient(timeout=timeout) as client:
        return await client.post(
            f"{JAVA_BASE_URL}{path}", headers=_headers(auth), json=payload
        )


async def search_knowledge(auth: str, question: str, top_k: int, mode: str) -> Dict[str, Any]:
    """检索工具：命中片段拼成带编号的资料块，交给 answer 节点引用。"""
    started = time.perf_counter()
    name = "search_knowledge"
    try:
        resp = await _post(
            "/search", auth,
            {"query": question, "topK": top_k, "mode": mode}, SEARCH_TIMEOUT,
        )
    except httpx.HTTPError as e:
        return _fail(name, started, f"请求异常：{e}")

    body = _body(resp)
    if resp.status_code != 200 or body.get("code") != 200:
        return _fail(name, started,
                     f"HTTP {resp.status_code} code={body.get('code')} message={body.get('message')}")

    hits = ((body.get("data") or {}).get("hits")) or []
    citations = [
        {
            "documentId": h.get("documentId"),
            "chunkIndex": h.get("chunkIndex", 0),
            "fileName": h.get("fileName", ""),
            "score": h.get("score", 0.0),
            "content": h.get("content", ""),
        }
        for h in hits
    ]
    blocks = [
        f"[{i + 1}] 来源：{c['fileName']}（第 {c['chunkIndex'] + 1} 片）\n{c['content']}"
        for i, c in enumerate(citations)
    ]
    return {
        "text": "\n\n".join(blocks),
        "citations": citations,
        "meta": {"name": name, "ok": True, "hitCount": len(citations), "tookMs": _ms(started)},
    }


async def rag_answer(auth: str, question: str, top_k: int, mode: str) -> Dict[str, Any]:
    """问答工具：直接复用 Java 的 RAG 流程，persist=false 避免与网关重复落库。"""
    started = time.perf_counter()
    name = "rag_answer"
    try:
        resp = await _post(
            "/qa/ask", auth,
            {"question": question, "topK": top_k, "persist": False}, QA_TIMEOUT,
        )
    except httpx.HTTPError as e:
        return _fail(name, started, f"请求异常：{e}")

    body = _body(resp)
    if resp.status_code != 200 or body.get("code") != 200:
        return _fail(name, started,
                     f"HTTP {resp.status_code} code={body.get('code')} message={body.get('message')}")

    data = body.get("data") or {}
    citations = data.get("citations") or []
    answer = (data.get("answer") or "").strip()
    # 无引用视为未命中，避免把「资料中未提及」当作依据再加工
    if not citations:
        answer = ""
    return {
        "text": answer,
        "citations": citations,
        "meta": {"name": name, "ok": True, "hitCount": len(citations), "tookMs": _ms(started)},
    }


async def run_tool(name: str, auth: str, question: str, top_k: int, mode: str) -> Dict[str, Any]:
    """按名称分派工具；未知名称按检索兜底，避免图走空。"""
    if name == "rag_answer":
        return await rag_answer(auth, question, top_k, mode)
    return await search_knowledge(auth, question, top_k, mode)
