package com.xiaohu.ai.service.parser;

import java.io.File;
public interface DocumentParser {
    /**
     * 把文件解析成纯文本
     */
    String parse(File file);

    /**
     * 是否支持这个后缀（例如传 "pdf" 就返回 true）
     */
    boolean supports(String ext);
}