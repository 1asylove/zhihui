package com.xiaohu.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.common.PageResult;
import com.xiaohu.ai.common.Result;
import com.xiaohu.ai.dto.DocumentDetailVO;
import com.xiaohu.ai.dto.DocumentVO;
import com.xiaohu.ai.entity.Document;
import com.xiaohu.ai.entity.User;
import com.xiaohu.ai.mapper.UserMapper;
import com.xiaohu.ai.service.ChunkService;
import com.xiaohu.ai.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@RestController
@RequestMapping("/documents")
public class DocumentController {

  
    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    // 从配置文件读上传目录（绝对路径）
    @Value("${upload.dir}")
    private String uploadDir;

    private final DocumentService documentService;
    private final UserMapper userMapper;
    private final ChunkService chunkService;

    public DocumentController(DocumentService documentService, UserMapper userMapper,
                              ChunkService chunkService) {
        this.documentService = documentService;
        this.userMapper = userMapper;
        this.chunkService = chunkService;
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

        // 8. 切片落库（仅切片，向量化在 Step 3）；失败不影响上传结果
        try {
            int count = chunkService.sliceAndSave(documentService.getById(id));
            log.info("文档 {} 切片完成，共 {} 片", id, count);
        } catch (Exception e) {
            log.warn("文档 {} 切片失败：{}", id, e.getMessage());
        }

        return Result.success(documentService.toVO(documentService.getById(id)));
    }

    @GetMapping
    public Result<PageResult<DocumentVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        // 契约要求 page >= 1、1 <= size <= 100，越界直接夹紧而不报错
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);

        User user = currentUser();
        Long uploaderId = isAdmin() ? null : user.getId();

        return Result.success(documentService.page(safePage, safeSize, uploaderId));
    }

    @GetMapping("/{id}")
    public Result<DocumentDetailVO> detail(@PathVariable Long id) {
        User user = currentUser();
        Long uploaderId = isAdmin() ? null : user.getId();

        return Result.success(documentService.detail(id, uploaderId));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('doc:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        if (!documentService.logicalDelete(id)) {
            throw new BizException(404, "文档不存在或已删除");
        }
        return Result.success("删除成功", null);
    }

    /** 当前登录用户实体（JWT 只存用户名，需要回库拿 id） */
    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BizException(500, "当前用户不存在");
        }
        return user;
    }

    /** 角色判断：权限里装的是 ROLE_ADMIN，不是 ADMIN */
    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
