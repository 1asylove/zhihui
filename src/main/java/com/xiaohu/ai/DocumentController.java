package com.xiaohu.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.common.Result;
import com.xiaohu.ai.dto.DocumentVO;
import com.xiaohu.ai.entity.Document;
import com.xiaohu.ai.entity.User;
import com.xiaohu.ai.mapper.UserMapper;
import com.xiaohu.ai.service.DocumentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    // 从配置文件读上传目录（绝对路径）
    @Value("${upload.dir}")
    private String uploadDir;

    private final DocumentService documentService;
    private final UserMapper userMapper;

    public DocumentController(DocumentService documentService, UserMapper userMapper) {
        this.documentService = documentService;
        this.userMapper = userMapper;
    }

    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('doc:upload')")
    public Result<DocumentVO> upload(@RequestParam("file") MultipartFile file) {
        // 1. 空文件直接拒，不落盘不落库
        if (file.isEmpty()) {
            return Result.error(400, "文件为空");
        }

        // 2. 清洗原始文件名（浏览器可能带 C:\fakepath\ 前缀，也可能为 null）
        String original = StringUtils.getFilename(file.getOriginalFilename());
        if (original == null || original.isBlank()) {
            return Result.error(400, "文件名无效");
        }

        // 3. 类型白名单由解析器决定
        String ext = documentService.getExtension(original);
        if (!documentService.isSupported(ext)) {
            return Result.error(400, "暂不支持的文件类型：" + ext);
        }

        // 4. 从 SecurityContext 取当前登录用户名，再查出 id
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            return Result.error(500, "当前用户不存在");
        }

        // 5. 落盘：物理文件名用 UUID，库里保留原始文件名
        Path dir = Paths.get(uploadDir);
        Path dest;
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            String stored = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            dest = dir.resolve(stored);
            file.transferTo(dest.toFile());
        } catch (IOException e) {
            return Result.error(500, "文件保存失败：" + e.getMessage());
        }

        // 6. 落库：PENDING → PARSING
        Document doc = documentService.savePending(
                original, dest.toString(), file.getSize(), ext, user.getId());
        Long id = doc.getId();
        documentService.markParsing(id);

        // 7. 解析：成功写 SUCCESS，失败写 FAILED，接口都返回 200
        try {
            String text = documentService.parse(dest.toFile());
            documentService.markSuccess(id, text);
        } catch (Exception e) {
            documentService.markFailed(id, e.getMessage());
        }

        return Result.success(toVO(documentService.getById(id)));
    }

    // 暂时保留按文件名的删除实现，Step 4 再改为按 id 逻辑删除
    @DeleteMapping("/{filename}")
    @PreAuthorize("hasAuthority('doc:delete')")
    public String delete(@PathVariable String filename) throws Exception {
        Path path = Paths.get(uploadDir).resolve(filename);
        if (Files.deleteIfExists(path)) {
            return "已删除：" + filename;
        }
        return "文件不存在：" + filename;
    }

    // 实体 → VO：列表视图不暴露正文
    private DocumentVO toVO(Document d) {
        DocumentVO vo = new DocumentVO();
        vo.setId(d.getId());
        vo.setFileName(d.getFileName());
        vo.setFileType(d.getFileType());
        vo.setFileSize(d.getFileSize());
        vo.setStatus(d.getStatus());
        vo.setErrorMsg(d.getErrorMsg());
        vo.setCreateTime(d.getCreateTime());
        return vo;
    }
}
