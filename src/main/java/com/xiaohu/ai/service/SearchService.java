package com.xiaohu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.common.DocumentStatus;
import com.xiaohu.ai.dto.SearchHitVO;
import com.xiaohu.ai.dto.SearchResponse;
import com.xiaohu.ai.entity.Document;
import com.xiaohu.ai.mapper.DocumentMapper;
import com.xiaohu.ai.service.vector.MilvusService;
import com.xiaohu.ai.service.vector.VectorizeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索服务：query → bge-m3 → Milvus TopK → 回表校验 → 带来源的命中。
 * 规格见 specs/qa/retrieval.md。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final VectorizeClient vectorizeClient;
    private final MilvusService milvusService;
    private final DocumentMapper documentMapper;
    private final int defaultTopK;
    private final int maxTopK;
    private final int maxQueryLength;
    private final double minScore;

    public SearchService(VectorizeClient vectorizeClient,
                         MilvusService milvusService,
                         DocumentMapper documentMapper,
                         @Value("${search.default-topk:5}") int defaultTopK,
                         @Value("${search.max-topk:20}") int maxTopK,
                         @Value("${search.max-query-length:500}") int maxQueryLength,
                         @Value("${search.min-score:0}") double minScore) {
        this.vectorizeClient = vectorizeClient;
        this.milvusService = milvusService;
        this.documentMapper = documentMapper;
        this.defaultTopK = defaultTopK;
        this.maxTopK = maxTopK;
        this.maxQueryLength = maxQueryLength;
        this.minScore = minScore;
    }

    /**
     * @param userId 当前用户 id，USER 用于向量库侧过滤
     * @param admin  ADMIN 检索全量
     */
    public SearchResponse search(String query, Integer topK, Long userId, boolean admin) {
        long begin = System.nanoTime();

        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            throw new BizException(400, "query 不能为空");
        }
        if (q.length() > maxQueryLength) {
            throw new BizException(400, "query 超过 " + maxQueryLength + " 字上限");
        }
        int k = clampTopK(topK);

        // 1. 问题向量化（失败时 VectorizeClient 抛 502）
        List<Float> vector = vectorizeClient.embed(List.of(q)).getVectors().get(0);

        // 2. 向量检索：权限在 Milvus 侧过滤，避免无权片段挤占 TopK
        String expr = admin ? "document_id > 0" : "uploader_id == " + userId;
        List<MilvusService.Hit> raw = milvusService.search(vector, k, expr);

        List<SearchHitVO> hits = new ArrayList<>(raw.size());
        if (!raw.isEmpty()) {
            // 3. 一次批量回表，校验 deleted=0 且解析成功（兜底孤儿向量）
            Map<Long, String> fileNames = loadFileNames(raw);

            for (MilvusService.Hit h : raw) {
                String fileName = fileNames.get(h.getDocumentId());
                if (fileName == null) {
                    continue;   // 文档已删/已失效，丢弃该命中
                }
                if (h.getScore() < minScore) {
                    continue;   // 默认 0 即关闭；禁止依赖绝对分数阈值
                }
                SearchHitVO vo = new SearchHitVO();
                vo.setDocumentId(h.getDocumentId());
                vo.setChunkIndex(h.getChunkIndex());
                vo.setFileName(fileName);
                vo.setScore(h.getScore());
                vo.setContent(h.getContent());
                hits.add(vo);
            }
        }

        SearchResponse resp = new SearchResponse();
        resp.setQuery(q);
        resp.setTopK(k);
        resp.setHits(hits);
        resp.setTookMs((System.nanoTime() - begin) / 1_000_000);
        log.info("检索完成：query={} topK={} 原始命中={} 有效命中={} 耗时={}ms admin={}",
                q, k, raw.size(), hits.size(), resp.getTookMs(), admin);
        return resp;
    }

    /** 批量取文件名，只查 id + file_name 两列 */
    private Map<Long, String> loadFileNames(List<MilvusService.Hit> raw) {
        Set<Long> docIds = new HashSet<>();
        for (MilvusService.Hit h : raw) {
            docIds.add(h.getDocumentId());
        }

        List<Document> docs = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .select(Document::getId, Document::getFileName)
                .in(Document::getId, docIds)
                .eq(Document::getDeleted, 0)
                .eq(Document::getStatus, DocumentStatus.SUCCESS));

        Map<Long, String> map = new HashMap<>(docs.size());
        for (Document d : docs) {
            map.put(d.getId(), d.getFileName());
        }
        return map;
    }

    /** topK 缺省用配置值，越界夹紧而不报错 */
    private int clampTopK(Integer topK) {
        int k = (topK == null) ? defaultTopK : topK;
        return Math.min(Math.max(k, 1), maxTopK);
    }
}
