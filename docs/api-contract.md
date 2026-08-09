# LAS SSO 账号与管理 API 契约

- 状态：第一版实施规格
- 基础地址：`https://auth.las.example`
- API 前缀：`/api/v1`
- 依赖：[总纲](../LAS_SSO_DESIGN.md)、[数据模型](identity-and-data-model.md)

## 1. 通用规则

### 1.1 表示与版本

- JSON 请求和成功响应使用 `application/json; charset=utf-8`。
- 错误使用 RFC 9457 风格的 `application/problem+json`。
- 日期时间使用 UTC RFC 3339，例如 `2026-07-29T12:34:56Z`。
- ID 以标准带连字符 UUID 字符串传输。
- 未声明的 JSON 字段默认拒绝，避免客户端拼写错误被静默忽略。
- `/api/v1` 内只做向后兼容扩展；删除/改义字段时增加主版本。

### 1.2 Cookie

| Cookie | 用途 | 属性 |
|---|---|---|
| `__Host-las_sso` | SSO 主会话 | `Secure; HttpOnly; Path=/; SameSite=Lax` |
| `__Host-las_csrf` | 双提交 CSRF 值或前端读取令牌 | `Secure; Path=/; SameSite=Lax` |
| `__Host-las_recovery` | 受限密码恢复会话 | `Secure; HttpOnly; Path=/; SameSite=Strict` |

`__Host-` Cookie 必须使用 `Path=/` 且不设置 `Domain`；恢复会话能访问哪些端点由服务端授权限制，不能依赖 Cookie Path。业务子域不能读取 SSO Cookie。创建或完成登录时必须更换会话 ID，防止会话固定。

### 1.3 CSRF、Origin 与缓存

- 除 OIDC 标准端点中由协议另行保护的请求外，所有改变状态的浏览器请求都要求有效 CSRF Token。
- 对浏览器写请求校验 `Origin`；缺少 `Origin` 时再校验 `Referer` 的同源关系。
- 账号、安全和管理员响应返回 `Cache-Control: no-store`。
- API 不启用允许任意来源的 CORS。第一版 SSO UI 与 API 同源。

### 1.4 请求追踪与幂等

- 每个响应返回 `X-Request-Id`。客户端提供的值不可信，只能在格式和长度校验后沿用。
- 下列端点接受 `Idempotency-Key`，同一操作者、端点和键在 24 小时内返回相同业务结果：
  - 注册；
  - 全局退出；
  - 注销申请/取消；
  - 管理员冻结/解冻/强制下线；
  - 人工恢复凭据签发；
  - 客户端密钥轮换。
- 幂等记录不能缓存或再次返回恢复码、人工恢复凭据、客户端密钥等一次性明文；重试时只返回“已完成，秘密不可再次显示”。

## 2. 错误格式

```json
{
  "type": "https://auth.las.example/problems/username-unavailable",
  "title": "用户名不可用",
  "status": 409,
  "code": "USERNAME_UNAVAILABLE",
  "detail": "请选择其他用户名。",
  "requestId": "019fb42c-bf2a-7f77-b4b4-c751c5f366e8",
  "fieldErrors": [
    {
      "field": "username",
      "code": "UNAVAILABLE"
    }
  ]
}
```

约束：

- `detail` 面向用户，不包含栈、SQL、Token、内部主机名或账号存在性之外的额外信息。
- 协议端点继续使用 OAuth/OIDC 规范错误格式，不套用本 API 的 Problem 格式。
- `401` 表示未登录或会话失效；`403` 表示已登录但缺少管理员/近期认证/CSRF 条件。

第一版错误码：

| HTTP | `code` | 场景 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 字段格式错误 |
| 400 | `CAPTCHA_INVALID` | Challenge 无效、过期或已使用 |
| 400 | `RECOVERY_CREDENTIAL_INVALID` | 恢复凭据无效或已使用 |
| 403 | `CAPTCHA_REQUIRED` | 恢复等匿名敏感流程达到风险阈值，需要先完成人机验证 |
| 401 | `AUTHENTICATION_REQUIRED` | 需要登录 |
| 401 | `SESSION_EXPIRED` | 会话空闲或绝对过期 |
| 403 | `CSRF_INVALID` | CSRF/Origin 校验失败 |
| 403 | `RECENT_AUTHENTICATION_REQUIRED` | 15 分钟内未重新输入密码 |
| 403 | `PASSWORD_CHANGE_REQUIRED` | 临时密码登录后尚未改密 |
| 403 | `ACCOUNT_FROZEN` | 账号冻结 |
| 403 | `DELETION_RESTRICTED` | 注销等待/暂缓期，只允许注销相关流程 |
| 409 | `USERNAME_UNAVAILABLE` | 完全相同大小写的当前或历史用户名已占用 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 同一幂等键被用于不同请求体 |
| 409 | `USERNAME_CHANGE_COOLDOWN` | 90 天冷却未结束 |
| 409 | `STATE_CONFLICT` | 当前状态不允许该操作 |
| 429 | `RATE_LIMITED` | 触发限流；带 `Retry-After` |

