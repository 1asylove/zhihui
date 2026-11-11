package com.xiaohu.ai.dto;

/**
 * 检索请求：query 必填，topK 可省略（缺省取 search.default-topk）。
 */
public class SearchRequest {

    private String query;
    private Integer topK;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
}
