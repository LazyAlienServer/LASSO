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

UserInfo 是服务端 Bearer 端点，GET/POST 使用有效的已签发 openid Access Token；浏览器 Cookie 不能替代 Token，缺失/无效/撤销凭据返回401而非登录页重定向。2026-10-05真实参考BFF发现302，AS显式resource-server/Bearer converter与UserInfo entry point修复后，真实Token回归及默认/自有BFF Edge闭环通过。来源与完整边界见[English交付.2](../integration/development-delivery-2026-10-05-2.md)／[中文](../zh-CN/integration/development-delivery-2026-10-05-2.md)；不表示消费十五分钟门禁或生产TLS已验。

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

2026-10-06撤销/改密后的旧SSOCookie新Authorization续行已修：SSO清旧context/Session后仅GET授权请求anonymous进入成熟协议验证，合法请求才在newSession存pending并收真密码；不复制旧savedrequest/challenge/auth_time，其他expiry维持拒。普通/forced、promptnone和不安全redirect/max_age有7新回归，实际reset后BFF callback过。见[English交付](../integration/development-delivery-2026-10-06-2.md)／[中文](../zh-CN/integration/development-delivery-2026-10-06-2.md)，非RP高风险门禁。

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

2026-10-04 B5 已实现安全返回：入口和配置的直接授权入口拒绝无效/重复/过长returnTo（no-store400）。目标只允许保守应用内路径（最多2048字符、可带query，无authority/fragment/反斜杠/控制空白/歧义编码/点路径段/分号），在Spring授权请求内部属性保存目标/创建时间，不发给OP；本地Session+单个state、十分钟精确到期、同JVM单次移除。新授权替换同Session旧请求，callback参数不能覆盖。验证和登记成功后公开 `LasSsoReturnTo.target(request)`，默认自动使用、自有处理器显式采用且不被绕过，见用户确认[ADR-0004](../adr/0004-bind-local-return-target-to-login-transaction.md)。

45项目标/事务及28项签名回调套件（新增59）通过，178server/176starter强制全量零失败/错误/跳过。旧不带时间属性的待完成请求须新登录，shared Session/多实例/真实容器代理/浏览器仍待验。默认实际302而非上文推荐303，导航不重放危险写操作、不产生近期认证证据；详见[English](../integration/login-return-target.md)／[中文](../zh-CN/integration/login-return-target.md)。

2026-10-03 starter 已增加公开 `LasSsoSecurityConfigurer`，默认与应用自有安全链统一使用 PKCE、LAS repository、刷新、当前退出及登记。`LasSsoAuthenticationSuccessHandler` 在 Spring 完成验证/防固定/保存认证后、应用成功 delegate/跳转前登记 sid/sub/最终本地 Session；登记失败删除本地授权、失效认证/Session，并经公开 `LasSsoClientErrorHandler` 返回 `503 SESSION_REGISTRATION_FAILED`，不跳成功页。监听器处理换号/销毁，再次登录替换当前 Session 旧身份索引。

这已修复 B2 的“回调返回后等待下一请求”窗口；14 项签名 Code/PKCE/JWKS 回归覆盖默认/自有链及失败路径。入站 Servlet/监听事件/退出路由是 fixture，真实浏览器/容器未验收；Token 与索引保存不是跨存储事务，退出先于/并发于登记及持久/多实例协调仍未保证。公开配置、兼容与错误语义见[中英文接入契约](../integration/starter-security-composition.md)，B3/B4/B5 不随此修复关闭。

### 3.3 OIDC 密码重新认证（当前 SSO 实现）

2026-10-02 工作区代码与 PostgreSQL 集成测试已验证以下 SSO 行为；LASLOG 的本地证据消费尚未实现，不能把本节当作业务接入验收。

