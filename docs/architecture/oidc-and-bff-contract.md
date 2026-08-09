# LAS SSO OIDC 与 BFF 接入契约

- 状态：第一版实施规格
- 角色：LAS SSO 是 OpenID Provider；LASLOG、AlienCommon 后端是 Relying Party/BFF
- 依赖：[总纲](../../LAS_SSO_DESIGN.md)、[API 契约](api-contract.md)

## 1. 协议配置

身份主体不变量：

- Spring Security 使用自定义 `LasUserPrincipal`；
- `LasUserPrincipal.getName()` 必须返回用户 UUID v7 的规范字符串；
- `Authentication.getName()`、`OAuth2Authorization.principalName` 和 OIDC `sub` 三者必须相同且不可随用户名改变；
- 当前用户名只通过 `preferred_username` 提供；任何框架默认值若会把用户名写入 `principalName`，必须显式覆盖并通过契约测试。

### 1.1 Issuer 与发现

设计占位 Issuer：

```text
https://auth.las.example
```

客户端必须从以下地址读取 Discovery，不在代码中分别拼接端点：

```text
https://auth.las.example/.well-known/openid-configuration
```

正式 Issuer 必须和 Discovery 的 `issuer`、ID Token/Access Token/Logout Token 的 `iss` 完全相等，包括 scheme、host、port 和 path。

第一版 Discovery 至少发布：

```text
issuer
authorization_endpoint
token_endpoint
jwks_uri
userinfo_endpoint
revocation_endpoint
end_session_endpoint
scopes_supported
response_types_supported
grant_types_supported
code_challenge_methods_supported
id_token_signing_alg_values_supported
backchannel_logout_supported
backchannel_logout_session_supported
```

预期 Spring 端点如下，Discovery 的实际值是唯一权威来源：

```text
/oauth2/authorize
/oauth2/token
/oauth2/jwks
/oauth2/revoke
/userinfo
/connect/logout
```

第一版不启用 Dynamic Client Registration、Implicit、Password、Device Code、Token Exchange 或 Client Credentials。

### 1.2 算法

- ID Token、Access Token 和 Logout Token 第一版使用 `RS256`。
- JWKS 中每把公钥都有稳定唯一 `kid`。
- 客户端不得接受 `alg=none`，也不得根据 Token 自带算法动态扩大允许列表。
- Authorization Code 流固定使用 PKCE `S256`，拒绝 `plain` 和缺失 challenge。
- BFF 客户端认证固定使用 `client_secret_basic`，不把密钥放在请求体、查询串或浏览器。

## 2. 第一方客户端登记

占位生产配置：

| 项目 | LASLOG | AlienCommon |
|---|---|---|
| `client_id` | `laslog-bff` | `aliencommon-bff` |
| 类型 | Confidential | Confidential |
| Redirect URI | `https://log.las.example/login/oauth2/code/las-sso` | `https://common.las.example/auth/oidc/callback` |
| Post Logout URI | `https://log.las.example/logged-out` | `https://common.las.example/logged-out` |
| Back-channel URI | `https://log.las.example/internal/oidc/backchannel-logout` | `https://common.las.example/internal/oidc/backchannel-logout` |
| Scopes | `openid profile` | `openid profile` |
| Resource audience | `urn:las:resource:laslog` | `urn:las:resource:aliencommon` |
| Consent | 不显示 | 不显示 |

本地开发可以使用固定的 `http://localhost` 精确地址；不能把本地地址登记进生产客户端，也不能使用通配符。

第一版不开放 OAuth `resource` 参数给浏览器选择。每个 BFF 的 Access Token audience 由审核后的客户端配置固定映射，避免客户端请求任意资源。

客户端密钥规则：

