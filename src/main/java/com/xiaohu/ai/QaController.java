package com.xiaohu.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.common.Result;
import com.xiaohu.ai.dto.QaRequest;
import com.xiaohu.ai.dto.QaResponse;
import com.xiaohu.ai.entity.User;
import com.xiaohu.ai.mapper.UserMapper;
import com.xiaohu.ai.service.QaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * RAG 问答接口：M4 Step2 非流式、Step3 流式、Step4 多轮会话。
 * 契约见 contracts/qa.openapi.yaml、contracts/qa-stream.openapi.yaml。
 */
@RestController
public class QaController {

    private static final Logger log = LoggerFactory.getLogger(QaController.class);

    private final QaService qaService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public QaController(QaService qaService, UserMapper userMapper, ObjectMapper objectMapper) {
        this.qaService = qaService;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/qa/ask")
    @PreAuthorize("hasAuthority('chat:ask')")
    public Result<QaResponse> ask(@RequestBody(required = false) QaRequest req) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        User user = currentUser();
        return Result.success(qaService.ask(req.getQuestion(), req.getTopK(), user.getId(),
                isAdmin(), req.getSessionId(), !Boolean.FALSE.equals(req.getPersist())));
    }

    /**
     * 流式问答：meta（会话 + 引用）→ delta（增量文本）→ done；异常走 error 事件。
     * 契约见 contracts/qa-stream.openapi.yaml。
     */
    @PostMapping(value = "/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('chat:ask')")
    public Flux<ServerSentEvent<String>> stream(@RequestBody(required = false) QaRequest req)
            throws Exception {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        User user = currentUser();

        // 校验 + 会话归属 + 检索 + 落 user 消息 + Prompt 组装仍在建流之前完成
        QaService.QaPrompt ctx = qaService.prepare(req.getQuestion(), req.getTopK(),
                user.getId(), isAdmin(), req.getSessionId());

        ServerSentEvent<String> meta = event("meta", objectMapper.writeValueAsString(
                Map.of("sessionId", ctx.sessionId(), "citations", ctx.citations())));

        if (ctx.empty()) {
            qaService.saveAssistantMessage(ctx.sessionId(), QaService.NO_HIT_ANSWER, List.of());
            return Flux.just(meta,
                    event("delta", QaService.NO_HIT_ANSWER),
                    event("done", "[DONE]"));
        }

        StringBuilder answer = new StringBuilder();
        Flux<ServerSentEvent<String>> deltas = qaService.streamAnswer(ctx.userPrompt())
                .doOnNext(answer::append)
                .map(token -> event("delta", token))
                // 只有正常结束才落 assistant 消息；error 分支不会触发 onComplete
                .doOnComplete(() -> qaService.saveAssistantMessage(
                        ctx.sessionId(), answer.toString(), ctx.citations()))
                .onErrorResume(e -> {
                    log.warn("流式生成失败：{}", e.getMessage());
                    return Flux.just(event("error", "{\"message\":\"模型调用失败，请稍后重试\"}"));
                });

        return Flux.concat(Flux.just(meta), deltas, Flux.just(event("done", "[DONE]")));
    }

    private static ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
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
