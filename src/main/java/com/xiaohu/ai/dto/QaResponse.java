package com.xiaohu.ai.dto;

import java.util.List;

/**
 * 问答响应：citations 数组下标 + 1 即回答中的 [n] 引用标号。
 */
public class QaResponse {

    private Long sessionId;
    private String question;
    private String answer;
    private List<SearchHitVO> citations;
    private Long tookMs;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public List<SearchHitVO> getCitations() { return citations; }
    public void setCitations(List<SearchHitVO> citations) { this.citations = citations; }

    public Long getTookMs() { return tookMs; }
    public void setTookMs(Long tookMs) { this.tookMs = tookMs; }
}