## 3. 页面路由

SSO UI 至少提供：

```text
/login
/register
/password-recovery
/password-change-required
/consent
/logged-out
/account
/account/sessions
/account/recovery-codes
/account/authorizations
/account/delete
/admin/users
/admin/clients
/admin/deletion-requests
/admin/audit
```

OIDC 请求触发登录时，服务端保存经过校验的授权事务 ID；页面不得信任任意 `returnUrl`。登录完成后只返回原授权事务或站内允许列表地址。

## 4. 登录与人机验证

未登录页面先调用 `GET /api/v1/csrf` 获取 CSRF header/parameter 名称和 token。该端点只建立防 CSRF 所需状态，不建立登录会话。

### 4.1 获取 ALTCHA Challenge

```http
POST /api/v1/captcha/challenges
```

请求：

```json
{
  "purpose": "REGISTER"
}
```

`purpose` 第一版允许：

```text
REGISTER
LOGIN_RISK
RECOVERY_RISK
```

响应 `201`：

```json
{
  "challengeId": "019fb42d-327a-7700-98bd-870e91ad8351",
  "parameters": {
    "algorithm": "PBKDF2/SHA-256",
    "nonce": "...",
    "salt": "...",
    "cost": 5000,
    "data": {
      "challengeId": "019fb42d-327a-7700-98bd-870e91ad8351",
      "purpose": "REGISTER"
    }
  },
  "signature": "...",
  "expiresAt": "2026-07-29T12:39:56Z"
}
```

第一版使用 ALTCHA PoW v2。服务端验证 Widget payload 的 HMAC、工作量证明、签名元数据中的 `challengeId`/用途、TTL 和请求 IP 网段摘要，并通过 PostgreSQL 条件更新原子消费该 challenge。

### 4.2 密码登录

```http
POST /login
Content-Type: application/x-www-form-urlencoded
```

字段：

```text
username
password
remember
csrf
captchaPayload     仅风险策略要求时出现
loginTransaction  有 OIDC 授权事务时出现
```

处理规则：

- 用户名按提交值精确、大小写敏感地查询当前用户名，不做小写转换；`Alice` 与 `alice` 可以属于不同账号。
- 不区分“用户不存在”和“密码错误”的页面提示与外部错误码。
- 成功后轮换会话 ID，记录 `amr=["pwd"]` 和认证时间。
- `PASSWORD_CHANGE_REQUIRED` 账号建立受限会话并跳转 `/password-change-required`。
- `deletion_status=PENDING/HELD` 的账号只能进入注销查看、取消和退出页面；账号冻结与注销状态分别判断。
- 登录失败达到阈值后要求 `LOGIN_RISK` Challenge；阈值和锁定时间配置化。

## 5. 注册与恢复

### 5.1 注册

```http
POST /api/v1/registrations
Idempotency-Key: <uuid>
```

请求：

```json
{
  "username": "example_user",
  "displayName": "示例用户",
  "password": "correct horse battery staple",
  "captcha": {
    "challengeId": "019fb42d-327a-7700-98bd-870e91ad8351",
    "payload": "..."
  }
}
```

响应 `201`：

```json
{
  "user": {
    "id": "019fb42f-5f16-7c31-8ce0-9e54f7441ff0",
    "username": "example_user",
    "displayName": "示例用户"
  },
  "recoveryCodes": [
    "LAS-....-....",
    "LAS-....-...."
  ],
  "secretsAvailable": true,
  "idempotentReplay": false
}
```

首次成功返回 `201`。相同 `Idempotency-Key` 和完全相同请求体在 24 小时内重试返回 `200`、相同用户、空 `recoveryCodes`、`secretsAvailable=false`、`idempotentReplay=true`；恢复码绝不再次显示。同一幂等键用于不同请求返回 `IDEMPOTENCY_KEY_REUSED`。若网络中断导致用户没有保存恢复码，可使用密码登录后在近期认证保护下重新生成。

