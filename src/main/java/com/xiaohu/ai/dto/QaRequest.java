package com.xiaohu.ai.dto;

/**
 * 问答请求：question 必填，topK 可省略（缺省取 search.default-topk）。
 */
public class QaRequest {

    private String question;
    private Integer topK;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
}
