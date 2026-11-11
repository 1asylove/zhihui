package com.xiaohu.ai.config;

import com.xiaohu.ai.service.CustomUserDetailsService;
import com.xiaohu.ai.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, CustomUserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }
    /**
     * SSE/异步请求结束时会触发一次 ASYNC 派发，而 Spring Security 6.1+ 默认在所有派发类型上
     * 重新做授权校验。若这里跳过 ASYNC，派发时安全上下文为空，AuthorizationFilter 会抛
     * AuthorizationDeniedException；此时响应已提交，容器只能强行断开连接（客户端报 chunked 未完整结束）。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 1. 取请求头 Authorization
        String authHeader = request.getHeader("Authorization");

        // 2. 带 Bearer token 才处理
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                String username = jwtUtil.getUsername(token);
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                // 4. token 无效/过期：不设置身份，交给 Spring Security 返回 401
            }
        }

        // 5. 无论如何都继续走后续过滤器
        chain.doFilter(request, response);
    }
}