- 密钥至少 256 bit 随机熵；
- 只在创建或轮换时显示一次；
- 服务端使用 `PasswordEncoder` 保存摘要；
- 第一版每个客户端只有一把活动密钥；
- 轮换在短维护窗口内原子替换，旧密钥立即失效，随后部署/重启 BFF 并执行冒烟测试；
- 禁用客户端时立即撤销其授权、Refresh Token 和活动 BFF 会话。

## 3. Authorization Code 流

### 3.1 BFF 发起登录

BFF 为每个登录事务生成并服务端保存：

| 值 | 要求 |
|---|---|
| `state` | 至少 128 bit 随机值，单次使用，10 分钟过期 |
| `nonce` | 至少 128 bit 随机值，绑定该事务 |
| `code_verifier` | 43–128 个 RFC 7636 unreserved 字符 |
| `code_challenge` | `BASE64URL(SHA256(code_verifier))` |
| `return_to` | 只允许站内相对路径或服务端允许列表 |
| `issuer` | 固定为发现文档中的 Issuer |

授权请求固定包含：

```text
response_type=code
client_id=<当前 BFF>
redirect_uri=<精确登记地址>
scope=openid profile
state=<随机值>
nonce=<随机值>
code_challenge=<S256 结果>
code_challenge_method=S256
```

不使用 iframe 静默登录。需要无感续期时，BFF 发起正常顶层 Authorization Code 跳转；如果 SSO 会话仍有效，SSO 可以不再次显示密码页。

### 3.2 回调验证顺序

BFF 收到回调后必须：

1. 确认当前存在未使用且未过期的登录事务。
2. 常量时间比较 `state`，失败立即终止。
3. 拒绝同时出现 `code` 和 OAuth `error` 的异常响应。
4. 使用原始 `redirect_uri`、`code_verifier` 和服务器端客户端认证兑换 code。
5. 验证 ID Token 签名、`kid`、固定算法、`iss`、`aud`、`azp`（出现时）、`exp`、`iat` 和 `nonce`。
6. 允许的时钟偏差最多 60 秒；系统时钟偏差超过阈值必须报警。
7. 确认 `sub` 是合法 UUID v7 字符串，但永远按不透明字符串保存。
8. 创建/更新本地用户映射和 BFF 会话。
9. 原子标记登录事务已使用并清理 `code_verifier`。
10. 把浏览器重定向到已经校验的站内 `return_to`，回调 URL 不保留 code。

回调失败不能把 Token 响应、Authorization Code 或客户端密钥显示给用户或写入日志。

## 4. Token 与 Claims

### 4.1 固定初始时长

| 对象 | 时长 | 规则 |
|---|---:|---|
| Authorization Code | 2 分钟 | 单次使用 |
| ID Token | 10 分钟 | 只用于登录结果和 RP-Initiated Logout 提示 |
| Access Token | 15 分钟 | audience 限定 |
| Refresh Token family | 14 天 | 从首次 Code 授权起算的绝对期限；每次使用轮换但不顺延；不启用 `offline_access` |
| Logout Token | 最多 2 分钟 | 单次 `jti` |

这些是第一版具体默认值。上线后可以按客户端降低，不能由客户端自行提高。

### 4.2 ID Token

必需 Claims：

```json
{
  "iss": "https://auth.las.example",
  "sub": "019fb42f-5f16-7c31-8ce0-9e54f7441ff0",
  "aud": "laslog-bff",
  "exp": 1785330000,
  "iat": 1785329400,
  "auth_time": 1785329390,
  "nonce": "...",
  "sid": "...",
  "acr": "urn:las:loa:1",
  "amr": ["pwd"]
}
```

在批准 `profile` 时还可以包含：

```text
preferred_username
name
picture
updated_at
```

`updated_at` 使用 Unix 时间秒。ID Token 不包含邮箱、Minecraft、QQ、白名单、封禁、SSO 管理员身份或任何业务权限。

`preferred_username` 保留账号当前用户名的原始大小写。客户端只能将其用于展示或可变资料同步，登录和本地映射仍以 `(issuer, sub)` 为准。

### 4.3 Access Token

