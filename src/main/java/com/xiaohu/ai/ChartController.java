package com.xiaohu.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.common.BizException;
import com.xiaohu.ai.common.Result;
import com.xiaohu.ai.dto.ChartOverviewVO;
import com.xiaohu.ai.entity.User;
import com.xiaohu.ai.mapper.UserMapper;
import com.xiaohu.ai.service.ChartService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/charts")
public class ChartController {

    private final ChartService chartService;
    private final UserMapper userMapper;

    public ChartController(ChartService chartService, UserMapper userMapper) {
        this.chartService = chartService;
        this.userMapper = userMapper;
    }

    /**
     * 首页看板聚合统计。登录即可（同 GET /documents），
     * ADMIN 看全量，USER 只看自己。
     */
    @GetMapping("/overview")
    public Result<ChartOverviewVO> overview() {
        User user = currentUser();
        Long uploaderId = isAdmin() ? null : user.getId();
        return Result.success(chartService.overview(uploaderId));
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
