package com.xiaohu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaohu.ai.dto.SearchHitVO;
import com.xiaohu.ai.entity.Chunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChunkMapper extends BaseMapper<Chunk> {

    /**
     * 关键词召回：MySQL ngram 全文索引，权限与向量侧保持一致。
     * 只返回已成功入库（chunk/document 均 SUCCESS）且未删除的切片。
     */
    @Select("""
            <script>
            SELECT c.document_id AS documentId,
                   c.chunk_index AS chunkIndex,
                   c.content     AS content,
                   d.file_name   AS fileName,
                   MATCH(c.content) AGAINST(#{q} IN NATURAL LANGUAGE MODE) AS score
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            WHERE c.deleted = 0 AND c.status = 'SUCCESS'
              AND d.deleted = 0 AND d.status = 'SUCCESS'
              AND MATCH(c.content) AGAINST(#{q} IN NATURAL LANGUAGE MODE) > 0
            <if test="!admin">
              AND d.uploader_id = #{userId}
            </if>
            ORDER BY score DESC
            LIMIT #{limit}
            </script>
            """)
    List<SearchHitVO> keywordSearch(@Param("q") String q,
                                    @Param("limit") int limit,
                                    @Param("userId") Long userId,
                                    @Param("admin") boolean admin);
}
