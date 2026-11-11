package com.xiaohu.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.common.Result;
import com.xiaohu.ai.dto.MessageVO;
import com.xiaohu.ai.dto.SessionVO;
import com.xiaohu.ai.entity.User;
import com.xiaohu.ai.mapper.UserMapper;
import com.xiaohu.ai.service.ChatHistoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话管理：列表 / 消息历史 / 逻辑删除。
 * 契约见 contracts/qa-session.openapi.yaml；会话都由 /qa/ask、/qa/stream 创建。
 */
@RestController
@RequestMapping("/sessions")
public class SessionController {

    private final ChatHistoryService chatHistoryService;
    private final UserMapper userMapper;

    public SessionController(ChatHistoryService chatHistoryService, UserMapper userMapper) {
        this.chatHistoryService = chatHistoryService;
        this.userMapper = userMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('chat:ask')")
    public Result<List<SessionVO>> list() {
        return Result.success(chatHistoryService.listSessions(currentUser().getId()));
    }

    @GetMapping("/{id}/messages")
    @PreAuthorize("hasAuthority('chat:ask')")
    public Result<List<MessageVO>> messages(@PathVariable Long id) {
        return Result.success(chatHistoryService.listMessages(id, currentUser().getId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('chat:ask')")
    public Result<Void> delete(@PathVariable Long id) {
        chatHistoryService.deleteSession(id, currentUser().getId());
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
}
