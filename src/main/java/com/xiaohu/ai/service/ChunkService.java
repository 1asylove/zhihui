package com.xiaohu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.common.ChunkStatus;
import com.xiaohu.ai.entity.Chunk;
import com.xiaohu.ai.entity.Document;
import com.xiaohu.ai.mapper.ChunkMapper;
import com.xiaohu.ai.service.chunk.TextSplitter;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ChunkService {

    private final ChunkMapper chunkMapper;
    private final TextSplitter textSplitter;

    public ChunkService(ChunkMapper chunkMapper, TextSplitter textSplitter) {
        this.chunkMapper = chunkMapper;
        this.textSplitter = textSplitter;
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
}
