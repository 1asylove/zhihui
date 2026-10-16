package com.xiaohu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.dto.LoginRequest;
import com.xiaohu.ai.dto.RegisterRequest;
import com.xiaohu.ai.entity.User;
import com.xiaohu.ai.mapper.UserMapper;
import com.xiaohu.ai.util.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public void register(RegisterRequest req) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (count > 0) {
            throw new RuntimeException("用户名已存在");
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname() == null || req.getNickname().isBlank()
                ? req.getUsername() : req.getNickname());
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);
    }

    // 登录：校验密码，成功则签发 token
    public String login(LoginRequest req) {
        // 1. 按用户名查用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (user == null) {
            throw new RuntimeException("用户名或密码错误");
        }

        // 2. 校验密码（关键：用 matches，不是 equals）
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }

        // 3. 校验通过，签发 token 返回
        return jwtUtil.generateToken(user.getUsername());
    }
}