- `prompt=login`、`max_age=0` 要求一次新的密码验证；正整数 `max_age`（秒）复用当前 SSO 浏览器已有、非未来的密码时间，只在年龄超过请求值时要求验证。负数、重复、非整数或超出有符号 64 位范围的值拒绝为 `invalid_request`。
- 在触发页面前保留框架对 client、redirect URI、scope、PKCE、prompt 的验证。`prompt=none` 在未登录或密码时间不符合要求时向已验证回调返回 `login_required` 和原 `state`，不显示交互页。
- 已登录者通过 SSO `/oauth2/reauthenticate` 输入当前账号密码；此 POST 要求 CSRF、Origin/Referer 同源及原有用户/IP 限流。新登录者通过 `/login` 的真实密码认证即可继续，不要求第二次重复输入。
- 原请求参数、账号主体和稳定 `sid` 关联于当前 Spring Session JDBC；每个浏览器只保留一个五分钟事务，后来的不同请求替换旧事务。V14 `oidc_password_challenges` 原子标记完成和消费，即使两个实例持有完成状态副本，也只允许一个免重复验证返回。HTTP Session ID 轮换不改变该稳定 `sid`。
- `auth_time` 为初始 Authorization 的密码认证时间快照（Unix 秒），来自成功密码认证留下的会话证据。ID Token 签发/刷新、浏览器 Cookie、账号级最近认证查询不能补造或推进该时间。其他会话或同浏览器后续密码验证不升级旧 Authorization/Refresh Token family；无可信快照的旧授权省略 `auth_time`。

依赖方仍须校验签名、issuer/audience/subject/sid、state/nonce/PKCE 与当前本地会话的关联，并独立消费一次性的返回事务。仅收到普通登录或刷新 Token 不应自动给所有同主体会话建立高风险操作窗口。LASLOG 的已确认十五分钟、独立会话策略见其 D1/ADR-0023。

2026-10-05开发tuple0.1.0-dev.20261005.1新增public `LasSsoSecurityConfigurer.configure(http, success, Consumer<OAuth2AuthorizationRequest.Builder>)`，可信应用显式prompt/max_age，原双参数保留；SpringPKCE之后执行customizer，再本地state/targetstorage。consumer须保留clientredirectstate noncePKCE，default不透传browserprompt/max_age/auth_time。成功Spring验证/register后可用OidcUser.idToken.authenticatedAt，不新增browserToken字段或自动高风险门禁；RPpending/browser/session关联/十五分钟精确expiry/revoke仍own。两签名callbackfixture与包外本地制品compile验证见[开发交付English](../integration/development-delivery-2026-10-05.md)／[中文](../zh-CN/integration/development-delivery-2026-10-05.md)，非liveLAS/browserE2E。

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

目标 Claims 示例（当前实现仅在有可信密码时间快照时包含 `auth_time`；缺失时依赖方不得推测近期认证）：

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
- Token 的保存、加载、刷新锁与退出必须统一按本地 BFF 登录会话拥有，不能只按 registrationId + 用户共享。同一会话并发请求必须 single-flight，其他请求等待结果，不能并发使用同一旧 RT。初期单实例且重启全失效模式可用会话级进程内锁；后续跨实例共享会话/Token 时必须使用 PostgreSQL 行锁、advisory lock 或等价分布式方案。
- SSO 刷新事务对 Authorization/Token family 加行锁，并在签发前重新校验账号状态、`credentials_version`、全局 `security_epoch` 和客户端 `config_version`。
- 检测到已用 Token 再次出现时，撤销整个 family、对应 BFF 会话，写高危安全事件并触发 Back-channel Logout。
- 改密、密码恢复、账号冻结、账号删除、全局退出和管理员强制下线撤销相关 Refresh Token。

## 5. BFF 本地契约

2026-10-06参考可选 `https` profile 已经默认/自有链实际TLS验证：外配置issuer/client，host-only __Host Cookie SecureHttpOnlyLaxPath/与logoutCookie清理一致。受控Nginx必须清clientForwarded/Prefix等alternate再canonicalhostschemeportIP，appsDB仅private；SSO精确callback仍必需。例子与certificate信任范围：[English](../operations/tls-proxy-acceptance.md)／[中文](../zh-CN/operations/tls-proxy-acceptance.md)。这不提供自动trustedproxyCIDR或productionCA/firewall部署。

