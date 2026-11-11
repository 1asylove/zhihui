package com.xiaohu.ai.dto;

/**
 * 问答请求：question 必填，topK 可省略（缺省取 search.default-topk）。
 * sessionId 可省略：为空则自动新建会话，否则在指定会话里追问。
 */
public class QaRequest {

    private String question;
    private Integer topK;
    private Long sessionId;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
}
