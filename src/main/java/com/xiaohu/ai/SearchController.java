package com.xiaohu.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.common.Result;
import com.xiaohu.ai.dto.SearchRequest;
import com.xiaohu.ai.dto.SearchResponse;
import com.xiaohu.ai.entity.User;
import com.xiaohu.ai.mapper.UserMapper;
import com.xiaohu.ai.service.SearchService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识检索接口：M4 Step1 基础检索、Step5 混合检索 + Rerank。
 * 契约见 contracts/search.openapi.yaml。
 */
@RestController
public class SearchController {

    private final SearchService searchService;
    private final UserMapper userMapper;

    public SearchController(SearchService searchService, UserMapper userMapper) {
        this.searchService = searchService;
        this.userMapper = userMapper;
    }

    @PostMapping("/search")
    @PreAuthorize("hasAuthority('chat:ask')")
    public Result<SearchResponse> search(@RequestBody(required = false) SearchRequest req) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        User user = currentUser();
        return Result.success(searchService.search(req.getQuery(), req.getTopK(),
                user.getId(), isAdmin(), req.getMode()));
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
