package com.xiaohu.ai;

import com.xiaohu.ai.service.DocumentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    // 从配置文件读上传目录（绝对路径）
    @Value("${upload.dir}")
    private String uploadDir;

    private final DocumentService documentService;

    // 构造器注入（和 AuthController 保持一致的风格）
    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws Exception {
        // 1. 检查是不是空文件
        if (file.isEmpty()) {
            return "文件为空";
        }

        // 2. 准备保存目录（绝对路径，不存在就创建）
        Path dir = Paths.get(uploadDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        // 3. 拿到原始文件名，拼出保存路径
        String filename = file.getOriginalFilename();
        File dest = dir.resolve(filename).toFile();

        // 4. 把收到的文件转存到磁盘
        file.transferTo(dest);

        // 5. 上传成功后，解析成纯文本返回
        String text = documentService.parse(dest);
        return "上传成功：" + filename + "\n解析结果：\n" + text;
    }
}