注册和用户名修改接口均按 `^[A-Za-z0-9_]{3,24}$` 校验并保留原始大小写。唯一性检查覆盖当前和历史用户名，但只把完全相同的大小写组合视为冲突。

### 5.2 验证恢复凭据

```http
POST /api/v1/recovery/sessions
```

请求：

```json
{
  "username": "example_user",
  "credential": "LAS-....-....",
  "credentialType": "RECOVERY_CODE",
  "captcha": null
}
```

`credentialType` 为 `RECOVERY_CODE` 或 `ADMIN_ISSUED`。成功返回 `204` 并设置受限的 `__Host-las_recovery` Cookie，有效 10 分钟；失败返回统一 `RECOVERY_CREDENTIAL_INVALID`。每次尝试都参与用户、IP 和全局限流。

达到恢复风险阈值后，未提交 CAPTCHA 返回 `CAPTCHA_REQUIRED`，客户端再申请 `RECOVERY_RISK` challenge 并重试。恢复凭据在受限会话创建时立即原子标记为已使用；即使受限会话随后过期，该凭据也不能再次使用。

恢复流程中的用户名同样大小写敏感并精确匹配；客服或管理员不能把 `Alice` 的恢复凭据签发给 `alice`。

### 5.3 完成密码恢复

```http
PUT /api/v1/recovery/password
```

请求：

```json
{
  "newPassword": "new correct horse battery staple"
}
```

成功返回 `204`，并原子完成：

- 使用并废除提交的恢复凭据；
- 更新密码并增加 `credentials_version`；
- 撤销其他恢复会话、SSO 会话、Authorization 和 Refresh Token；
- 写安全事件和全局退出 outbox；
- 清除恢复 Cookie。

使用用户恢复码完成改密时，只消耗本次提交的恢复码，同批其他未使用恢复码继续有效。用户怀疑整批恢复码泄漏时，应登录后调用恢复码重新生成端点主动轮换整批。

该端点不直接建立完整 SSO 会话；用户必须用新密码登录。

## 6. 用户账号 API

### 6.1 当前用户

```http
GET /api/v1/me
```

响应：

```json
{
  "id": "019fb42f-5f16-7c31-8ce0-9e54f7441ff0",
  "username": "example_user",
  "displayName": "示例用户",
  "avatarUrl": null,
  "accountStatus": "ACTIVE",
  "deletionStatus": "NONE",
  "usernameChangeAvailableAt": "2026-10-27T12:34:56Z",
  "isSsoAdmin": false,
  "session": {
    "sid": "opaque-session-id",
    "remembered": true,
    "createdAt": "2026-07-29T12:34:56Z",
    "absoluteExpiresAt": "2026-10-27T12:34:56Z",
    "lastPasswordAuthAt": "2026-07-29T12:34:56Z"
  }
}
```

不返回密码状态摘要、恢复码数量以外的秘密信息、邮箱或业务权限。

### 6.2 近期密码认证

```http
POST /api/v1/me/reauthentication
```

请求：

```json
{
  "password": "..."
}
```

成功返回 `204`，轮换 SSO 会话 ID 并更新 `last_password_auth_at`。该端点要求现有完整会话、CSRF、同源校验和用户/IP 限流；不能用于恢复受限会话或 `PASSWORD_CHANGE_REQUIRED` 会话。

失败统一返回认证失败，不区分密码原因。近期认证成功只解除 15 分钟门禁，不自动重放此前失败的危险写请求；前端必须让用户重新确认并提交原操作。

### 6.3 修改公开资料

```http
PATCH /api/v1/me/profile
```

请求可包含：

```json
{
  "displayName": "新的显示名",
  "avatarResourceId": "default-alien-01"
}
```

`displayName` 最长 64 个 Unicode 字符，作为不可信文本转义显示。第一版 `avatarResourceId` 只能为服务端允许列表中的站内默认头像 ID 或 `null`，响应中的 `avatarUrl` 由服务端生成；不接受用户提交的任意 URL。头像上传作为后续独立媒体安全功能设计。

### 6.4 修改用户名

```http
POST /api/v1/me/username-change
```

请求：

```json
{
  "newUsername": "new_example_user",
  "currentPassword": "..."
}
```

