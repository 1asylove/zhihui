package com.xiaohu.ai.dto;

/**
 * Agent 工具调用记录，对应契约 ToolUsage。
 * ok=false 表示工具失败但主流程继续，回答退化为固定话术。
 */
public class ToolUsage {

    private String name;
    private Boolean ok;
    private Integer hitCount;
    private Long tookMs;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Boolean getOk() { return ok; }
    public void setOk(Boolean ok) { this.ok = ok; }

    public Integer getHitCount() { return hitCount; }
    public void setHitCount(Integer hitCount) { this.hitCount = hitCount; }

    public Long getTookMs() { return tookMs; }
    public void setTookMs(Long tookMs) { this.tookMs = tookMs; }
}
