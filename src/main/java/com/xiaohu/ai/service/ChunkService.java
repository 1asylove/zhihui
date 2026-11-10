package com.xiaohu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xiaohu.ai.common.ChunkStatus;
import com.xiaohu.ai.entity.Chunk;
import com.xiaohu.ai.entity.Document;
import com.xiaohu.ai.mapper.ChunkMapper;
import com.xiaohu.ai.service.chunk.TextSplitter;
import com.xiaohu.ai.service.vector.MilvusService;
import com.xiaohu.ai.service.vector.VectorizeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    private final ChunkMapper chunkMapper;
    private final TextSplitter textSplitter;
    private final VectorizeClient vectorizeClient;
    private final MilvusService milvusService;
    private final int batchSize;

    public ChunkService(ChunkMapper chunkMapper,
                        TextSplitter textSplitter,
                        VectorizeClient vectorizeClient,
                        MilvusService milvusService,
                        @Value("${vectorize.batch-size:16}") int batchSize) {
        this.chunkMapper = chunkMapper;
        this.textSplitter = textSplitter;
        this.vectorizeClient = vectorizeClient;
        this.milvusService = milvusService;
        this.batchSize = batchSize;
    }

    /**
     * 只做切片落库（PENDING），向量化在 Step 3。
     * 返回切片数；content 为空时返回 0。
     */
    public int sliceAndSave(Document doc) {
        if (doc == null || doc.getContent() == null || doc.getContent().isBlank()) {
            return 0;
        }

        List<String> pieces = textSplitter.split(doc.getContent());
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < pieces.size(); i++) {
            Chunk c = new Chunk();
            c.setDocumentId(doc.getId());
            c.setChunkIndex(i);
            c.setContent(pieces.get(i));
            c.setCharCount(pieces.get(i).length());
            c.setStatus(ChunkStatus.PENDING);
            c.setCreateTime(now);
            c.setUpdateTime(now);
            c.setDeleted(0);
            chunkMapper.insert(c);
        }
        return pieces.size();
    }

    /** 按文档查片，按 chunk_index 升序（验收与 Step3 都要用） */
    public List<Chunk> listByDocument(Long documentId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocumentId, documentId)
                .eq(Chunk::getDeleted, 0)
                .orderByAsc(Chunk::getChunkIndex));
    }
    /**
     * 向量化：只处理 PENDING 片，按批 upsert，单批失败只标该批。
     * 返回成功片数。
     */
    public int vectorize(Document doc) {
        List<Chunk> chunks = listByDocument(doc.getId()).stream()
                .filter(c -> ChunkStatus.PENDING.equals(c.getStatus()))
                .toList();
        if (chunks.isEmpty()) {
            return 0;
        }

        int success = 0;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<Chunk> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            try {
                List<String> texts = batch.stream().map(Chunk::getContent).toList();
                List<List<Float>> vectors = vectorizeClient.embedAll(texts);

                List<String> ids = new ArrayList<>();
                List<Long> docIds = new ArrayList<>();
                List<Integer> indexes = new ArrayList<>();
                List<Long> uploaders = new ArrayList<>();
                for (Chunk c : batch) {
                    ids.add("d" + c.getDocumentId() + "_c" + c.getChunkIndex());
                    docIds.add(c.getDocumentId());
                    indexes.add(c.getChunkIndex());
                    uploaders.add(doc.getUploaderId());
                }

                milvusService.upsert(ids, docIds, indexes, uploaders, texts, vectors);
                markChunkSuccess(batch, ids);
                success += batch.size();
            } catch (Exception e) {
                log.warn("文档 {} 第 {}-{} 片向量化失败：{}",
                        doc.getId(), i, i + batch.size() - 1, e.getMessage());
                markChunkFailed(batch, e.getMessage());
            }
        }
        return success;
    }

    private void markChunkSuccess(List<Chunk> batch, List<String> milvusIds) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < batch.size(); i++) {
            Chunk patch = new Chunk();
            patch.setId(batch.get(i).getId());
            patch.setStatus(ChunkStatus.SUCCESS);
            patch.setMilvusId(milvusIds.get(i));
            patch.setErrorMsg(null);
            patch.setUpdateTime(now);
            chunkMapper.updateById(patch);
        }
    }

    private void markChunkFailed(List<Chunk> batch, String message) {
        LocalDateTime now = LocalDateTime.now();
        String err = message == null ? "未知错误" : message;
        if (err.length() > 500) {
            err = err.substring(0, 500);
        }
        for (Chunk c : batch) {
            Chunk patch = new Chunk();
            patch.setId(c.getId());
            patch.setStatus(ChunkStatus.FAILED);
            patch.setErrorMsg(err);
            patch.setUpdateTime(now);
            chunkMapper.updateById(patch);
        }
    }

    /** 逻辑删除某文档全部切片，返回影响行数 */
    public int logicalDeleteByDocument(Long documentId) {
        Chunk patch = new Chunk();
        patch.setDeleted(1);
        patch.setUpdateTime(LocalDateTime.now());
        return chunkMapper.update(patch, new LambdaUpdateWrapper<Chunk>()
                .eq(Chunk::getDocumentId, documentId)
                .eq(Chunk::getDeleted, 0));
    }
}