Access Token 是签名 JWT，最小 Claims：

```text
iss
sub
aud
exp
iat
nbf
jti
client_id
scope
sid
```

其中：

- `aud` 对 LASLOG 只能是 `urn:las:resource:laslog`；
- `aud` 对 AlienCommon 只能是 `urn:las:resource:aliencommon`；
- Access Token 不复制 profile Claims；
- Resource Server 必须验证签名、固定 Issuer、自己的 audience、时间和所需 scope；
- Resource Server 不接受 ID Token 作为 API 凭据。

第一版不提供通用“LAS 全部 API” audience。

### 4.4 UserInfo

`/userinfo` 只接受有效 Access Token，并最多返回：

```json
{
  "sub": "019fb42f-5f16-7c31-8ce0-9e54f7441ff0",
  "preferred_username": "example_user",
  "name": "示例用户",
  "picture": "https://auth.las.example/media/avatar/...",
  "updated_at": 1785329300
}
```

UserInfo 的 `sub` 必须与 ID Token 完全一致，否则 BFF 终止登录。

### 4.5 Refresh Token

- Refresh Token 只发送给 Confidential BFF。
- 每次成功刷新都签发新 Refresh Token 并废除旧 Token。
- Token 绑定原 `client_id`、用户、scope、resource audience、Authorization 和 Token family。
- RT family 的绝对到期时间固定为首次 Code 授权后 14 天，轮换不能延长；到期后 BFF 发起新的顶层 Authorization Code 流，仍有效的 SSO 会话可使该流程无需再次输入密码。
- 同一 BFF 的并发业务请求通过 PostgreSQL 行锁或 advisory lock 实现分布式 single-flight，不能只用进程内锁；其他请求等待结果，不能并发使用同一旧 Token。
- SSO 刷新事务对 Authorization/Token family 加行锁，并在签发前重新校验账号状态、`credentials_version`、全局 `security_epoch` 和客户端 `config_version`。
- 检测到已用 Token 再次出现时，撤销整个 family、对应 BFF 会话，写高危安全事件并触发 Back-channel Logout。
- 改密、密码恢复、账号冻结、账号删除、全局退出和管理员强制下线撤销相关 Refresh Token。

## 5. BFF 本地契约

### 5.1 浏览器可见端点

适配库对前端统一暴露：

```text
GET  /api/auth/login?returnTo=/path
GET  /api/auth/callback
GET  /api/session
POST /api/logout
POST /api/logout/global
```

推荐语义：

- `/api/auth/login` 返回 `302` 到 SSO Authorization Endpoint。
- `/api/auth/callback` 完成验证后返回 `303` 到站内目标。
- `/api/session` 只返回本地会话和安全的用户快照。
- `/api/logout` 退出当前应用。
- `/api/logout/global` 建立 RP-Initiated Logout 跳转并退出全部应用。

前端永远不接收 Access Token、Refresh Token、ID Token、Authorization Code 或客户端密钥。

### 5.2 BFF 会话记录

BFF 服务端至少保存：

```text
local_session_id
issuer
sub
sid
authorization_id
access_token_expiry
auth_fresh_until
refresh_token_reference_or_encrypted_value
refresh_token_family_expires_at
id_token_reference_or_encrypted_value
scopes
created_at
last_seen_at
absolute_expires_at
local_user_id
profile_updated_at
```

Cookie 只保存随机 `local_session_id`，使用主机级 `Secure`、`HttpOnly`、`SameSite=Lax`。Token 放在服务端受限存储；如必须可逆保存，使用独立密钥加密且密钥不与数据库同处。

