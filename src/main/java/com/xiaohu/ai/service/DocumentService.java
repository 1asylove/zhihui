package com.xiaohu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.common.DocumentStatus;
import com.xiaohu.ai.common.PageResult;
import com.xiaohu.ai.dto.DocumentDetailVO;
import com.xiaohu.ai.dto.DocumentVO;
import com.xiaohu.ai.entity.Document;
import com.xiaohu.ai.mapper.DocumentMapper;
import com.xiaohu.ai.service.parser.DocumentParser;
import org.springframework.stereotype.Service;


import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档服务：解析分发 + 入库状态流转。
 */
@Service
public class DocumentService {

    private final List<DocumentParser> parsers;
    private final DocumentMapper documentMapper;

    public DocumentService(List<DocumentParser> parsers, DocumentMapper documentMapper) {
        this.parsers = parsers;
        this.documentMapper = documentMapper;
    }

    /**
     * 根据文件后缀分发到对应的解析器；解析失败时抛异常，由调用方落 FAILED。
     */
    public String parse(File file) {
        String ext = getExtension(file.getName());
        for (DocumentParser p : parsers) {
            if (p.supports(ext)) {
                return p.parse(file);
            }
        }
        throw new IllegalArgumentException("暂不支持的文件类型：" + ext);
    }

    /**
     * 用解析器自身能力判断是否支持该后缀，避免在 Controller 里硬编码白名单。
     */
    public boolean isSupported(String ext) {
        return parsers.stream().anyMatch(p -> p.supports(ext));
    }

    /**
     * 取文件后缀（不含点，统一小写）。
     */
    public String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot >= 0) ? filename.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * 落盘成功后插入一条 PENDING 记录，insert 后自增 id 会回填到对象上。
     */
    public Document savePending(String fileName, String filePath, Long fileSize,
                                String fileType, Long uploaderId) {
        LocalDateTime now = LocalDateTime.now();
        Document doc = new Document();
        doc.setFileName(fileName);
        doc.setFilePath(filePath);
        doc.setFileSize(fileSize);
        doc.setFileType(fileType);
        doc.setStatus(DocumentStatus.PENDING);
        doc.setUploaderId(uploaderId);
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        doc.setDeleted(0);
        documentMapper.insert(doc);
        return doc;
    }

    public void markParsing(Long id) {
        updateStatus(id, DocumentStatus.PARSING);
    }

    public void markSuccess(Long id, String content) {
        Document doc = new Document();
        doc.setId(id);
        doc.setContent(content);
        doc.setStatus(DocumentStatus.SUCCESS);
        doc.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(doc);
    }

    public void markFailed(Long id, String errorMsg) {
        String msg = (errorMsg == null || errorMsg.isBlank()) ? "解析失败" : errorMsg;
        if (msg.length() > 500) {
            msg = msg.substring(0, 500);
        }
        Document doc = new Document();
        doc.setId(id);
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorMsg(msg);
        doc.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(doc);
    }

    public Document getById(Long id) {
        return documentMapper.selectById(id);
    }

    /**
     * 只更新状态与更新时间，其余字段保持 null 由 MP 的 NOT_NULL 策略跳过。
     */
    private void updateStatus(Long id, String status) {
        Document doc = new Document();
        doc.setId(id);
        doc.setStatus(status);
        doc.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(doc);
    }

    /** 分页查询：uploaderId 为 null 表示管理员看全量 */
    public PageResult<DocumentVO> page(int page, int size, Long uploaderId) {
        Page<Document> p = new Page<>(page, size);
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                // 列裁剪：不查 content / file_path / update_time
                .select(Document::getId, Document::getFileName, Document::getFileType,
                        Document::getFileSize, Document::getStatus, Document::getErrorMsg,
                        Document::getCreateTime)
                .eq(Document::getDeleted, 0)
                // 条件生效重载：uploaderId 为 null 时该条件不参与 SQL
                .eq(uploaderId != null, Document::getUploaderId, uploaderId)
                .orderByDesc(Document::getCreateTime)
                .orderByDesc(Document::getId);   // 时间相同再按 id 兜底，保证翻页稳定

        documentMapper.selectPage(p, wrapper);

        List<DocumentVO> records = p.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    /** 详情：不存在 / 已删除 / 不可见 一律 404 */
    public DocumentDetailVO detail(Long id, Long uploaderId) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getId, id)
                .eq(Document::getDeleted, 0)
                .eq(uploaderId != null, Document::getUploaderId, uploaderId);

        Document d = documentMapper.selectOne(wrapper);
        if (d == null) {
            throw new BizException(404, "文档不存在或已删除");
        }
        return toDetailVO(d);
    }

    /** 是否存在未删除的文档（删除链路第一段判断） */
    public boolean existsActive(Long id) {
        return documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getId, id)
                .eq(Document::getDeleted, 0)) > 0;
    }

    /** 逻辑删除：返回 false 表示记录不存在或已删过 */
    public boolean logicalDelete(Long id) {
        Document patch = new Document();
        patch.setDeleted(1);
        patch.setUpdateTime(LocalDateTime.now());

        return documentMapper.update(patch, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, id)
                .eq(Document::getDeleted, 0)) > 0;
    }

    // 两个 VO 共享一套字段赋值，避免 setter 复制两遍
    private void fill(DocumentVO vo, Document d) {
        vo.setId(d.getId());
        vo.setFileName(d.getFileName());
        vo.setFileType(d.getFileType());
        vo.setFileSize(d.getFileSize());
        vo.setStatus(d.getStatus());
        vo.setErrorMsg(d.getErrorMsg());
        vo.setCreateTime(d.getCreateTime());
    }

    public DocumentVO toVO(Document d) {
        DocumentVO vo = new DocumentVO();
        fill(vo, d);
        return vo;
    }

    private DocumentDetailVO toDetailVO(Document d) {
        DocumentDetailVO vo = new DocumentDetailVO();
        fill(vo, d);
        vo.setContent(d.getContent());
        return vo;
    }
}
