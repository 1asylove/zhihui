# 鉴权与 RBAC 规格（Auth & RBAC Spec）

## 1. 目标
从jwt到spring security的升级，实现用户认证与授权

## 2. 范围
### 做
- 登录签发 JWT、受保护接口校验 token
- 用户 - 角色 - 权限 三级权限模型
- 角色：ADMIN（管理员）、USER（普通用户）
### 不做
- 单点登录、OAuth 第三方登录、验证码

## 3. 数据模型
- user（已有）：id, username, password
- role（新增）：id, role_code, role_name
- permission（新增）：id, perm_code, perm_name
- user_role（新增）：user_id, role_id
- role_permission（新增）：role_id, permission_id

## 4. 角色与权限

| 角色 role_code | 说明         |
|--------------|------------|
| ADMIN        | 管理员，拥有全部权限 |
| USER         | 普通用户       |

权限点（perm_code）：
- doc:upload 上传文档
- doc:delete 删除文档
- chat:ask   发起问答
- user:manage 管理用户

角色-权限映射：
- ADMIN → user:manage, doc:upload, doc:delete, chat:ask
- USER  → doc:upload, chat:ask

## 5. 功能需求
- FR-1：`POST /auth/login` ，body 传`username` /`password` ，成功返回`Result<String>` ，`data` 为该用户的 JWT。
- FR-2：受保护接口，无 token 会报错 401
- FR-3：登录成功后，系统 应能 根据用户角色查出其权限点，后续请求 能识别 该用户具备哪些权限
- FR-4：只有 ADMIN 角色能访问`user:manage` 类接口；USER 访问时返回 403 无权限

## 6. 接口契约
- 见 contracts/auth.openapi.yaml（占位，下一步写）

## 7. 验收标准
- [ ] 登录成功返回 JWT
- [ ] 无 token 访问受保护接口 → 401
- [ ] ADMIN 可访问管理接口，USER 访问被拒（403）