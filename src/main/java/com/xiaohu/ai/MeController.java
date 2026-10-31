package com.xiaohu.ai;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    @GetMapping("/me")
    public Object me(Authentication authentication) {
        return authentication.getAuthorities();
    }
}