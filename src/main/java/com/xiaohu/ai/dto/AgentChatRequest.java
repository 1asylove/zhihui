package com.xiaohu.ai.dto;

/**
 * Agent 问答请求，契约见 contracts/agent.openapi.yaml。
 */
public class AgentChatRequest {

    private String question;
    private Long sessionId;
    private Integer topK;
    private String mode;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
