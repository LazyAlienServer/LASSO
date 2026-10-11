# API 参考 — {{VERSION}}

[目录](README.md) · [English](../en/api-reference.md) · **[OpenAPI 3.1](../../api/openapi.json)** · [接入教程](external-integration.md)

**逐项怎么调用：** [API流程F01–F10](api-operations.md)对应全部operationId；界面点击步骤见[操作总目录](operation-index.md)。

## 1. 范围与阅读方式

本文是已实现服务端参考。包内 OpenAPI 列出 JSON 请求/响应字段、必需请求头、状态码、协议端点及认证方式。可导入本地 OpenAPI 查看器或客户端工具，自己填写可信 issuer。服务端没有安装 Swagger UI 或 `/v3/api-docs`。

1. 先读下方认证/错误规则。
2. 按接口分组选择路径，在 OpenAPI 查看命名 schema。
3. 业务网站用 Code + PKCE 及[接入教程](external-integration.md)。账号/管理 API 面向 SSO 自己的浏览器会话，不能用业务 BFF 的用户 Access Token 替代。

清单覆盖已实现账号、管理员和内部资格路由。OAuth 路由由框架处理，Discovery 是实际 URL 权威。本文不是 OpenID 认证或全部可选协议能力声明。HTML 页面、静态资源、离线维护单列，不虚构 JSON 端点。

## 2. 认证、CSRF 与身份

| 接口面 | 认证 | 额外条件 |
| --- | --- | --- |
| 注册、CAPTCHA、开始恢复 | 允许匿名 | CSRF及issuer同源写 |
| `/api/v1/me/**` | SSO浏览器Cookie | 当前凭据/会话有效，无适用限制 |
| `/api/v1/admin/**`读取 | SSO管理员浏览器登录 | `ROLE_SSO_ADMIN` |
| 管理员写入 | 同管理员 | DB核验当前管理员会话、不到15分钟密码证据、CSRF、同源、reason |
| `/internal/v1/principals/{subject}/service-access` | ACTIVE FIRST_PARTY客户端Basic | 独立机器查询；Cookie/用户Bearer不能替代 |
| Token/撤销端点 | OIDC机密客户端Basic | 精确所属client及登记grant |
| `/userinfo` | 有效Access Token、openid | Cookie和ID Token不足 |
| Prometheus | 独立collector Basic | 私有管理listener及monitoring profile |

生产主Cookie是 `__Host-las_sso`（Secure/HttpOnly/Lax、Path=/、无Domain）；恢复Cookie是 `__Host-las_recovery`（Secure/HttpOnly/Strict、10分钟）。**不要假定存在独立CSRF Cookie**：当前用Spring session-based CSRF状态。先GET `/api/v1/csrf`拿headerName/parameterName/token，保留同一cookie jar，写入时用返回的名称和token；登录/会话变化后重新获取。

应用写入要求canonical issuer的Origin；缺Origin时必须有同源Referer。CSRF是另一个检查。`/oauth2/reauthenticate`属于应用密码表单，**不豁免CSRF**。浏览器/API不向任意站点开放CORS。

以 `(issuer, sub)` 定位人类身份，sub是不可变UUID，非可变用户名。SSO不授LASLOG角色/成员/邀请/Minecraft链接/封禁。

## 3. 格式、错误与重试

- 成功JSON直接是DTO或列表，没有统一包装。时间是offset/RFC3339字符串，firstAuthorizedLabel等显示标签不是时间字段。
- 未知JSON请求字段拒绝；UUID路径/头绑定及校验可能先于controller返回框架错误。
- 服务端提供X-Request-Id；短且可解析的UUID请求头可保留。它不是授权/幂等证明。
- 敏感响应一般no-store；不能据此假定所有错误均有相同缓存/响应头。

| 来源 | 实际响应 |
| --- | --- |
| MVC应用异常 | application/problem+json：type/title/status/detail/code/requestId，可有instance/availableAt |
| 会话过期filter | 401 application/json `{"code":"SESSION_EXPIRED"}` |
| 强制改密filter | 403 application/json `{"code":"PASSWORD_CHANGE_REQUIRED"}` |
| 注销限制filter | 403 application/json `{"code":"DELETION_PENDING"}`；HELD也用该code |
| 改密局部异常 | 400/401 application/json `{code}`，错当前密码401 |
| 匿名浏览器认证 | 可重定向/login，不能把所有失败理解为Problem JSON |
| 内部Basic失败 | 空401+WWW-Authenticate；预算429/存储503为安全Problem JSON |
| 登录密码/CAPTCHA失败 | 重定向/login?error或/login?captcha；限流429文本及Retry-After |
| OAuth/OIDC | 保持协议错误/重定向/Bearer challenge，不能套业务JSON |

