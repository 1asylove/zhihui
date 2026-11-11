package com.xiaohu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.common.DocumentStatus;
import com.xiaohu.ai.dto.SearchHitVO;
import com.xiaohu.ai.dto.SearchResponse;
import com.xiaohu.ai.entity.Document;
import com.xiaohu.ai.mapper.ChunkMapper;
import com.xiaohu.ai.mapper.DocumentMapper;
import com.xiaohu.ai.service.vector.MilvusService;
import com.xiaohu.ai.service.vector.RerankClient;
import com.xiaohu.ai.service.vector.VectorizeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索服务：向量（bge-m3 + Milvus）与关键词（MySQL ngram）双路召回，
 * RRF 融合后可选 Rerank 精排。
 * 规格见 specs/qa/retrieval.md、specs/qa/hybrid-retrieval.md。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** RRF 名次衰减常数，文献默认值 */
    private static final int RRF_K = 60;
    /** 两路召回条数上限，防止单次查询把库拖慢 */
    private static final int MAX_FETCH = 50;
    private static final Set<String> MODES = Set.of("vector", "keyword", "hybrid");

    private final VectorizeClient vectorizeClient;
    private final MilvusService milvusService;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final RerankClient rerankClient;
    private final int defaultTopK;
    private final int maxTopK;
    private final int maxQueryLength;
    private final double minScore;
    private final String defaultMode;
    private final boolean rerankEnabled;
    private final int rerankMaxCandidates;

    public SearchService(VectorizeClient vectorizeClient,
                         MilvusService milvusService,
                         DocumentMapper documentMapper,
                         ChunkMapper chunkMapper,
                         RerankClient rerankClient,
                         @Value("${search.default-topk:5}") int defaultTopK,
                         @Value("${search.max-topk:20}") int maxTopK,
                         @Value("${search.max-query-length:500}") int maxQueryLength,
                         @Value("${search.min-score:0}") double minScore,
                         @Value("${search.mode:hybrid}") String defaultMode,
                         @Value("${rerank.enabled:true}") boolean rerankEnabled,
                         @Value("${rerank.max-candidates:20}") int rerankMaxCandidates) {
        this.vectorizeClient = vectorizeClient;
        this.milvusService = milvusService;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.rerankClient = rerankClient;
        this.defaultTopK = defaultTopK;
        this.maxTopK = maxTopK;
        this.maxQueryLength = maxQueryLength;
        this.minScore = minScore;
        this.defaultMode = MODES.contains(defaultMode) ? defaultMode : "hybrid";
        this.rerankEnabled = rerankEnabled;
        this.rerankMaxCandidates = rerankMaxCandidates;
    }

    /** 不带 mode 的调用（/qa/ask、/qa/stream）走配置默认值 */
    public SearchResponse search(String query, Integer topK, Long userId, boolean admin) {
        return search(query, topK, userId, admin, null);
    }

    /**
     * @param userId 当前用户 id，USER 用于向量库/关键词的权限过滤
     * @param admin  ADMIN 检索全量
     * @param mode   vector / keyword / hybrid，空则取 search.mode
     */
    public SearchResponse search(String query, Integer topK, Long userId, boolean admin,
                                 String mode) {
        long begin = System.nanoTime();

        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            throw new BizException(400, "query 不能为空");
        }
        if (q.length() > maxQueryLength) {
            throw new BizException(400, "query 超过 " + maxQueryLength + " 字上限");
        }
        int k = clampTopK(topK);
        String m = resolveMode(mode);

        // 精排前需要更多候选；纯向量时按 topK 的 3 倍召回，给融合留余量
        int fetchN = Math.min(MAX_FETCH,
                rerankEnabled ? Math.max(rerankMaxCandidates, k) : Math.max(k, k * 3));

        // 1. 向量召回（keyword 模式跳过向量化，省一次调用）
        List<SearchHitVO> vectorHits = List.of();
        if (!"keyword".equals(m)) {
            List<Float> vector = vectorizeClient.embed(List.of(q)).getVectors().get(0);
            vectorHits = vectorSearch(vector, fetchN, userId, admin);
        }

        // 2. 关键词召回（失败时 hybrid 降级为 vector；keyword 模式直接报错）
        List<SearchHitVO> keywordHits = List.of();
        if (!"vector".equals(m)) {
            try {
                keywordHits = chunkMapper.keywordSearch(q, fetchN, userId, admin);
            } catch (Exception e) {
                log.warn("关键词检索失败，降级为纯向量：{}", e.getMessage());
                if ("keyword".equals(m)) {
                    throw new BizException(503, "关键词检索失败：" + e.getMessage());
                }
                m = "vector";
            }
        }

        // 3. 融合（vector/keyword 直接透传，hybrid 走 RRF）
        List<Candidate> candidates = fuse(m, vectorHits, keywordHits);

        // 4. 精排：只对 hybrid 生效（vector/keyword 作为对照模式保持原始排序），失败保留融合排序
        boolean reranked = "hybrid".equals(m) && runRerank(q, candidates);
        List<SearchHitVO> hits = toHits(candidates, k);

        SearchResponse resp = new SearchResponse();
        resp.setQuery(q);
        resp.setTopK(k);
        resp.setMode(m);
        resp.setReranked(reranked);
        resp.setHits(hits);
        resp.setTookMs((System.nanoTime() - begin) / 1_000_000);
        log.info("检索完成：query={} mode={} 向量={} 关键词={} 候选={} 返回={} reranked={} "
                        + "耗时={}ms admin={}",
                q, m, vectorHits.size(), keywordHits.size(), candidates.size(), hits.size(),
                reranked, resp.getTookMs(), admin);
        return resp;
    }

    /** 精排候选并原地更新 score/顺序，返回是否真正精排成功 */
    private boolean runRerank(String query, List<Candidate> candidates) {
        if (!rerankEnabled || candidates.isEmpty()) {
            return false;
        }
        int n = Math.min(rerankMaxCandidates, candidates.size());
        List<Candidate> head = candidates.subList(0, n);
        List<String> documents = new ArrayList<>(n);
        for (Candidate c : head) {
            documents.add(c.content);
        }

        long begin = System.nanoTime();
        try {
            List<RerankClient.ScoredIndex> scored = rerankClient.rerank(query, documents);
            if (scored.isEmpty()) {
                return false;
            }
            List<Candidate> ordered = new ArrayList<>(candidates.size());
            for (RerankClient.ScoredIndex si : scored) {
                if (si.index() < 0 || si.index() >= head.size()) {
                    continue;
                }
                Candidate c = head.get(si.index());
                c.score = si.score();
                ordered.add(c);
            }
            // 未被精排返回的尾部候选保持原顺序追加
            ordered.addAll(candidates.subList(n, candidates.size()));
            candidates.clear();
            candidates.addAll(ordered);
            log.info("Rerank 完成：候选={} 耗时={}ms", n,
                    (System.nanoTime() - begin) / 1_000_000);
            return true;
        } catch (Exception e) {
            log.warn("Rerank 失败，保留融合排序：{}", e.getMessage());
            return false;
        }
    }

    private List<Candidate> fuse(String mode, List<SearchHitVO> vectorHits,
                                 List<SearchHitVO> keywordHits) {
        if ("vector".equals(mode)) {
            return toCandidates(vectorHits);
        }
        if ("keyword".equals(mode)) {
            return toCandidates(keywordHits);
        }
        Map<String, Candidate> merged = new LinkedHashMap<>();
        addRrf(merged, vectorHits);
        addRrf(merged, keywordHits);
        List<Candidate> list = new ArrayList<>(merged.values());
        list.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());
        return list;
    }

    /** RRF：同一片段累加两路名次分，天然去重 */
    private void addRrf(Map<String, Candidate> merged, List<SearchHitVO> hits) {
        for (int i = 0; i < hits.size(); i++) {
            SearchHitVO h = hits.get(i);
            String key = h.getDocumentId() + ":" + h.getChunkIndex();
            Candidate c = merged.computeIfAbsent(key, kk -> new Candidate(h, 0));
            c.score += 1.0 / (RRF_K + i + 1);
        }
    }

    private List<Candidate> toCandidates(List<SearchHitVO> hits) {
        List<Candidate> list = new ArrayList<>(hits.size());
        for (SearchHitVO h : hits) {
            list.add(new Candidate(h));
        }
        return list;
    }

    private List<SearchHitVO> toHits(List<Candidate> candidates, int k) {
        List<SearchHitVO> hits = new ArrayList<>(Math.min(k, candidates.size()));
        for (Candidate c : candidates) {
            if (hits.size() >= k) {
                break;
            }
            SearchHitVO vo = new SearchHitVO();
            vo.setDocumentId(c.documentId);
            vo.setChunkIndex(c.chunkIndex);
            vo.setFileName(c.fileName);
            vo.setScore(c.score);
            vo.setContent(c.content);
            hits.add(vo);
        }
        return hits;
    }

    /** 向量召回：Milvus TopN → 回表校验 → 带来源命中 */
    private List<SearchHitVO> vectorSearch(List<Float> vector, int fetchN, Long userId,
                                           boolean admin) {
        // 权限在 Milvus 侧过滤，避免无权片段挤占 TopK
        String expr = admin ? "document_id > 0" : "uploader_id == " + userId;
        List<MilvusService.Hit> raw = milvusService.search(vector, fetchN, expr);

        List<SearchHitVO> hits = new ArrayList<>(raw.size());
        if (raw.isEmpty()) {
            return hits;
        }
        // 一次批量回表，校验 deleted=0 且解析成功（兜底孤儿向量）
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
        return hits;
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

    /** 请求 mode 优先，缺省取配置；非法值 400 */
    private String resolveMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return defaultMode;
        }
        String m = mode.trim().toLowerCase();
        if (!MODES.contains(m)) {
            throw new BizException(400, "mode 仅支持 vector / keyword / hybrid");
        }
        return m;
    }

    /** topK 缺省用配置值，越界夹紧而不报错 */
    private int clampTopK(Integer topK) {
        int k = (topK == null) ? defaultTopK : topK;
        return Math.min(Math.max(k, 1), maxTopK);
    }

    /** 融合/精排过程中的可变候选 */
    private static final class Candidate {
        private final Long documentId;
        private final Integer chunkIndex;
        private final String fileName;
        private final String content;
        private double score;

        private Candidate(SearchHitVO vo) {
            this(vo, vo.getScore() == null ? 0 : vo.getScore());
        }

        /** 融合模式下从 0 起算 RRF 分，避免原始分（余弦/全文相关度）混入 */
        private Candidate(SearchHitVO vo, double initialScore) {
            this.documentId = vo.getDocumentId();
            this.chunkIndex = vo.getChunkIndex();
            this.fileName = vo.getFileName();
            this.content = vo.getContent();
            this.score = initialScore;
        }
    }
}
