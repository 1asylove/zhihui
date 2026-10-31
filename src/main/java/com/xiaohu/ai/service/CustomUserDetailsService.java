package com.xiaohu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaohu.ai.entity.Permission;
import com.xiaohu.ai.entity.Role;
import com.xiaohu.ai.entity.RolePermission;
import com.xiaohu.ai.entity.User;
import com.xiaohu.ai.entity.UserRole;
import com.xiaohu.ai.mapper.PermissionMapper;
import com.xiaohu.ai.mapper.RoleMapper;
import com.xiaohu.ai.mapper.RolePermissionMapper;
import com.xiaohu.ai.mapper.UserMapper;
import com.xiaohu.ai.mapper.UserRoleMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    public CustomUserDetailsService(UserMapper userMapper,
                                    UserRoleMapper userRoleMapper,
                                    RoleMapper roleMapper,
                                    RolePermissionMapper rolePermissionMapper,
                                    PermissionMapper permissionMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        // 1. 查用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在：" + username);
        }

        // 2. 查用户身上的角色 id
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, user.getId()))
                .stream()
                .map(UserRole::getRoleId)
                .toList();

        // 3. 收集 authorities：ROLE_xxx（给 hasRole 用）+ 权限点裸串（给 hasAuthority 用）
        List<String> authorities = new ArrayList<>();

        if (!roleIds.isEmpty()) {
            // 3.1 角色
            roleMapper.selectBatchIds(roleIds).stream()
                    .map(Role::getRoleCode)
                    .map(code -> "ROLE_" + code)
                    .forEach(authorities::add);

            // 3.2 角色 → 权限点
            List<Long> permissionIds = rolePermissionMapper.selectList(
                            new LambdaQueryWrapper<RolePermission>()
                                    .in(RolePermission::getRoleId, roleIds))
                    .stream()
                    .map(RolePermission::getPermissionId)
                    .toList();

            if (!permissionIds.isEmpty()) {
                permissionMapper.selectBatchIds(permissionIds).stream()
                        .map(Permission::getPermCode)
                        .forEach(authorities::add);
            }
        }

        // 4. 组装成 Spring Security 认识的 UserDetails
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .authorities(authorities.toArray(String[]::new))
                .build();
    }
}