应用code包括VALIDATION_FAILED400、CAPTCHA_INVALID400、RECOVERY_CREDENTIAL_INVALID400、CAPTCHA_REQUIRED403、REAUTHENTICATION_FAILED401、RECENT_AUTHENTICATION_REQUIRED403、REAUTHENTICATION_CHALLENGE_EXPIRED401、CSRF_INVALID403、USERNAME_UNAVAILABLE409、USERNAME_CHANGE_COOLDOWN409（availableAt）、IDEMPOTENCY_KEY_REUSED409、IDENTITY_SERVICE_UNAVAILABLE503、RATE_LIMITED429。墓碑/管理员/自身目标和client参数有各自400/403/404/409 code；见OpenAPI和guard结果。type当前是固定文档URI前缀，不是配置issuer。

幂等：注册用UUID key；logout-all、注销申请/取消、管理员freeze/unfreeze/revoke-sessions/人工恢复签发、client-secret rotation用非空≤128字符key。作用域actor+operation、24小时，不同请求材料冲突；重试仍要认证/CSRF。一次性秘密不重显。client创建/编辑、恢复码rotation、注销hold/release无此幂等保证。

超时属于结果不确定。不要盲重试秘密创建/轮换、密码操作或refresh。密码、恢复码、Token、Cookie、client secret及完整认证请求响应不能进日志。

## 4. 账号端点清单

下表正文为JSON，登录表单见§7。Key为Idempotency-Key；所有应用写入另要求CSRF/同源。

| 方法/路径 | 正文/参数 | 成功schema/作用 |
| --- | --- | --- |
| GET `/api/v1/csrf` | 无 | 200 CsrfResponse |
| POST `/api/v1/captcha/challenges` | purpose：REGISTER/LOGIN_RISK/RECOVERY_RISK | 201 IssuedChallenge、ALTCHA v2 |
| POST `/api/v1/registrations` | username/displayName/password/captcha；UUID Key | 201 RegistrationResponse；replay200无码；不登录 |
| POST `/api/v1/recovery/sessions` | username/credential/credentialType（RECOVERY_CODE或ADMIN_ISSUED），可选captcha | 204、设置受限恢复Cookie |
| PUT `/api/v1/recovery/password` | newPassword、恢复Cookie | 204、清Cookie，旧密码/会话/授权失效 |
| GET `/api/v1/me/profile` | 无 | 200 Profile |
| PATCH `/api/v1/me/profile` | displayName、可选avatarResourceId | 200 Profile |
| POST `/api/v1/me/username-change` | newUsername/currentPassword | 200 Profile；UUID不变，90天冷却 |
| POST `/api/v1/me/password-change` | currentPassword或temporaryPassword、newPassword | 204、会话/授权失效，重新登录 |
| POST `/api/v1/me/reauthentication` | password | 204、当前会话密码证据/ID轮换 |
| GET `/api/v1/me/recovery-codes/status` | 无 | 200 RecoveryCodeStatus，只有数量 |
| POST `/api/v1/me/recovery-codes/rotate` | 无正文；近期密码 | 200 RecoveryCodeRotation、新一次性码 |
| GET `/api/v1/me/sessions` | 无 | 200 SessionView数组 |
| DELETE `/api/v1/me/sessions/{sid}` | sid | 204/空404；**当前sid触发全部用户SSO会话退出** |
| POST `/api/v1/me/logout-all` | Key，无正文 | 204、全部SSO会话/授权结束 |
| GET `/api/v1/me/authorizations` | 无 | 200 AuthorizationView数组，按client汇总 |
| DELETE `/api/v1/me/authorizations/{clientId}` | clientId | 204、该用户在此client全部授权/consent |
| GET `/api/v1/me/deletion-request` | 无 | 200 DeletionStatus |
| POST `/api/v1/me/deletion-request` | password+Key | 200 DeletionStatus、30天等待 |
| DELETE `/api/v1/me/deletion-request` | JSON password+Key | 204、取消pending/held |

