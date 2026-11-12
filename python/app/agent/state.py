from typing import Any, Dict, List, TypedDict

class HistoryMessage(TypedDict):
    role: str
    content: str

class AgentState(TypedDict, total=False):
    question: str
    history: List[HistoryMessage]
    auth: str            # Authorization 原样透传，工具回调 Java 时使用
    top_k: int
    mode: str
    intent: str          # "chitchat" | "knowledge"
    tool: str            # "search_knowledge" | "rag_answer"
    tool_result: Dict[str, Any]
    tools_used: List[Dict[str, Any]]
    answer: str
    citations: List[Dict[str, Any]]