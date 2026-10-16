package com.xiaohu.ai;

import com.xiaohu.ai.common.Result;
import com.xiaohu.ai.dto.LoginRequest;
import com.xiaohu.ai.dto.RegisterRequest;
import com.xiaohu.ai.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody RegisterRequest req) {
        userService.register(req);
        return Result.success("注册成功", null);
    }

    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginRequest req) {
        return Result.success(userService.login(req));
    }
}