2026-10-06真实隔离restore执行新epoch/新持久key，旧SSOCookie/AT/UserInfo/RT/Code拒绝、重启BFF旧pendingcallback拒，重新密码登录/globalBack-channel通过。epoch代次消费fixture验证拒旧reply，不等业务runtime；新JWKS/key不能证明所有旧cachedkid自动失效，production必须清旧BFF会话/验证缓存并fresh对账。见[English恢复契约](../operations/restore-epoch-barrier.md)／[中文](../zh-CN/operations/restore-epoch-barrier.md)。

2026-10-05参考可选durable-revocation profile经用户ADR-0009确认：独立PG/key/AES-GCM task提交独立事务commit再返回，DBlease/8claimretrydead/HTTP200ack/成功擦密文，真实BFF新JVM后SSO原RT拒绝已验。默认Starter仍memory，不替业务建表，不关闭local退出后submitcommit前crash或Sessionindexreplay持久。见[English](../integration/reference-durable-revocation.md)／[中文](../zh-CN/integration/reference-durable-revocation.md)与[ADR-0009](../adr/0009-reference-bff-durable-token-revocation.md)。

2026-10-05用户确认默认单实例B2 sid标记保护：有效Logout即使无匹配也记录已撤销sid，期限为配置本地会话绝对期限/默认14天，markercheck/index同短锁，迟到登记沿原503清理而不执行业务success。同主体其他sid仍可登录；自有adapter自行等效保护。非durable/restart/multiinstance/在途业务取消，决策见[English ADR-0008](../adr/0008-retain-sid-revocations-before-local-registration.md)／[中文](../zh-CN/adr/0008-retain-sid-revocations-before-local-registration.md)。

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

#### 5.2.1 LASLOG 初期重启策略（2026-10-02 已确认，待实现）

- 初期采用单业务实例；进程重启后废弃该实例的旧本地登录状态、Token 会话关联、未完成登录/重新认证事务和十五分钟近期认证证据，不恢复为已登录。
- 用户重新通过顶层 Authorization Code + PKCE 进入。SSO 自身的 JDBC 会话独立存在，仍有效时普通登录可不要求密码；普通回调不得自动把旧近期认证窗口转移到新的业务会话。
- 旧 Cookie 或旧回调不能恢复认证、消费重启前事务或执行高风险操作。若采用持久本地会话存储，仍须通过启动代次或等价机制确保旧状态失效；不得持久恢复登录后只清空内存 sid 索引。
- 重启不等于全局退出，也不改变账号资格、LASLOG 成员/角色。SSO 侧 RT 撤销、签名退出通知和本地可靠撤销作业仍按原契约执行；会话全失效不代表可以丢失已确认的撤销工作。
- 跨重启保持业务登录与多实例会话共享留后续。此处只确定 LASLOG 初期行为，不撤销 SSO 自身 Spring Session JDBC 持久化，也不代表 starter 当前默认实现已满足会话所有权或重启契约。

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

2026-10-03 starter 运行期修复：默认链的 Login、刷新和当前退出统一注入 `LasSsoAuthorizedClientRepository`，不采用无关的 `@Primary` Spring repository。退出先保存本会话 Token 快照，再删除本地授权、清除认证、结束 HTTP Session，随后调用公开 `LasSsoRevocationTaskSink`；sink 故障不恢复登录。该实现没有新增数据库事务或保证任意消费端删除/任务提交原子性；durable adapter 正常返回须代表任务已持久接受，不代表 OP 已撤销。提交失败只记录非秘密失败类别，不报成功；可靠补偿/重试仍由受支持的 adapter 契约完成。

验证：`LasSsoSessionChainIntegrationTest` 的 10 项用实际安全链/manager/repository 和 loopback HTTP Token 服务验证隔离/刷新/退出/故障。认证起点和入站 Servlet 请求为 fixture，不代表签名登录回调、实际 SSO、真实浏览器/容器、持久存储或多实例验收。

### 6.2 退出全部应用

`POST /api/logout/global` 的顺序：

