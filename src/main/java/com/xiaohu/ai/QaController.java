package com.xiaohu.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.common.Result;
import com.xiaohu.ai.dto.QaRequest;
import com.xiaohu.ai.dto.QaResponse;
import com.xiaohu.ai.entity.User;
import com.xiaohu.ai.mapper.UserMapper;
import com.xiaohu.ai.service.QaService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 问答接口：M4 Step2，非流式。
 * 契约见 contracts/qa.openapi.yaml。
 */
@RestController
public class QaController {

    private final QaService qaService;
    private final UserMapper userMapper;

    public QaController(QaService qaService, UserMapper userMapper) {
        this.qaService = qaService;
        this.userMapper = userMapper;
    }

    @PostMapping("/qa/ask")
    @PreAuthorize("hasAuthority('chat:ask')")
    public Result<QaResponse> ask(@RequestBody(required = false) QaRequest req) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        User user = currentUser();
        return Result.success(
                qaService.ask(req.getQuestion(), req.getTopK(), user.getId(), isAdmin()));
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
