package com.xiaohu.ai.service.vector;

import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.dto.VectorizeRequest;
import com.xiaohu.ai.dto.VectorizeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * Java -> Python 向量化服务客户端。
 * Step 3 只打通调用与维度校验；写入 Milvus 与状态回写在 Step 4。
 */
@Component
public class VectorizeClient {

    private final RestClient restClient;
    private final int batchSize;
    private final int expectedDimension;

    public VectorizeClient(RestClient.Builder builder,
                           @Value("${vectorize.base-url:http://localhost:8000}") String baseUrl,
                           @Value("${vectorize.batch-size:16}") int batchSize,
                           @Value("${vectorize.expected-dimension:1024}") int expectedDimension) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.batchSize = batchSize;
        this.expectedDimension = expectedDimension;
    }

    /** 单批调用，校验维度 */
    public VectorizeResponse embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new BizException(400, "待向量化文本为空");
        }

        VectorizeRequest request = new VectorizeRequest(texts);
        try {
            VectorizeResponse response = restClient.post()
                    .uri("/internal/vectorize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(VectorizeResponse.class);

            if (response == null || response.getVectors() == null || response.getVectors().isEmpty()) {
                throw new BizException(502, "向量化服务返回空结果");
            }
            int actual = response.getVectors().get(0).size();
            if (actual != expectedDimension) {
                throw new BizException(502, "向量维度不符：期望 " + expectedDimension + "，实际 " + actual);
            }
            return response;
        } catch (RestClientException e) {
            throw new BizException(502, "向量化服务调用失败：" + e.getMessage());
        }
    }

    /** 按 batch-size 分批，返回与 texts 顺序一致的扁平向量列表 */
    public List<List<Float>> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<List<Float>> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            all.addAll(embed(new ArrayList<>(texts.subList(i, end))).getVectors());
        }
        return all;
    }
}