要求当前密码正确，不以已有近期认证代替。仅改变大小写也视为一次完整修改并进入 90 天冷却。成功返回更新后的用户资料；冷却冲突返回下一次允许时间：

```json
{
  "code": "USERNAME_CHANGE_COOLDOWN",
  "availableAt": "2026-10-27T12:34:56Z"
}
```

### 6.5 修改密码

```http
POST /api/v1/me/password-change
```

普通会话请求：

```json
{
  "currentPassword": "...",
  "newPassword": "..."
}
```

`PASSWORD_CHANGE_REQUIRED` 受限会话请求：

```json
{
  "temporaryPassword": "...",
  "newPassword": "..."
}
```

成功返回 `204`，增加 `credentials_version`，撤销除当前改密结果页所需受限状态外的所有会话和 Authorization。管理员初次改密成功后，前端立即引导调用恢复码重新生成端点。

### 6.6 会话管理

```http
GET    /api/v1/me/sessions
DELETE /api/v1/me/sessions/{sid}
POST   /api/v1/me/logout-all
```

列表只展示粗粒度最近活动、浏览器摘要、是否当前会话、是否记住和过期时间，不展示完整 IP。

- 删除非当前会话返回 `204`。
- 删除当前会话等同 SSO 全局退出，并清除当前 Cookie。
- `logout-all` 要求 `Idempotency-Key`，撤销 SSO 会话和全部客户端 Authorization，并触发 Back-channel Logout。

### 6.7 恢复码

```http
GET  /api/v1/me/recovery-codes/status
POST /api/v1/me/recovery-codes/rotate
```

状态响应只包含可用数量和最近生成时间。轮换要求 15 分钟内密码认证；成功响应一次返回 8–10 个新码，旧码在同一事务内全部废除。

### 6.8 已授权应用

```http
GET    /api/v1/me/authorizations
DELETE /api/v1/me/authorizations/{clientId}
```

列表只返回客户端展示名、已批准 scopes、首次/最近授权时间。第一方客户端可以显示为“LAS 自营服务”；合作网站必须逐项显示批准的数据范围。

撤销授权时：

- 撤销该用户和客户端的全部 Authorization 与 Refresh Token；
- 触发该客户端的 Back-channel Logout；
- 不影响其他客户端或 SSO 主会话；
- 第一方客户端再次登录时可自动重新授权；
- 合作网站再次登录时重新显示 consent。

### 6.9 自助注销

```http
POST   /api/v1/me/deletion-request
DELETE /api/v1/me/deletion-request
```

申请请求必须包含当前密码。成功返回：

```json
{
  "deletionStatus": "PENDING",
  "executeAfter": "2026-08-28T12:34:56Z"
}
```

申请后立即触发全局退出。取消时必须重新登录/恢复到受限注销页面，并在 DELETE 请求体提交 `{"password":"..."}` 重新验证当前密码。申请和取消都必须提供 `Idempotency-Key`。已执行删除不能自助恢复。

## 7. 管理员 API

所有 `/api/v1/admin/**` 端点要求：

- 当前账号 `is_sso_admin = true`；
- 管理员短会话未过期；
- 写操作提供 CSRF；
- 敏感写操作在 15 分钟内重新输入过密码；
- 写入 `admin_audit_events`。

### 7.1 用户

```http
GET  /api/v1/admin/users?query=&status=&cursor=&limit=
GET  /api/v1/admin/users/{userId}
POST /api/v1/admin/users/{userId}/freeze
POST /api/v1/admin/users/{userId}/unfreeze
POST /api/v1/admin/users/{userId}/revoke-sessions
POST /api/v1/admin/users/{userId}/recovery-credentials
```

写请求统一包含：

```json
{
  "reason": "人工处理说明"
}
```

约束：

- 管理员不能冻结、删除、解任或对自己签发人工恢复凭据。
- 冻结和强制下线必须触发 Authorization 撤销与 Back-channel Logout。
- 人工恢复凭据响应只显示一次：

```json
{
  "credential": "LAS-ADMIN-....",
  "expiresAt": "2026-07-29T13:04:56Z"
}
```

- 第一版不提供管理员直接改用户密码、查看恢复码或冒充登录。

### 7.2 OIDC Client