1. 从服务端会话生成指向 Discovery `end_session_endpoint` 的跳转，携带当前或最近有效的 `id_token_hint`、精确登记的 `post_logout_redirect_uri` 和随机 `state`；不得把 ID Token 交给前端脚本。
2. 本地立即撤销会话并清除 Cookie，保留仅用于校验退出返回的短期 `state`。
3. 浏览器以顶层导航进入 SSO。
4. SSO 撤销主会话、全部 Authorization/Refresh Token，并向访问过的 BFF 发送 Back-channel Logout。
5. SSO 只跳回精确登记的 Post Logout URI。
6. BFF 校验退出 `state`，清理残余本地会话。

如果没有可用的 `id_token_hint`，BFF 可以引导用户进入 SSO 账号中心的已认证全局退出接口，但不能接受任意退出后跳转地址。

2026-10-07刷新后hint连续性修复（ADR-0011）：实际.6刷新替换当前IDToken而BFF保initialhint导致400，现V15 HMAC-only `oauth2_id_token_hint_history`在原save事务保previous/newhint→仍存在authorization关联。仅显式id_tokenlookupfallback，ATRTcodegeneral不扩大，成熟client/audience/principal/session/exactreturn仍核；authorization删除cascadehistory，不复活旧auth。preupgradecurrent首次save捕获，已丢更早hint不可恢复，pepper轮换/historysizing另核。规范/红绿/最终两mode混合依据[English](../implementation/refresh-logout-hint-history.md)／[中文](../zh-CN/implementation/refresh-logout-hint-history.md)。

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

2026-10-04 B3 已修复发送侧确认：`BackChannelLogoutDelivery` 仅接受 200/204，starter `LasSsoTokenRevocationQueue` 按 RFC 7009 §2.2 仅接受 200（已无效 Token 也返回 200，忽略响应体）。202、其他未约定 2xx、3xx 和 4xx/5xx 均进入原八次/随机指数退避；两发送器禁止跟随跳转，重试原端点。HTTP 失败记录 `HTTP_<status>`，传输失败记录非秘密类别，耗尽日志不附响应体/异常文本。

服务端八次失败写持久 `dead_lettered_at`，保持 `processed_at` 空；默认 starter 仅停止进程内请求并告警，没有持久死信/重启保证。20 项真实 PostgreSQL/HTTP 投递与 18 项 HTTP 队列回归通过，全量 178 + 71 项零失败/跳过；成功状态不等于任意接收数据库或所有下游已独立验收。详见[English](../integration/logout-delivery-acknowledgements.md)／[中文](../zh-CN/integration/logout-delivery-acknowledgements.md)；B4、持久接受、真实 TLS/代理/消费端仍开放。

随后 2026-10-04 B4 已落地：默认 decoder 固定 issuer/RS256/JWKS/用途并在验签后通过 Nimbus 原生 payload API 校验原始身份类型，避免 numeric sub 提前转换；接收器强制非空 sid/jti、空对象事件、iat/exp 与 120 秒寿命/60 秒偏差。固定 issuer 为配置必需；替换 decoder 须保留密码学及原始类型验证。

用户确认 [ADR-0003](../adr/0003-logout-replay-execution-receipts.md)：Replay Store 增量 `process` 接点区分 SUCCESS/CONFLICT/IN_PROGRESS，保留 accept 源码兼容但接收不再调用；旧 accept-only adapter 未升级时返回 503。默认单 JVM 在执行成功后才缓存回执，失败释放占用保留摘要、相同可重试、冲突拒绝；正在执行 503，成功重传 204 且不再次失效，保留至 exp+60 秒并仍先验 Token。Session adapter 失败保留未结束索引，默认部分失效可重试。日志不附 Token/claim/adapter 错误文本。

33 项 RSA/JWKS + 15 项接收执行 + 5 项重放回归、既有 14 回调/一项 Bean 替换通过，最终强制全量 178 + 117 项零失败/错误/跳过。该 process 不构成跨存储 exactly-once，持久 adapter 租约/事务/崩溃、多实例、真实容器/消费/通知先于登记仍待验收。迁移与边界见[English](../integration/backchannel-logout-receipts.md)／[中文](../zh-CN/integration/backchannel-logout-receipts.md)。

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
