package com.xiaohu.ai.service.parser;

import org.apache.pdfbox.Loader;                          // Loader.loadPDF 用
import org.apache.pdfbox.pdmodel.PDDocument;              // PDDocument 类型

import org.apache.pdfbox.text.PDFTextStripper;            // 抽文字工具
import org.springframework.stereotype.Component;


import java.io.File;
@Component
public class PdfParser implements DocumentParser {
    @Override
    public String parse(File file) {
        try (PDDocument pdf = Loader.loadPDF(file)){
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(pdf);

        }catch (Exception e){
            return  "解析失败";

        }

    }

    @Override
    public boolean supports(String ext) {
        return "pdf".equals(ext);
    }
}
