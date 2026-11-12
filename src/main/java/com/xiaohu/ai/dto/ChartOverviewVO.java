package com.xiaohu.ai.dto;

import java.util.List;

/**
 * 首页看板聚合数据。字段口径见 specs/chart/dashboard-stats.md。
 */
public class ChartOverviewVO {

    private Long totalDocuments;
    private Long successDocuments;
    private Long failedDocuments;
    private Long processingDocuments;
    private Long chunkCount;
    private Long sessionCount;
    private List<NameCountVO> typeDistribution;
    private List<DateCountVO> recentUploads;

    public Long getTotalDocuments() { return totalDocuments; }
    public void setTotalDocuments(Long totalDocuments) { this.totalDocuments = totalDocuments; }

    public Long getSuccessDocuments() { return successDocuments; }
    public void setSuccessDocuments(Long successDocuments) { this.successDocuments = successDocuments; }

    public Long getFailedDocuments() { return failedDocuments; }
    public void setFailedDocuments(Long failedDocuments) { this.failedDocuments = failedDocuments; }

    public Long getProcessingDocuments() { return processingDocuments; }
    public void setProcessingDocuments(Long processingDocuments) { this.processingDocuments = processingDocuments; }

    public Long getChunkCount() { return chunkCount; }
    public void setChunkCount(Long chunkCount) { this.chunkCount = chunkCount; }

    public Long getSessionCount() { return sessionCount; }
    public void setSessionCount(Long sessionCount) { this.sessionCount = sessionCount; }

    public List<NameCountVO> getTypeDistribution() { return typeDistribution; }
    public void setTypeDistribution(List<NameCountVO> typeDistribution) { this.typeDistribution = typeDistribution; }

    public List<DateCountVO> getRecentUploads() { return recentUploads; }
    public void setRecentUploads(List<DateCountVO> recentUploads) { this.recentUploads = recentUploads; }
}
