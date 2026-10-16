package com.xiaohu.ai.service.parser;

import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Component
public class TxtParser implements DocumentParser {

    @Override
    public String parse(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取失败：" + e.getMessage();
        }
    }

    @Override
    public boolean supports(String ext) {
        return "txt".equals(ext) || "md".equals(ext);
    }
}