用户名大小写敏感，`[A-Za-z0-9_]{3,24}`，当前及历史名占用。密码12–128 Unicode code points、≤512 UTF-8 bytes，不能等用户名/内置常见密码，空格不自动剥除。Profile显示名trim后非空、≤64 code points，头像仅null或default-alien-01。注册Bean Validation另外限制显示名≤64 UTF-16 units。没有上传/头像管理端点。

非秘密响应示例：

```json
{"username":"ExampleUser","displayName":"Example","avatarUrl":null,"usernameChangeAvailableAt":null}
```

强改登录只允许改密/退出及指定页面；注销pending/held只允许状态/取消/退出。Cookie存在不表示所有/me可用。注册Location指向/api/v1/users/{id}，当前没有该GET路由。管理员创建/唯一管理员reset/恢复epoch轮换是[离线维护](operations.md)，非HTTP管理API。

## 5. 管理员端点清单

读取要管理员浏览器登录，写入使用§2强guard。reason非空≤500字符；自身/管理员/已删除目标按操作拒绝。

| 方法/路径 | 请求 | 成功 |
| --- | --- | --- |
| GET `/api/v1/admin/users` | query/status/cursor(UUID)/limit | 200 UserPage：items/nextCursor/previousCursor |
| GET `/api/v1/admin/users/{userId}` | UUID | 200 UserDetails；墓碑凭据字段null |
| POST `/api/v1/admin/users/{userId}/freeze` | reason+Key | 204、撤销会话/授权 |
| POST `/api/v1/admin/users/{userId}/unfreeze` | reason+Key | 204、不复活旧授权 |
| POST `/api/v1/admin/users/{userId}/revoke-sessions` | reason+Key | 204、目标全退出 |
| POST `/api/v1/admin/users/{userId}/recovery-credentials` | reason+Key | 200 IssuedCredential、30分钟一次性凭据 |
| GET `/api/v1/admin/clients` | query/status/cursor/limit | 200 ClientPage，无秘密 |
| GET `/api/v1/admin/clients/{clientId}` | clientId | 200 ClientView，无秘密 |
| POST `/api/v1/admin/clients` | CreateClient | **200** CreatedClient、secret一次 |
| PATCH `/api/v1/admin/clients/{clientId}` | PatchClient，全组可编辑配置 | 200 PatchedClient、撤销标记/数量/version |
| POST `/api/v1/admin/clients/{clientId}/disable` | reason | 204、client全部授权撤销 |
| POST `/api/v1/admin/clients/{clientId}/enable` | reason | 204、不复活 |
| POST `/api/v1/admin/clients/{clientId}/secret-rotation` | reason+Key | 200 RotatedClient、旧secret/授权失效 |
| GET `/api/v1/admin/deletion-requests` | status/cursor/limit | 200 DeletionPage |
| POST `/api/v1/admin/deletion-requests/{requestId}/hold` | reason/publicSummary/holdUntil | 204、未来≤90天 |
| POST `/api/v1/admin/deletion-requests/{requestId}/release` | reason | 204 |
| GET `/api/v1/admin/audit-events` | action/targetId/from/to/cursor/limit | 200 AuditPage、白名单元信息 |
| GET `/api/v1/admin/security-events` | type/severity/userId/from/to/cursor/limit | 200 SecurityPage |

分页默认50，clamp到1–200。User cursor为UUID，其余为返回的Base64url时间/ID位置；不是签名能力或快照隔离。保持过滤条件、原样传cursor。时间过滤RFC3339带offset。状态：client默认ACTIVE（ACTIVE/DISABLED/ALL）；user ACTIVE/FROZEN/DELETED或空全部；deletion PENDING/HELD/CANCELLED/EXECUTED或空全部。

Client正文：clientName、clientType FIRST_PARTY/REVIEWED_PARTNER、redirectUris1–10、可选postLogoutRedirectUris≤10、scopes包含openid且仅openid/profile、resourceAudiences1–10、可选backchannelLogoutUri、reason；创建可选clientId。PATCH替换整组配置，非JSON Patch。生产要审查HTTPS精确URI，无wildcard/fragment/userinfo。HTTP本地issuer开发profile才允许本机HTTP回调。仅一把active secret，窗口内协调轮换，私密保存首次输出。

## 6. 内部资格 API

GET `/internal/v1/principals/{subject}/service-access`，ACTIVE FIRST_PARTY登记Basic。不需要OAuth client-credentials grant，结果不包含业务权限。

