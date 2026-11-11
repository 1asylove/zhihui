package com.xiaohu.ai.dto;

/**
 * 检索请求：query 必填，topK 可省略（缺省取 search.default-topk）。
 * mode 可省略：vector / keyword / hybrid，缺省取 search.mode。
 */
public class SearchRequest {

    private String query;
    private Integer topK;
    private String mode;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
