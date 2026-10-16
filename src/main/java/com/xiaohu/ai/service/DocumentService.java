package com.xiaohu.ai.service;

import com.xiaohu.ai.service.parser.DocumentParser;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析服务：把已保存到磁盘的文件解析成纯文本。
 * 目前先支持 txt / md，pdf、docx 后续补上。
 */
@Service
public class DocumentService {

    /**
     * 根据文件后缀，分发到对应的解析方法
     *
     * @param file 已保存到磁盘的文档文件
     * @return 文件的纯文本内容
     */

    private final List<DocumentParser> parsers;

    public DocumentService(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public String parse(File file) {
        String ext = getExtension(file.getName());
        for (DocumentParser p : parsers) {
            if (p.supports(ext)) return p.parse(file);
        }
        return "暂不支持的文件类型：" + ext;
    }

    // 从文件名里取后缀（去掉点，统一转小写）
    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        // 没有点（比如文件名是 "abc"）就返回空字符串
        return (dot >= 0) ? filename.substring(dot + 1).toLowerCase() : "";
    }

    // 解析纯文本文件：直接按 UTF-8 读整个文件
    private String parseTxt(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取失败：" + e.getMessage();
        }
    }
}