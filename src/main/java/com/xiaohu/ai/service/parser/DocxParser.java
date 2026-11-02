package com.xiaohu.ai.service.parser;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;

@Component
public class DocxParser implements DocumentParser {


    @Override
    public String parse(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)){
            return new XWPFWordExtractor(document).getText();

        }catch (Exception e){
            throw new RuntimeException("解析失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String ext) {
        return "docx".equals(ext);
    }
}
