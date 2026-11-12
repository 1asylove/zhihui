"""M5 Agent 图：route → tools → answer（最小闭环）。

设计约束（见 specs/agent/agent-chat.md）：
- 只做一次工具调用，不做循环
- route 的结构化输出解析失败时兜底 knowledge + search_knowledge
- 无工具结果时返回固定话术，绝不编造引用
"""
import json
import logging
import os
import time
from typing import Any, Dict, List, Optional, Tuple

from langchain_openai import ChatOpenAI
from langgraph.graph import END, START, StateGraph

from app.agent.state import AgentState
from app.agent.tools import NO_HIT_TEXT, run_tool

log = logging.getLogger("zhihui.agent")

DEFAULT_MODEL = "Qwen/Qwen2.5-7B-Instruct"
DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1"
LLM_TIMEOUT = float(os.getenv("AGENT_LLM_TIMEOUT", "15"))

ROUTE_PROMPT = """你是企业知识库问答的意图分类器。
判断用户问题属于哪一类：
- chitchat：寒暄、闲聊，或明显与知识库内容无关
- knowledge：需要查询企业内部制度、文档、规范

可选工具：
- search_knowledge：检索文档片段，适合需要给出依据或原文的问题
- rag_answer：直接基于知识库给出完整回答，适合“是什么 / 怎么规定”类问题

只输出一行 JSON，不要输出解释、代码块或多余文字：
{"intent": "chitchat 或 knowledge", "tool": "search_knowledge 或 rag_answer"}
"""

CHITCHAT_PROMPT = """你是「智汇」企业知识助手。用户只是在寒暄或闲聊时，请简短友好地回应，
不要编造任何企业制度内容，也不要输出引用编号。"""

ANSWER_PROMPT = """你是「智汇」企业知识助手。请严格依据下面的资料回答用户问题：
1. 只使用资料中的信息，不允许编造
2. 引用资料时在句末标注 [n]，n 为资料片段编号
3. 资料不足以回答时，直接说明“根据现有资料，未找到与该问题相关的内容”

资料：
"""

_llm: Optional[ChatOpenAI] = None


def get_llm() -> ChatOpenAI:
    """懒初始化：未配置 Key 时应用仍可启动，只在实际调用时失败。"""
    global _llm
    if _llm is None:
        _llm = ChatOpenAI(
            model=os.getenv("AGENT_MODEL", DEFAULT_MODEL),
            base_url=os.getenv("AGENT_BASE_URL", DEFAULT_BASE_URL),
            api_key=os.getenv("SILICONFLOW_API_KEY", ""),
            temperature=0.2,
            timeout=LLM_TIMEOUT,
        )
    return _llm


def _ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _parse_json(raw: str) -> Dict[str, Any]:
    """容错解析：去掉 ```json 包裹，取首尾大括号之间的内容。"""
    text = (raw or "").strip()
    if text.startswith("```"):
        text = text.strip("`").strip()
        if text.lower().startswith("json"):
            text = text[4:].strip()
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        return {}
    try:
        return json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return {}


def _history_messages(history: List[Dict[str, Any]]) -> List[Tuple[str, str]]:
    """历史最多带最近 4 条，role 只认 user/assistant。"""
    messages = []
    for item in history[-4:]:
        role = item.get("role")
        content = (item.get("content") or "").strip()
        if role in ("user", "assistant") and content:
            messages.append((role, content))
    return messages


async def route_node(state: AgentState) -> Dict[str, Any]:
    started = time.perf_counter()
    messages: List[Tuple[str, str]] = [("system", ROUTE_PROMPT)]
    messages.extend(_history_messages(state.get("history") or []))
    messages.append(("user", state.get("question", "")))

    intent, tool = "knowledge", "search_knowledge"
    try:
        resp = await get_llm().ainvoke(messages)
        data = _parse_json(getattr(resp, "content", "") or "")
        if data.get("intent") in ("chitchat", "knowledge"):
            intent = data["intent"]
        if data.get("tool") in ("search_knowledge", "rag_answer"):
            tool = data["tool"]
    except Exception as e:  # 上游抖动不阻断主流程，按知识问题兜底
        log.warning("route 失败，兜底 knowledge/search_knowledge：%s", e)

    if intent == "chitchat":
        tool = ""
    log.info("route: intent=%s tool=%s 耗时=%sms", intent, tool, _ms(started))
    return {"intent": intent, "tool": tool}


async def tools_node(state: AgentState) -> Dict[str, Any]:
    started = time.perf_counter()
    name = state.get("tool") or "search_knowledge"
    try:
        result = await run_tool(
            name,
            state.get("auth", ""),
            state.get("question", ""),
            state.get("top_k", 5),
            state.get("mode") or "hybrid",
        )
    except Exception as e:
        log.warning("工具 %s 调用失败：%s", name, e)
        result = {
            "text": "",
            "citations": [],
            "meta": {"name": name, "ok": False, "hitCount": 0, "tookMs": _ms(started)},
        }
    log.info("tools: name=%s ok=%s 耗时=%sms", name, result["meta"]["ok"], _ms(started))
    return {"tool_result": result, "tools_used": [result["meta"]]}


async def answer_node(state: AgentState) -> Dict[str, Any]:
    started = time.perf_counter()
    question = state.get("question", "")

    if state.get("intent") == "chitchat":
        try:
            resp = await get_llm().ainvoke(
                [("system", CHITCHAT_PROMPT), ("user", question)]
            )
            answer = (getattr(resp, "content", "") or "").strip()
        except Exception as e:
            log.warning("chitchat 生成失败：%s", e)
            answer = "你好，我是「智汇」企业知识助手，可以询问企业制度相关问题。"
        log.info("answer: route=chitchat 耗时=%sms", _ms(started))
        return {"answer": answer, "citations": []}

    tool_result = state.get("tool_result") or {}
    text = (tool_result.get("text") or "").strip()
    citations = tool_result.get("citations") or []
    if not text:
        log.info("answer: 无工具结果，返回固定话术 耗时=%sms", _ms(started))
        return {"answer": NO_HIT_TEXT, "citations": []}

    messages: List[Tuple[str, str]] = [("system", ANSWER_PROMPT + text)]
    messages.extend(_history_messages(state.get("history") or []))
    messages.append(("user", question))
    try:
        resp = await get_llm().ainvoke(messages)
        answer = (getattr(resp, "content", "") or "").strip() or text
    except Exception as e:
        log.warning("answer 生成失败，回退工具原文：%s", e)
        answer = text
    log.info("answer: route=knowledge 耗时=%sms", _ms(started))
    return {"answer": answer, "citations": citations}


def build_graph():
    builder = StateGraph(AgentState)
    builder.add_node("route", route_node)
    builder.add_node("tools", tools_node)
    builder.add_node("answer", answer_node)
    builder.add_edge(START, "route")
    builder.add_conditional_edges(
        "route",
        lambda s: "answer" if s.get("intent") == "chitchat" else "tools",
        {"answer": "answer", "tools": "tools"},
    )
    builder.add_edge("tools", "answer")
    builder.add_edge("answer", END)
    return builder.compile()


GRAPH = build_graph()
