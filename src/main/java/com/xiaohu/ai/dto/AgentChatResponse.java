package com.xiaohu.ai.dto;

import java.util.List;

/**
 * Agent 问答响应。route 取 chitchat / knowledge / fallback；
 * degraded=true 时 route=fallback，回答来自 Java 本地 /qa/ask。
 */
public class AgentChatResponse {

    private Long sessionId;
    private String question;
    private String answer;
    private List<SearchHitVO> citations;
    private String route;
    private List<ToolUsage> toolsUsed;
    private Boolean degraded;
    private Long tookMs;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public List<SearchHitVO> getCitations() { return citations; }
    public void setCitations(List<SearchHitVO> citations) { this.citations = citations; }

    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }

    public List<ToolUsage> getToolsUsed() { return toolsUsed; }
    public void setToolsUsed(List<ToolUsage> toolsUsed) { this.toolsUsed = toolsUsed; }

    public Boolean getDegraded() { return degraded; }
    public void setDegraded(Boolean degraded) { this.degraded = degraded; }

    public Long getTookMs() { return tookMs; }
    public void setTookMs(Long tookMs) { this.tookMs = tookMs; }
}
