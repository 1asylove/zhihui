package com.xiaohu.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.common.Result;
import com.xiaohu.ai.dto.AgentChatRequest;
import com.xiaohu.ai.dto.AgentChatResponse;
import com.xiaohu.ai.entity.User;
import com.xiaohu.ai.mapper.UserMapper;
import com.xiaohu.ai.service.AgentChatService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * M5 Agent 统一入口：会话/历史/落库在 Java，编排在 Python。
 * 契约见 contracts/agent.openapi.yaml。
 */
@RestController
public class AgentController {

    private final AgentChatService agentChatService;
    private final UserMapper userMapper;

    public AgentController(AgentChatService agentChatService, UserMapper userMapper) {
        this.agentChatService = agentChatService;
        this.userMapper = userMapper;
    }

    @PostMapping("/agent/chat")
    @PreAuthorize("hasAuthority('chat:ask')")
    public Result<AgentChatResponse> chat(
            @RequestBody(required = false) AgentChatRequest req,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(401, "缺少或无效的 Authorization");
        }
        User user = currentUser();
        return Result.success(agentChatService.chat(req, user.getId(), isAdmin(), authorization));
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
