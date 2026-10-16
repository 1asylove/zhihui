package com.xiaohu.ai.config;

import com.xiaohu.ai.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    public JwtInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 从请求头取 "Authorization"
        String authHeader = request.getHeader("Authorization");

        // 2. 没带 token 或格式不对 → 拒绝
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            response.getWriter().write("未登录");
            return false; // false = 不放行
        }

        // 3. 去掉 "Bearer " 前缀，拿到真正的 token
        String token = authHeader.substring(7);

        // 4. 校验 token
        try {
            String username = jwtUtil.getUsername(token);
            request.setAttribute("username", username); // 存起来给 Controller 用
            return true; // true = 放行，继续走 Controller
        } catch (Exception e) {
            response.setStatus(401);
            response.getWriter().write("token 无效或已过期");
            return false;
        }
    }
}