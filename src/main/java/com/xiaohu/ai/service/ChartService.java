package com.xiaohu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaohu.ai.common.DocumentStatus;
import com.xiaohu.ai.dto.ChartOverviewVO;
import com.xiaohu.ai.dto.DateCountVO;
import com.xiaohu.ai.dto.NameCountVO;
import com.xiaohu.ai.entity.ChatSession;
import com.xiaohu.ai.entity.Chunk;
import com.xiaohu.ai.entity.Document;
import com.xiaohu.ai.mapper.ChatSessionMapper;
import com.xiaohu.ai.mapper.ChunkMapper;
import com.xiaohu.ai.mapper.DocumentMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页看板统计：对现有 document / chunk / chat_session 表做实时聚合，不建冗余统计表。
 * 口径沿用文档可见性：uploaderId 为 null（ADMIN）统计全量，否则只统计自己的。
 */
@Service
public class ChartService {

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final ChatSessionMapper chatSessionMapper;

    public ChartService(DocumentMapper documentMapper, ChunkMapper chunkMapper,
                        ChatSessionMapper chatSessionMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.chatSessionMapper = chatSessionMapper;
    }

    public ChartOverviewVO overview(Long uploaderId) {
        ChartOverviewVO vo = new ChartOverviewVO();
        vo.setTotalDocuments(countDocuments(uploaderId, null));
        vo.setSuccessDocuments(countDocuments(uploaderId, DocumentStatus.SUCCESS));
        vo.setFailedDocuments(countDocuments(uploaderId, DocumentStatus.FAILED));
        vo.setProcessingDocuments(countProcessing(uploaderId));
        vo.setChunkCount(countChunks(uploaderId));
        vo.setSessionCount(countSessions(uploaderId));
        vo.setTypeDistribution(typeDistribution(uploaderId));
        vo.setRecentUploads(recentUploads(uploaderId));
        return vo;
    }

    /** status 为 null 表示全部状态 */
    private long countDocuments(Long uploaderId, String status) {
        return documentMapper.selectCount(new QueryWrapper<Document>()
                .eq("deleted", 0)
                .eq(uploaderId != null, "uploader_id", uploaderId)
                .eq(status != null, "status", status));
    }

    private long countProcessing(Long uploaderId) {
        return documentMapper.selectCount(new QueryWrapper<Document>()
                .eq("deleted", 0)
                .eq(uploaderId != null, "uploader_id", uploaderId)
                .in("status", DocumentStatus.PENDING, DocumentStatus.PARSING));
    }

    /** 切片数：只统计未删除文档下的未删除切片；USER 再加 uploader 过滤 */
    private long countChunks(Long uploaderId) {
        String docFilter = uploaderId == null
                ? "SELECT id FROM document WHERE deleted = 0"
                : "SELECT id FROM document WHERE deleted = 0 AND uploader_id = " + uploaderId;
        return chunkMapper.selectCount(new QueryWrapper<Chunk>()
                .eq("deleted", 0)
                .inSql("document_id", docFilter));
    }

    private long countSessions(Long uploaderId) {
        return chatSessionMapper.selectCount(new QueryWrapper<ChatSession>()
                .eq("deleted", 0)
                .eq(uploaderId != null, "user_id", uploaderId));
    }

    private List<NameCountVO> typeDistribution(Long uploaderId) {
        QueryWrapper<Document> qw = new QueryWrapper<Document>()
                .select("file_type AS name", "COUNT(*) AS count")
                .eq("deleted", 0)
                .eq(uploaderId != null, "uploader_id", uploaderId)
                .groupBy("file_type")
                .orderByDesc("count");
        return documentMapper.selectMaps(qw).stream()
                .map(row -> new NameCountVO(
                        String.valueOf(row.get("name")),
                        ((Number) row.get("count")).longValue()))
                .toList();
    }

    /** 近 7 天上传数：DB 只返回有数据的日期，Java 侧补零成连续 7 天 */
    private List<DateCountVO> recentUploads(Long uploaderId) {
        LocalDate start = LocalDate.now().minusDays(6);
        QueryWrapper<Document> qw = new QueryWrapper<Document>()
                .select("DATE(create_time) AS date", "COUNT(*) AS count")
                .eq("deleted", 0)
                .eq(uploaderId != null, "uploader_id", uploaderId)
                .ge("create_time", start.atStartOfDay())
                .groupBy("DATE(create_time)");

        Map<String, Long> byDay = new HashMap<>();
        for (Map<String, Object> row : documentMapper.selectMaps(qw)) {
            byDay.put(String.valueOf(row.get("date")), ((Number) row.get("count")).longValue());
        }

        List<DateCountVO> list = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String day = start.plusDays(i).toString();
            list.add(new DateCountVO(day, byDay.getOrDefault(day, 0L)));
        }
        return list;
    }
}
