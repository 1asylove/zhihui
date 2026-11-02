package com.xiaohu.ai.service;

import com.xiaohu.ai.common.DocumentStatus;
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
}
