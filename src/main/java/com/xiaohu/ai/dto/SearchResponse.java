package com.xiaohu.ai.dto;

import java.util.List;

/**
 * 检索响应：hits 按 score 降序，无命中时为空列表而不是 404。
 * mode 为实际使用的检索模式，reranked 表示是否经过精排。
 */
public class SearchResponse {

    private String query;
    private Integer topK;
    private String mode;
    private Boolean reranked;
    private Long tookMs;
    private List<SearchHitVO> hits;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Boolean getReranked() { return reranked; }
    public void setReranked(Boolean reranked) { this.reranked = reranked; }

    public Long getTookMs() { return tookMs; }
    public void setTookMs(Long tookMs) { this.tookMs = tookMs; }

    public List<SearchHitVO> getHits() { return hits; }
    public void setHits(List<SearchHitVO> hits) { this.hits = hits; }
}
