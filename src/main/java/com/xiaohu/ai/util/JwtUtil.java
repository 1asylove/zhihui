package com.xiaohu.ai.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration; // 单位：毫秒

    // 把配置里的字符串密钥转成算法需要的密钥对象
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // 生成 token（登录成功时用）
    public String generateToken(String username) {
        return Jwts.builder()
                .subject(username)                       // 把用户名写进 token
                .issuedAt(new Date())                    // 签发时间
                .expiration(new Date(System.currentTimeMillis() + expiration)) // 过期时间
                .signWith(getKey())                      // 用密钥签名
                .compact();                              // 拼成最终字符串
    }

    // 解析 token，取出里面的用户名（验证身份时用）
    public String getUsername(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getKey())                    // 用同一把密钥验证签名
                .build()
                .parseSignedClaims(token)                // 解析并验签
                .getPayload();
        return claims.getSubject();                      // 取出用户名
    }
}