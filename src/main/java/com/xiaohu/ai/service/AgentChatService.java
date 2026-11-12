package com.xiaohu.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.dto.AgentChatRequest;
import com.xiaohu.ai.dto.AgentChatResponse;
import com.xiaohu.ai.dto.QaResponse;
import com.xiaohu.ai.dto.SearchHitVO;
import com.xiaohu.ai.dto.ToolUsage;
import com.xiaohu.ai.entity.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Agent 网关：会话/历史/落库都在这里，Python 只负责编排。
 * Python 不可用（连接失败/超时/5xx）时降级为本地 /qa/ask 流程。
 * 规格见 specs/agent/agent-chat.md。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private static final Set<String> MODES = Set.of("vector", "keyword", "hybrid");
    private static final String ROUTE_FALLBACK = "fallback";

    private final ChatHistoryService chatHistoryService;
    private final QaService qaService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final boolean enabled;
    private final int maxQuestionLength;

    public AgentChatService(ChatHistoryService chatHistoryService,
                            QaService qaService,
                            ObjectMapper objectMapper,
                            @Value("${agent.base-url:http://127.0.0.1:8100}") String baseUrl,
                            @Value("${agent.timeout-ms:20000}") int timeoutMs,
                            @Value("${agent.enabled:true}") boolean enabled,
                            @Value("${search.max-query-length:500}") int maxQuestionLength) {
        this.chatHistoryService = chatHistoryService;
        this.qaService = qaService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxQuestionLength = maxQuestionLength;

        // 读超时与 Python 图的总超时对齐：Python 自己会在 20s 掐断，Java 稍晚一点收尾
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public AgentChatResponse chat(AgentChatRequest req, Long userId, boolean admin,
                                  String authorization) {
        long begin = System.nanoTime();

        String question = req.getQuestion() == null ? "" : req.getQuestion().trim();
        if (question.isEmpty()) {
            throw new BizException(400, "question 不能为空");
        }
        if (question.length() > maxQuestionLength) {
            throw new BizException(400, "question 过长，最多 " + maxQuestionLength + " 字");
        }
        if (req.getMode() != null && !MODES.contains(req.getMode())) {
            throw new BizException(400, "mode 只能是 vector / keyword / hybrid");
        }

        // 越权/已删会话在建流前就 404，不调用 Python
        Long sessionId = chatHistoryService.resolveSession(req.getSessionId(), userId, question);
        List<ChatMessage> history = chatHistoryService.recentMessages(sessionId);
        chatHistoryService.appendUserMessage(sessionId, question);

        AgentChatResponse resp;
        if (!enabled) {
            log.info("agent.enabled=false，直接降级：sessionId={}", sessionId);
            resp = degrade(question, req.getTopK(), userId, admin, sessionId);
        } else {
            try {
                InternalAgentResponse py = callPython(req, question, sessionId, userId, admin,
                        authorization, history);
                resp = new AgentChatResponse();
                resp.setAnswer(py.getAnswer());
                resp.setCitations(py.getCitations() == null ? List.of() : py.getCitations());
                resp.setRoute(py.getRoute());
                resp.setToolsUsed(py.getToolsUsed() == null ? List.of() : py.getToolsUsed());
                resp.setDegraded(false);
                chatHistoryService.appendAssistantMessage(sessionId, py.getAnswer(), resp.getCitations());
            } catch (PythonUnavailableException e) {
                log.warn("Python Agent 不可用，降级到本地问答：sessionId={} 原因={}", sessionId, e.getMessage());
                resp = degrade(question, req.getTopK(), userId, admin, sessionId);
            }
        }

        resp.setSessionId(sessionId);
        resp.setQuestion(question);
        resp.setTookMs((System.nanoTime() - begin) / 1_000_000);
        log.info("Agent 完成：sessionId={} route={} degraded={} 工具={} 引用={} 耗时={}ms" ,
                sessionId, resp.getRoute(), resp.getDegraded(),
                resp.getToolsUsed().size(), resp.getCitations().size(), resp.getTookMs());
        return resp;
    }

    /** 降级：复用本地 RAG（persist=false，网关已经落过 user 消息，避免重复） */
    private AgentChatResponse degrade(String question, Integer topK, Long userId, boolean admin,
                                      Long sessionId) {
        QaResponse qa = qaService.ask(question, topK, userId, admin, sessionId, false);
        chatHistoryService.appendAssistantMessage(sessionId, qa.getAnswer(), qa.getCitations());

        AgentChatResponse resp = new AgentChatResponse();
        resp.setAnswer(qa.getAnswer());
        resp.setCitations(qa.getCitations());
        resp.setRoute(ROUTE_FALLBACK);
        resp.setToolsUsed(List.of());
        resp.setDegraded(true);
        return resp;
    }

    /** 调用 Python Agent；4xx 视为实现缺陷直接 500，连接/超时/5xx 触发降级 */
    private InternalAgentResponse callPython(AgentChatRequest req, String question, Long sessionId,
                                             Long userId, boolean admin, String authorization,
                                             List<ChatMessage> history) {
        InternalAgentRequest payload = new InternalAgentRequest();
        payload.setSessionId(sessionId);
        payload.setQuestion(question);
        payload.setUserId(userId);
        payload.setAdmin(admin);
        // Python 侧 topK 是必填 int，未传时补默认值，避免 null 触发 422
        payload.setTopK(req.getTopK() == null ? 5 : req.getTopK());
        payload.setMode(req.getMode());

        List<HistoryMessage> messages = new ArrayList<>(history.size());
        for (ChatMessage m : history) {
            HistoryMessage hm = new HistoryMessage();
            hm.setRole(m.getRole());
            hm.setContent(m.getContent());
            messages.add(hm);
        }
        payload.setHistory(messages);

        ResponseEntity<String> entity;
        try {
            entity = restClient.post()
                    .uri("/internal/agent/chat")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toEntity(String.class);
        } catch (HttpClientErrorException e) {
            throw new BizException(500, "Python Agent 返回 "
                    + e.getStatusCode() + "：" + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new PythonUnavailableException(e.getMessage());
        }

        try {
            return objectMapper.readValue(entity.getBody(), InternalAgentResponse.class);
        } catch (Exception e) {
            throw new PythonUnavailableException("Python 响应解析失败：" + e.getMessage());
        }
    }

    /** Python 侧异常标记：只用于区分「该降级」和「该报错」 */
    private static class PythonUnavailableException extends RuntimeException {
        PythonUnavailableException(String message) {
            super(message);
        }
    }

    public static class InternalAgentRequest {
        private Long sessionId;
        private String question;
        private Long userId;
        private Boolean admin;
        private Integer topK;
        private String mode;
        private List<HistoryMessage> history;

        public Long getSessionId() { return sessionId; }
        public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }

        public Boolean getAdmin() { return admin; }
        public void setAdmin(Boolean admin) { this.admin = admin; }

        public Integer getTopK() { return topK; }
        public void setTopK(Integer topK) { this.topK = topK; }

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public List<HistoryMessage> getHistory() { return history; }
        public void setHistory(List<HistoryMessage> history) { this.history = history; }
    }

    public static class HistoryMessage {
        private String role;
        private String content;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class InternalAgentResponse {
        private String answer;
        private List<SearchHitVO> citations;
        private String route;
        private List<ToolUsage> toolsUsed;
        private Long tookMs;

        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }

        public List<SearchHitVO> getCitations() { return citations; }
        public void setCitations(List<SearchHitVO> citations) { this.citations = citations; }

        public String getRoute() { return route; }
        public void setRoute(String route) { this.route = route; }

        public List<ToolUsage> getToolsUsed() { return toolsUsed; }
        public void setToolsUsed(List<ToolUsage> toolsUsed) { this.toolsUsed = toolsUsed; }

        public Long getTookMs() { return tookMs; }
        public void setTookMs(Long tookMs) { this.tookMs = tookMs; }
    }
}