BFF 本地会话绝对期限不得超过 14 天，达到后通过新的顶层 Authorization Code 流建立新会话；有效的 SSO 记住设备会话仍可让该流程无需再次输入密码。无论本地绝对期限是否到达，`auth_fresh_until` 都固定等于当前 Access Token 的到期时间。每个需要身份或调用受保护资源的请求都必须确认 `now < auth_fresh_until`；到期后只有成功刷新或完成新的顶层 Authorization Code 流才能继续。刷新因撤销失败时清除本地认证状态；SSO 暂时不可用时返回服务不可用，不能仅凭仍未过期的本地 Cookie 继续。这样即使 Back-channel Logout 暂时失败，被撤销授权也会在最多约 15 分钟后的 freshness 边界被阻断。

### 5.3 `/api/session` 响应

未登录：

```json
{
  "authenticated": false
}
```

已登录：

```json
{
  "authenticated": true,
  "user": {
    "sub": "019fb42f-5f16-7c31-8ce0-9e54f7441ff0",
    "username": "example_user",
    "displayName": "示例用户",
    "avatarUrl": null
  },
  "session": {
    "expiresAt": "2026-08-12T12:34:56Z"
  }
}
```

业务角色和权限可以由业务 BFF 在自己的命名空间追加，例如 `businessPermissions`，但适配库不得把它们误称为 SSO Claims。

### 5.4 JIT Provisioning

业务数据库对 `(issuer, sub)` 建唯一约束。登录事务内：

1. 按 `(issuer, sub)` 查询本地映射。
2. 不存在时创建普通、无敏感权限用户。
3. 已存在时在完整登录后更新资料；持续会话最多每 24 小时调用一次 UserInfo，并只在 `updated_at` 更新时同步用户名、显示名、头像快照。
4. 不因用户名变化创建第二个本地用户，也不按用户名合并账号。
5. 本地封禁或权限判断在 JIT 之后由业务系统执行。

SSO 不可用时，已有本地会话可以在本地安全策略允许的期限内继续；到刷新点无法获得新 Token 时转为不可调用受保护资源，不把 SSO 故障解释为用户被封禁。

## 6. 退出

### 6.1 退出当前应用

`POST /api/logout` 的顺序：

1. 在本地事务中将会话标记为已撤销并清除 BFF Cookie；该步骤不依赖 SSO 可用。
2. 通过可靠 outbox/后台任务使用客户端认证调用 Revocation Endpoint，尽力撤销当前 Refresh Token/Authorization。
3. 立即返回 `204` 或跳转到业务首页；远程撤销失败只记录重试和告警，不能让用户重新处于本地登录状态。

它不调用 OP End Session Endpoint，因此保留 SSO 主会话。用户再次登录该应用时仍可能无须输入密码。

### 6.2 退出全部应用

`POST /api/logout/global` 的顺序：

1. 从服务端会话生成指向 Discovery `end_session_endpoint` 的跳转，携带当前或最近有效的 `id_token_hint`、精确登记的 `post_logout_redirect_uri` 和随机 `state`；不得把 ID Token 交给前端脚本。
2. 本地立即撤销会话并清除 Cookie，保留仅用于校验退出返回的短期 `state`。
3. 浏览器以顶层导航进入 SSO。
4. SSO 撤销主会话、全部 Authorization/Refresh Token，并向访问过的 BFF 发送 Back-channel Logout。
5. SSO 只跳回精确登记的 Post Logout URI。
6. BFF 校验退出 `state`，清理残余本地会话。

如果没有可用的 `id_token_hint`，BFF 可以引导用户进入 SSO 账号中心的已认证全局退出接口，但不能接受任意退出后跳转地址。

## 7. Back-channel Logout

SSO Discovery 发布：

```json
{
  "backchannel_logout_supported": true,
  "backchannel_logout_session_supported": true
}
```

SSO 使用 `application/x-www-form-urlencoded` POST：

```text
logout_token=<signed JWT>
```

Logout Token：

- Header `typ` 固定 `logout+jwt`；
- 必须签名，禁止 `alg=none`；
- 包含 `iss`、`aud`、`iat`、`exp`、`jti`、`events`；
- 包含 `sid`，可同时包含 `sub`；
- `events` 包含 `http://schemas.openid.net/event/backchannel-logout: {}`；
- 禁止包含 `nonce`；
- `aud` 是接收方 `client_id`；
- 最长 2 分钟有效。