```json
{"issuer":"https://auth.las.test:18443","subject":"019fb42f-5f16-7c31-8ce0-9e54f7441ff0","decision":"ALLOWED","reason":"ACTIVE","stateEpoch":"opaque-epoch","stateVersion":1,"changedAt":"2026-10-09T00:00:00Z"}
```

精确七字段。decision ALLOWED/DENIED；reason ACTIVE、PRINCIPAL_NOT_FOUND、ACCOUNT_DELETED、ACCOUNT_FROZEN、DELETION_PENDING、DELETION_HELD、PASSWORD_CHANGE_REQUIRED、CREDENTIALS_DISABLED、ACCOUNT_STATE_INVALID。未知UUID version0/changedAt=null；epoch缺失/存储503。401凭据、429预算、503不可用不能解释成账号拒绝。版本只在同issuer/subject/epoch比较，消费方自己实现缓存到期、重启/恢复对账及业务执行；没有隐含生命周期事件流/批量接口。

## 7. OIDC、浏览器与管理listener

| 接口面 | 已实现行为 |
| --- | --- |
| `/.well-known/openid-configuration`、`/oauth2/jwks` | metadata/公钥；可信canonical issuer及验证TLS |
| `/.well-known/oauth-authorization-server` | 框架OAuth server metadata |
| `/oauth2/introspect` | 框架POST token/hint、client认证；存储Token metadata或active=false。默认provider非owner-only，不代资格/family截止检查 |
| `/oauth2/authorize` | GET code流程；框架另处理form/consent，应用旧会话续行仅GET，不能宣称POST全部同等已验 |
| `/oauth2/token` | POST code+verifier/精确redirect或refresh；机密Basic，Token只服务端 |
| `/oauth2/revoke` | POST token/hint、client Basic；未知/无效也200；**旧消费RT不保证后继family清理** |
| `/userinfo` | GET/POST有效openid AT，sub须与ID Token一致 |
| `/connect/logout` | 框架GET/POST RP-Initiated浏览器/确认流程，精确登记return；本应用执行用户全部SSO退出 |
| `/login` | GET HTML，POST username/password/rememberDevice/CSRF，风险CAPTCHA |
| `/logout` | POST CSRF SSO退出，此处用户全局；非BFF单应用退出 |
| `/oauth2/reauthenticate` | GET/POST challenge绑定密码表单，5分钟，CSRF/同源/限流 |
| `/actuator/health/liveness`、`/actuator/health/readiness` | Compose私有管理口，readiness含DB，无details |
| `/actuator/prometheus` | monitoring profile/私有listener，独立collector Basic |

HTML页面：/、/login、/register、/password-recovery、/password-change-required、/account、/account/sessions、/account/recovery-codes、/account/authorizations、/account/delete、/account/security、/password-change、/admin/users、/admin/clients、/admin/deletion-requests、/admin/audit；框架consent/logout UI为协议所有。/assets/**及包内WebJars为静态资源。不承诺/api/v1/me会话汇总、上传、Dynamic Client Registration或/api/v1/users/{id} GET。

默认code2分钟、AT15分钟、refresh family固定14天。Family刷新截止不是记录删除时间，不等所有离线验JWT立即失效。auth_time保初始密码快照，refresh不推进，无证据不造。ID Token签名时长以当前框架/配置为准，不把旧设计示例硬编码时长当已验证事实。

刷新响应丢失/后继未知：消费方本地安全失效、保留已接受的已知Token作业、区分协议200与family清理证明，评估有界授权/AT残留。故意重用已轮换A不是常规补偿。历史RT撤销是可选增强，未宣称已实现，也不自动列V1门槛。

Back-channel为**SSO对各登记消费方的出站POST**，参数logout_token，签名typ=logout+jwt、RS256、audience client、sub和适用sid、iat/exp/jti/events。非SSO入站controller。Starter消费方路径/internal/oidc/backchannel-logout；业务站API由该消费方拥有。默认单JVM replay/session和可选参考durable queue不实现LASLOG存储或重启验收。

## 8. 发行检查与支持边界

源码构建检查MVC路径及Java record字段与OpenAPI一致；发行校验核API精确版本、引用和包内hash。这些防漂移检查不替运行时状态/安全测试、协议一致性或消费方联验。

Compose禁止public actuator且不发布DB/app/management口。fixture TLS/browser信任及Windows挂载不代公共CA/Linux权限，见[验收](acceptance.md)。本文记录实际响应不一致，不默默改API行为或新增scope。
