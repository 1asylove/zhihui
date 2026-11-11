package com.xiaohu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.dto.MessageVO;
import com.xiaohu.ai.dto.SearchHitVO;
import com.xiaohu.ai.dto.SessionVO;
import com.xiaohu.ai.entity.ChatMessage;
import com.xiaohu.ai.entity.ChatSession;
import com.xiaohu.ai.mapper.ChatMessageMapper;
import com.xiaohu.ai.mapper.ChatSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话与消息：建会话、属主校验、取最近历史、追加消息、列表/详情/逻辑删。
 * 规格见 specs/qa/multi-turn.md。
 */
@Service
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

    private static final String HISTORY_HEADER = "【历史对话】\n";
    private static final String ROLE_USER = "user";
    private static final int TITLE_MAX_LEN = 30;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final int maxRounds;
    private final int maxChars;

    public ChatHistoryService(ChatSessionMapper sessionMapper,
                              ChatMessageMapper messageMapper,
                              ObjectMapper objectMapper,
                              @Value("${chat.history.max-rounds:3}") int maxRounds,
                              @Value("${chat.history.max-chars:1200}") int maxChars) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.maxRounds = maxRounds;
        this.maxChars = maxChars;
    }

    /**
     * 指定 sessionId 时校验属主（越权/已删一律 404）；未指定则新建会话。
     */
    public Long resolveSession(Long sessionId, Long userId, String question) {
        if (sessionId == null) {
            return createSession(userId, question);
        }
        requireOwned(sessionId, userId);
        return sessionId;
    }

    public Long createSession(Long userId, String question) {
        LocalDateTime now = LocalDateTime.now();
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(titleOf(question));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setDeleted(0);
        sessionMapper.insert(session);
        log.info("新建会话：id={} userId={} title={}", session.getId(), userId, session.getTitle());
        return session.getId();
    }

    /**
     * 最近 maxRounds 轮（每轮 user + assistant）消息，按 id 升序返回。
     * 从新到旧累计字符，单条放不下就停止，保证总长不超 maxChars。
     */
    public List<ChatMessage> recentMessages(Long sessionId) {
        int limit = maxRounds * 2;
        if (limit <= 0) {
            return List.of();
        }
        Page<ChatMessage> page = new Page<>(1, limit, false);
        List<ChatMessage> desc = messageMapper.selectPage(page,
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .eq(ChatMessage::getDeleted, 0)
                        .orderByDesc(ChatMessage::getId)).getRecords();

        List<ChatMessage> kept = new ArrayList<>(desc.size());
        int budget = maxChars;
        for (int i = desc.size() - 1; i >= 0; i--) {
            ChatMessage m = desc.get(i);
            int cost = (m.getContent() == null ? 0 : m.getContent().length()) + 6;
            if (cost > budget) {
                break;
            }
            kept.add(m);
            budget -= cost;
        }
        Collections.reverse(kept);
        return kept;
    }

    /** 拼成【历史对话】段；无历史返回空串，调用方据此跳过 */
    public String formatHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(HISTORY_HEADER);
        for (ChatMessage m : messages) {
            sb.append(ROLE_USER.equals(m.getRole()) ? "用户：" : "助手：")
                    .append(m.getContent()).append('\n');
        }
        return sb.toString();
    }

    public void appendUserMessage(Long sessionId, String content) {
        appendMessage(sessionId, ROLE_USER, content, null);
    }

    public void appendAssistantMessage(Long sessionId, String content,
                                       List<SearchHitVO> citations) {
        String json;
        try {
            json = objectMapper.writeValueAsString(citations == null ? List.of() : citations);
        } catch (Exception e) {
            log.warn("引用序列化失败，按空数组处理：{}", e.getMessage());
            json = "[]";
        }
        appendMessage(sessionId, "assistant", content, json);
    }

    public List<SessionVO> listSessions(Long userId) {
        List<ChatSession> list = sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getDeleted, 0)
                .orderByDesc(ChatSession::getUpdatedAt)
                .orderByDesc(ChatSession::getId));

        List<SessionVO> vos = new ArrayList<>(list.size());
        for (ChatSession s : list) {
            SessionVO vo = new SessionVO();
            vo.setId(s.getId());
            vo.setTitle(s.getTitle());
            vo.setCreatedAt(s.getCreatedAt());
            vo.setUpdatedAt(s.getUpdatedAt());
            vos.add(vo);
        }
        return vos;
    }

    public List<MessageVO> listMessages(Long sessionId, Long userId) {
        requireOwned(sessionId, userId);

        List<ChatMessage> list = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getDeleted, 0)
                .orderByAsc(ChatMessage::getId));

        List<MessageVO> vos = new ArrayList<>(list.size());
        for (ChatMessage m : list) {
            MessageVO vo = new MessageVO();
            vo.setId(m.getId());
            vo.setRole(m.getRole());
            vo.setContent(m.getContent());
            vo.setCitations(parseCitations(m.getCitationsJson()));
            vo.setCreatedAt(m.getCreatedAt());
            vos.add(vo);
        }
        return vos;
    }

    /** 会话与消息一并逻辑删；重复删除 / 越权返回 404 */
    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        requireOwned(sessionId, userId);

        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getDeleted, 0)
                .set(ChatSession::getDeleted, 1)
                .set(ChatSession::getUpdatedAt, LocalDateTime.now()));

        messageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getDeleted, 0)
                .set(ChatMessage::getDeleted, 1));

        log.info("会话 {} 已逻辑删除，userId={}", sessionId, userId);
    }

    /** 会话必须存在、未删除且属于当前用户，否则一律 404（不暴露存在性） */
    private void requireOwned(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getDeleted, 0));
        if (session == null) {
            throw new BizException(404, "会话不存在或已删除");
        }
    }

    private void appendMessage(Long sessionId, String role, String content, String citationsJson) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setCitationsJson(citationsJson);
        m.setCreatedAt(LocalDateTime.now());
        m.setDeleted(0);
        messageMapper.insert(m);

        // 刷新会话活跃时间，列表按 updated_at 排序
        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getUpdatedAt, LocalDateTime.now()));
    }

    private List<SearchHitVO> parseCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<SearchHitVO>>() { });
        } catch (Exception e) {
            log.warn("引用 JSON 解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 去空白后取前 30 字做标题 */
    private String titleOf(String question) {
        String t = question == null ? "" : question.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            return "新会话";
        }
        return t.length() <= TITLE_MAX_LEN ? t : t.substring(0, TITLE_MAX_LEN);
    }
}
