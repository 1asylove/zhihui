package com.xiaohu.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话消息：assistant 消息带 citations，下标 + 1 即当时回答里的 [n]。
 */
public class MessageVO {

    private Long id;
    private String role;
    private String content;
    private List<SearchHitVO> citations;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<SearchHitVO> getCitations() { return citations; }
    public void setCitations(List<SearchHitVO> citations) { this.citations = citations; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
