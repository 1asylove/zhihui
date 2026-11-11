package com.xiaohu.ai.dto;

import java.util.List;

/**
 * 检索响应：hits 按 score 降序，无命中时为空列表而不是 404。
 */
public class SearchResponse {

    private String query;
    private Integer topK;
    private Long tookMs;
    private List<SearchHitVO> hits;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public Long getTookMs() { return tookMs; }
    public void setTookMs(Long tookMs) { this.tookMs = tookMs; }

    public List<SearchHitVO> getHits() { return hits; }
    public void setHits(List<SearchHitVO> hits) { this.hits = hits; }
}