BFF 验证：

1. 固定算法、签名和 `kid`；
2. `iss` 等于配置 Issuer；
3. `aud` 包含自己的 `client_id`；
4. `iat`/`exp` 在允许的 60 秒时钟偏差内；
5. 存在 `sid` 或 `sub`，第一方契约要求必须有 `sid`；
6. `events` 值正确且没有 `nonce`；
7. 以 `jti` 查询幂等缓存：首次请求保存 Logout Token 内容摘要和处理结果；完全相同的有效 Token 重传直接返回之前的成功结果；相同 `jti` 但内容不同则拒绝；
8. 按 `(issuer, sid)` 清理本地会话；已退出也视为成功。

返回：

- 有效且处理成功或完全相同的有效重传：`200` 或 `204`，`Cache-Control: no-store`；
- Token 无效：`400`；
- 临时内部错误：`5xx`，允许 SSO 延迟重试。

SSO 对各 BFF 并行发送，指数退避并加入随机抖动。重复通知必须幂等；达到重试上限后告警，但不恢复已经撤销的 SSO 状态。

## 8. Spring Boot 与 Django 适配库

### 8.1 共同能力

两个适配库必须提供一致的：

```text
login
callback
currentSession
requireLogin
requireRecentAuthentication
logoutCurrent
logoutGlobal
backChannelLogout
refreshWithSingleFlight
jitProvision
```

适配库内部使用成熟 OAuth/OIDC/JWT 库，不自行解析 JWT 字符串或实现签名算法。

### 8.2 配置项

统一逻辑配置名：

```text
issuer
clientId
clientSecret
redirectUri
postLogoutRedirectUri
backchannelLogoutUri
expectedAudience
scopes
sessionCookieName
sessionEncryptionKeyReference
clockSkewSeconds
```

客户端密钥和会话加密密钥只能来自部署 Secret，不允许提交到仓库或前端运行时配置。

### 8.3 契约版本

- 接入契约使用语义化版本，例如 `1.0.0`。
- 适配库在启动日志中记录自身版本和契约主版本，不记录秘密。
- SSO 变更 Discovery、Claims、退出 Token 或错误语义前，必须先通过两个适配库的契约测试。
- 业务应用可以独立升级适配库，但主版本不兼容时启动失败并明确报错。

## 9. 合作网站

未来合作网站接入时必须：

- 人工审核运营方、域名、隐私说明、数据用途、联系人和安全能力；
- 独立 `client_id`，禁止复用 LAS 自营客户端；
- 精确登记回调/退出地址和最小 scopes；
- 首次授权和 scope 扩大时显示 consent；
- 允许用户查看和撤销 consent；
- 不提供 `email`、Minecraft、业务权限或管理员 Claims；
- 发生滥用时可单独禁用客户端和撤销其全部授权；
- 浏览器型 SPA 必须另行设计为 Public Client，不能获得客户端密钥；第一版不开放自助创建。

## 10. 规范依据

- OpenID Connect Core 1.0: <https://openid.net/specs/openid-connect-core-1_0.html>
- OpenID Connect Discovery 1.0: <https://openid.net/specs/openid-connect-discovery-1_0.html>
- OpenID Connect RP-Initiated Logout 1.0: <https://openid.net/specs/openid-connect-rpinitiated-1_0.html>
- OpenID Connect Back-Channel Logout 1.0: <https://openid.net/specs/openid-connect-backchannel-1_0.html>
- OAuth 2.0 Security Best Current Practice, RFC 9700: <https://www.rfc-editor.org/rfc/rfc9700.html>
- PKCE, RFC 7636: <https://www.rfc-editor.org/rfc/rfc7636.html>
- Spring Authorization Server Core Model: <https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/core-model-components.html>
