package com.xiaohu.ai.service.vector;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Rerank 精排：调硅基流动 /v1/rerank（cross-encoder），按 relevance_score 重排候选。
 * 失败由调用方决定是否降级；本类只负责发请求与解析。
 */
@Component
public class RerankClient {

    private final RestClient restClient;
    private final String model;

    public RerankClient(@Value("${rerank.base-url:https://api.siliconflow.cn}") String baseUrl,
                        @Value("${spring.ai.openai.api-key}") String apiKey,
                        @Value("${rerank.model:BAAI/bge-reranker-v2-m3}") String model,
                        @Value("${rerank.timeout-ms:8000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.model = model;
    }

    /**
     * @param documents 待精排文本，顺序与返回值 index 对应
     * @return 按相关性降序的下标与分数；失败抛 IllegalStateException，由调用方降级
     */
    public List<ScoredIndex> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "query", query,
                "documents", documents,
                "top_n", documents.size(),
                "return_documents", false);

        JsonNode resp;
        try {
            resp = restClient.post()
                    .uri("/v1/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("rerank 调用失败：" + e.getMessage(), e);
        }

        if (resp == null || !resp.has("results")) {
            throw new IllegalStateException("rerank 响应缺少 results");
        }
        List<ScoredIndex> out = new ArrayList<>();
        for (JsonNode n : resp.get("results")) {
            out.add(new ScoredIndex(n.path("index").asInt(), n.path("relevance_score").asDouble()));
        }
        out.sort(Comparator.comparingDouble(ScoredIndex::score).reversed());
        return out;
    }

    /** index 对应入参 documents 的下标 */
    public record ScoredIndex(int index, double score) {
    }
}
