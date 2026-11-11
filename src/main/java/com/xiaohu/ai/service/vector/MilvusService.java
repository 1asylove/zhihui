package com.xiaohu.ai.service.vector;

import com.xiaohu.ai.common.BizException;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class MilvusService {

    private static final Logger log = LoggerFactory.getLogger(MilvusService.class);

    private final String uri;
    private final String token;
    private final String collection;
    private final String metric;
    private final int dimension;
    private volatile MilvusServiceClient client;
    private volatile boolean ready = false;

    public MilvusService(@Value("${milvus.uri:http://localhost:19530}") String uri,
                         @Value("${milvus.token:}") String token,
                         @Value("${milvus.collection:zhihui_chunk}") String collection,
                         @Value("${milvus.metric:COSINE}") String metric,
                         @Value("${vectorize.expected-dimension:1024}") int dimension) {
        this.uri = uri;
        this.token = token;
        this.collection = collection;
        this.metric = metric;
        this.dimension = dimension;
    }

    /**
     * 首次用到时才建连接：构造器只存配置，Milvus 不在线不会拖垮应用启动。
     * 连接超时 15 秒：Zilliz Cloud Serverless 有冷启动，3 秒在跨洋链路上容易误判失败。
     */
    private synchronized MilvusServiceClient client() {
        if (client != null) {
            return client;
        }
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withUri(uri)
                .withConnectTimeout(15, TimeUnit.SECONDS);
        if (token != null && !token.isBlank()) {
            builder.withToken(token);
        }
        try {
            client = new MilvusServiceClient(builder.build());
        } catch (Exception e) {
            throw new BizException(503, "Milvus 连接失败：" + e.getMessage());
        }
        return client;
    }

    /** 首次使用时建 collection + 索引 + load；成功后缓存，避免每次写入都探测 */
    private synchronized void ensureReady() {
        if (ready) {
            return;
        }
        MilvusServiceClient c = client();
        R<Boolean> has = c.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collection).build());
        if (Boolean.TRUE.equals(has.getData())) {
            checkDimension();
        } else {
            createCollection();
            createIndex();
        }
        load();
        ready = true;
        log.info("Milvus 就绪：collection={} dim={} metric={}", collection, dimension, metric);
    }

    private void createCollection() {
        List<FieldType> fields = new ArrayList<>();
        fields.add(FieldType.newBuilder()
                .withName("milvus_id").withDataType(DataType.VarChar)
                .withPrimaryKey(true).withAutoID(false).withMaxLength(64).build());
        fields.add(FieldType.newBuilder()
                .withName("document_id").withDataType(DataType.Int64).build());
        fields.add(FieldType.newBuilder()
                .withName("chunk_index").withDataType(DataType.Int32).build());
        fields.add(FieldType.newBuilder()
                .withName("uploader_id").withDataType(DataType.Int64).build());
        fields.add(FieldType.newBuilder()
                .withName("content").withDataType(DataType.VarChar).withMaxLength(2048).build());
        fields.add(FieldType.newBuilder()
                .withName("vector").withDataType(DataType.FloatVector).withDimension(dimension).build());

        R<?> r = client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(collection)
                .withDescription("智汇文档切片向量")
                .withFieldTypes(fields)
                .withEnableDynamicField(false)
                .build());
        check(r, "创建 collection");
    }

    private void createIndex() {
        R<?> r = client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collection)
                .withFieldName("vector")
                .withIndexType(IndexType.AUTOINDEX)
                .withMetricType(MetricType.valueOf(metric))
                .withSyncMode(Boolean.TRUE)
                .build());
        check(r, "创建索引");
    }

    private void load() {
        R<?> r = client.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collection).build());
        check(r, "load collection");
    }

    /** collection 已存在时校验维度，防止换模型后带病写入 */
    private void checkDimension() {
        R<DescribeCollectionResponse> r = client.describeCollection(
                DescribeCollectionParam.newBuilder().withCollectionName(collection).build());
        check(r, "describe collection");
        int actual = r.getData().getSchema().getFieldsList().stream()
                .filter(f -> "vector".equals(f.getName()))
                .findFirst()
                .map(f -> f.getTypeParamsList().stream()
                        .filter(t -> "dim".equals(t.getKey()))
                        .map(t -> Integer.parseInt(t.getValue()))
                        .findFirst().orElse(-1))
                .orElse(-1);
        if (actual != dimension) {
            throw new IllegalStateException(
                    "Milvus collection 维度不匹配：已有 " + actual + "，期望 " + dimension);
        }
    }

    /**
     * 全量字段 upsert（Milvus 的 upsert = delete + insert，必须 6 字段都传）。
     */
    public void upsert(List<String> ids, List<Long> documentIds, List<Integer> indexes,
                       List<Long> uploaderIds, List<String> contents, List<List<Float>> vectors) {
        ensureReady();

        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field("milvus_id", ids),
                new InsertParam.Field("document_id", documentIds),
                new InsertParam.Field("chunk_index", indexes),
                new InsertParam.Field("uploader_id", uploaderIds),
                new InsertParam.Field("content", contents),
                new InsertParam.Field("vector", vectors));

        R<?> r = client.upsert(UpsertParam.newBuilder()
                .withCollectionName(collection)
                .withFields(fields)
                .build());
        check(r, "upsert 向量");
    }

    /** 按文档删除向量，返回删除条数 */
    public long deleteByDocument(Long documentId) {
        ensureReady();
        R<?> r = client.delete(DeleteParam.newBuilder()
                .withCollectionName(collection)
                .withExpr("document_id == " + documentId)
                .build());
        check(r, "按文档删除向量");
        return 0;
    }

    /** 统计某文档的向量条数（验收用） */
    public long countByDocument(Long documentId) {
        ensureReady();
        R<QueryResults> r = client.query(QueryParam.newBuilder()
                .withCollectionName(collection)
                .withExpr("document_id == " + documentId)
                .withOutFields(List.of("count(*)"))
                .build());
        check(r, "统计向量条数");
        QueryResultsWrapper wrapper = new QueryResultsWrapper(r.getData());
        return (long) wrapper.getFieldWrapper("count(*)").getFieldData().get(0);
    }

    /** 检索冒烟：按文档内相似度取 topK（M4 会扩展为跨文档混合检索） */
    public List<Long> searchInDocument(Long documentId, List<Float> queryVector, int topK) {
        ensureReady();
        R<SearchResults> r = client.search(SearchParam.newBuilder()
                .withCollectionName(collection)
                .withMetricType(MetricType.valueOf(metric))
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .withExpr("document_id == " + documentId)
                .withTopK(topK)
                .withOutFields(List.of("chunk_index"))
                .withVectors(List.of(queryVector))
                .withVectorFieldName("vector")
                .build());
        check(r, "检索");
        SearchResultsWrapper wrapper = new SearchResultsWrapper(r.getData().getResults());
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
            out.add((Long) wrapper.getRowRecords(0).get(i).get("chunk_index"));
        }
        return out;
    }

    private void check(R<?> r, String action) {
        if (r.getStatus() != R.Status.Success.getCode()) {
            throw new BizException(503, "Milvus " + action + "失败：" + r.getMessage());
        }
    }
}