```http
GET    /api/v1/admin/clients
POST   /api/v1/admin/clients
GET    /api/v1/admin/clients/{clientId}
PATCH  /api/v1/admin/clients/{clientId}
POST   /api/v1/admin/clients/{clientId}/disable
POST   /api/v1/admin/clients/{clientId}/enable
POST   /api/v1/admin/clients/{clientId}/secret-rotation
```

创建/更新时服务端强制验证：

- Redirect URI 和 Post Logout URI 精确地址；
- 生产只允许 HTTPS；
- 禁止通配符、片段、userinfo 和开放跳转；
- 第一版固定 Authorization Code + Refresh Token、PKCE S256；
- `client_secret_basic`；
- scopes 只能取管理员允许集合；
- 每个客户端至少一个明确的 resource audience；
- 第一方 `requireConsent=false`，合作方必须为 `true`。

新建客户端和轮换密钥仅一次返回明文：

```json
{
  "clientSecret": "las_client_...",
  "validFrom": "2026-07-29T12:34:56Z",
  "expiresAt": null,
  "configVersion": 3
}
```

第一版每个客户端只有一把活动密钥。`secret-rotation` 原子替换密钥摘要、增加 `config_version` 并立即使旧密钥失效；管理员页面必须要求短维护窗口和已准备部署新密钥的确认。若响应丢失，不能再次读取明文，只能再次轮换。

客户端配置变更语义：

- 禁用客户端、缩小 scopes/audience 或轮换密钥：增加 `config_version`，撤销现有 Authorization、RT 和该客户端 BFF 会话；
- 合作方扩大 scopes：增加版本，旧 consent 不覆盖新增 scope，下次登录必须重新授权；
- 修改 Redirect/Post Logout/Back-channel URI：只对新协议请求生效，旧地址不再接受；若属于安全事件，先禁用并撤销全部授权；
- 仅修改展示名称不强制用户退出；
- PATCH 响应明确返回是否触发撤销、受影响授权数量和新的 `configVersion`。

这里管理的是 OIDC 客户端密钥，不是 JWT 签名私钥。API 不提供签名私钥读取、上传、轮换或删除功能。

### 7.3 审计与安全事件

```http
GET /api/v1/admin/audit-events?cursor=&action=&targetId=&from=&to=
GET /api/v1/admin/security-events?cursor=&type=&severity=&userId=&from=&to=
GET /api/v1/admin/deletion-requests?status=&cursor=&limit=
POST /api/v1/admin/deletion-requests/{requestId}/hold
POST /api/v1/admin/deletion-requests/{requestId}/release
```

- `limit` 默认 50，最大 200。
- 使用不透明 cursor，不使用可被大范围跳页滥用的 offset。
- 导出功能不在第一阶段；日志页面不显示秘密或完整 IP。
- 注销任务默认自动执行，不需要管理员逐个批准。只有已记录的安全事件、账号归属争议或明确保留依据才能暂缓；`hold` 必须填写原因和最长 90 天的 `holdUntil`，到期自动释放。业务系统自己的稿件、处罚、Minecraft 数据保留不通过 SSO hold 解决。

## 8. 权限与近期认证矩阵

| 操作 | 登录 | 当前密码/近期认证 | 管理员 |
|---|---:|---:|---:|
| 查看/修改普通资料 | 是 | 否 | 否 |
| 建立 15 分钟近期认证 | 是 | 请求内当前密码 | 否 |
| 修改用户名 | 是 | 请求内当前密码 | 否 |
| 修改密码 | 是 | 请求内当前/临时密码 | 否 |
| 查看会话、撤销其他会话 | 是 | 否 | 否 |
| 全局退出 | 是 | 否 | 否 |
| 重新生成恢复码 | 是 | 15 分钟内 | 否 |
| 申请/取消注销 | 是 | 请求内当前密码 | 否 |
| 查看管理员只读页面 | 是 | 否 | 是 |
| 冻结、人工恢复、客户端变更 | 是 | 15 分钟内 | 是 |
| 查看或操作 JWT 签名私钥 | 不提供 | 不提供 | 不提供 |

## 9. 日志与秘密处理

应用日志必须对以下字段整体删除而不是部分遮盖：

```text
password
currentPassword
newPassword
temporaryPassword
credential
recoveryCodes
clientSecret
authorization_code
access_token
refresh_token
id_token
logout_token
Cookie
Set-Cookie
```

反向代理访问日志不记录请求体；查询串中禁止放置秘密。管理员原因、客户端名称等自由文本在展示时按不可信内容转义。
