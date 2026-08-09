# LAS SSO 测试与验收计划

- 状态：第一版实施规格
- 范围：SSO、LASLOG BFF 适配、AlienCommon BFF 适配、退出传播和运维恢复
- 依赖：[总纲](../../LAS_SSO_DESIGN.md)及 `docs/` 各分类目录下的详细设计

## 1. 质量门禁

生产发布必须同时满足：

1. 单元、数据库集成、API、OIDC/BFF 契约和浏览器 E2E 全部通过。
2. 没有未处理的 Critical/High 依赖漏洞；例外必须有书面影响分析和到期日。
3. Discovery、JWKS、Authorization Code + PKCE、刷新轮换、单应用退出和全局退出完成真实进程验证。
4. 管理员 bootstrap、首次强制改密和离线重置完成演练。
5. PostgreSQL 备份能够在空环境恢复。
6. 日志检查确认没有密码、恢复码、客户端密钥、Token 或 Cookie。
7. 关键负向测试通过，不能只证明正常路径可用。
8. 所有 `.example`、默认 Secret 和测试客户端都不在生产配置。

## 2. 测试层级

| 层级 | 目标 | 建议工具 |
|---|---|---|
| 单元测试 | 规则、状态机、Claims、错误映射 | JUnit 5、AssertJ、property-based library |
| 数据库集成 | 迁移、唯一约束、事务、并发 | Testcontainers PostgreSQL |
| PostgreSQL 短期状态集成 | TTL 判定、原子限流、锁、单次消费和分批清理 | Testcontainers PostgreSQL |
| Spring Security 集成 | 登录、CSRF、会话、OAuth/OIDC 端点 | Spring Boot Test、MockMvc/WebTestClient |
| 协议契约 | Discovery、Token/Claims、BFF 一致性 | 独立黑盒测试包 |
| 浏览器 E2E | 跳转、Cookie、页面状态、退出 | Playwright |
| 安全测试 | SAST、依赖、DAST、秘密扫描 | 成熟扫描器组合 |
| 运维演练 | 备份恢复、换钥、管理员恢复、故障 | 隔离测试环境 |

集成测试使用真实 PostgreSQL，不以 H2 或内存 Map 代替最终一致性约束。

## 3. 测试数据与时间

- 每个测试生成独立 UUID v7 和不重复 client ID。
- 用可注入 `Clock` 测试 2 分钟 code、15 分钟 AT、RT family 绝对 14 天、BFF 会话 14 天、90 天 SSO 记住会话、30 天注销等边界。
- 密码和恢复码测试值只存在测试资源，不复用生产 Secret。
- 日志测试使用特殊 canary 值，测试后扫描所有日志确认 canary 不存在。
- 测试数据库迁移从空库开始，也从上一发布 schema 升级。
- 并发测试固定随机种子并保存失败重现信息。

## 4. 身份与数据模型

### 4.1 UUID 与用户

- 新注册 `users.id` 是有效 UUID v7。
- 两个连续生成 ID 不重复；并发生成不重复。
- OIDC `sub` 与数据库 UUID 字符串完全一致。
- `LasUserPrincipal.getName()`、`Authentication.getName()`、`OAuth2Authorization.principalName` 与 `sub` 都是同一 UUID v7；修改用户名后保持不变。
- 业务代码没有按用户名、显示名或邮箱建立永久身份映射。
- 数据库和响应中不存在 email 字段/claim/scope。

### 4.2 用户名

参数化测试：

```text
有效：abc, Alice, ABC_123, 1234, a_B
无效：ab, 25字符, 连字符, 空格, 中文, emoji
```

验收：

- `Alice`、`alice` 和 `ALICE` 可以分别注册并登录，数据库中是三个独立用户名。
- 登录必须精确匹配大小写；`Alice` 的密码不能配合 `alice` 登录 `Alice` 的账号。
- 数据库列和唯一索引使用确定性的大小写敏感比较，不存在 `citext` 或 `lower(username)` 唯一索引。
- 当前用户名数据库唯一。
- 历史用户名按精确大小写永久占用；历史 `Alice` 不阻止独立的 `alice`。
- 普通用户 90 天内不能二次修改。
- 只把 `Alice` 改为 `ALICE` 也算一次修改并触发 90 天冷却。
- 到第 90 天边界允许修改。
- 两个并发事务抢同一用户名只有一个成功。
- 修改失败不关闭旧用户名。
- 历史用户名不能登录，也不会被分给另一个用户。

### 4.3 状态机

- `account_status: ACTIVE -> FROZEN -> ACTIVE` 合法。
- `deletion_status: NONE -> PENDING -> CANCELLED` 合法，取消后账号状态仍由 `account_status` 决定。
- 冻结账号可以同时为 `deletion_status=PENDING/HELD`，不会因取消注销而自动解冻。
- 注销执行后 `account_status=DELETED` 且 `deletion_status=EXECUTED`。
- `DELETED` 不能恢复到 Active，UUID 不复用。
- 唯一管理员不能经 Web 进入 `FROZEN`/`DELETED` 或失去管理员身份。
- 数据库唯一部分索引阻止第二个 `is_sso_admin=true`。

## 5. 注册与 CAPTCHA

正常路径：

- `REGISTER` Challenge 成功创建账号和 8–10 个恢复码。
- Challenge、用户、用户名、密码凭据和恢复码在一个事务提交。
- 一次性响应只出现一次恢复码。

负向路径：

- 无 Challenge、过期、错误签名、错误用途、重复使用均失败。
- ALTCHA v2 签名元数据中的 challenge ID、用途和提交 IP 网段任一不匹配均失败。
- `LOGIN_RISK` Challenge 不能用于注册。
- 并发重复 Challenge 只有一次成功。
- 用户名冲突不创建孤立用户或恢复码。
- 密码策略失败不消耗用户名。
- PostgreSQL 短期状态写入失败时注册失败关闭。
- IP 小时/每日阈值和 `Retry-After` 正确。
- CAPTCHA 失败仍保留独立事务中的限流计数；第 6 次同网段小时请求返回 `429`。
- 相同幂等键和请求体重试不再次返回恢复码；同一键配不同请求体返回 `IDEMPOTENCY_KEY_REUSED`。
- 修改客户端时间、请求 ID 或隐藏字段不能绕过 TTL/用途。

## 6. 密码与登录

### 6.1 密码

- 11 字符拒绝，12 和 128 字符接受，129 拒绝。
- 空格和长密码短语不被裁剪。
- 常见/泄漏密码拒绝。
- 密码不要求字符种类组合。
- 存储值是 Argon2id/批准的 `PasswordEncoder` 格式，不等于明文。
- 相同密码的两个用户哈希不同。
- 参数升级后旧哈希成功登录会重新哈希。
- Argon2 基准 p95 低于最终门限，错误请求不能绕过限流耗尽内存。

### 6.2 登录

- 正确用户名/密码成功，`amr=["pwd"]`、`acr="urn:las:loa:1"`。
- 用户不存在和密码错误的外部提示/状态一致，耗时差异在可接受范围。
- 历史用户名、冻结、删除账号不能正常登录。
- 登录成功轮换 SSO Session ID。
- 未选择记住和选择记住的 idle/absolute 到期准确。
- absolute 到期不能因持续访问无限延长。
- 达阈值后要求 CAPTCHA，继续失败短期锁定。
- 管理员不能获得 90 天记住会话。
- 管理员 45 分钟 idle、10 小时 absolute、15 分钟近期认证按最终配置测试。
- 近期认证端点验证当前密码、轮换会话 ID、更新 `last_password_auth_at`，不自动执行此前失败的写操作。
- 任何会话的 `credentials_version` 或 `security_epoch` 不匹配时立即失效。
- 授予管理员身份会撤销目标既有普通/记住会话；只有重新登录产生的 `admin_session=true` 短会话可调用管理员端点。

## 7. 恢复

### 7.1 用户恢复码

- 每个码只使用一次；并发提交只有一个成功。
- 错误码、错误用户名、过期恢复会话和错误 CSRF 均失败。
- 恢复用户名精确区分大小写；`Alice` 的恢复码不能用于 `alice`。
- 恢复会话只能调用改密/退出，不能调用 `/me` 完整功能或 OIDC 授权。
- 恢复成功增加 `credentials_version`。
- 旧密码、已使用的恢复码、旧会话、旧 Authorization 和旧 Refresh Token 全部失效；同批其他未使用恢复码继续有效。
- 恢复成功不自动建立完整登录。
- 重新生成恢复码时旧批次在同一事务全部失效。
- 响应中断后不能通过 API 再取回同一批明文码。

### 7.2 管理员人工恢复

- 只有管理员且有 15 分钟近期认证才能签发。
- 原因必填，凭据最长 30 分钟、单次使用。
- 管理员不能为自己签发。
- 管理员不能查看用户密码/恢复码或直接设置密码。
- 签发、成功、失败、过期、撤销均有审计关联。

## 8. 管理员 bootstrap 与密码重置

### 8.1 Bootstrap

- 无管理员时可按现有 UUID 授予。
- 空库模式可创建 UUID v7 用户并授予。
- 并发执行只有一个成功。
- 已存在管理员时再次执行失败且不修改任何用户。
- 命令没有 `--password`，终端隐藏输入并要求二次一致。
- 临时密码 24 小时过期。
- 初次登录只允许改密/退出。
- 改密后需要新会话并引导生成恢复码。
- stdout、stderr、应用日志、shell 历史和审计中没有临时密码或哈希。

### 8.2 `reset-admin-password.ps1`

静态/行为验收：

- 脚本只调用 Java 维护命令，不含哈希算法或 SQL。
- 仅接受非秘密的 `-Profile`，无 `-UserId` 或 `-Password`。
- Java 命令自动查询并显示唯一管理员的用户名与 UUID，确认前不修改数据。
- Java 命令使用 Web 应用相同 `PasswordEncoder`。
- 管理员不存在、非活动或确认期间发生变化时失败。
- 密码策略失败返回非零且不修改凭据。
- 成功后设置 `ACTIVE`、清除临时到期时间和锁定、凭据版本递增。
- 全部会话/Authorization/Refresh Token/恢复码失效。
- outbox 与密码更新处于同一事务，失败时整体回滚。
- 审计事件 `ADMIN_PASSWORD_RESET_BY_MAINTENANCE` 存在且无秘密。
- 旧管理员 Cookie 和 Refresh Token 不能继续使用。

## 9. API 与浏览器安全

每个写端点测试：

- 无 CSRF、错误 CSRF、跨 Origin 请求拒绝；
- 正确同源 CSRF 通过；
- JSON 多余字段、类型错误、超长字段拒绝；
- 错误响应符合 Problem 格式且有 `requestId`；
- `Cache-Control: no-store`；
- 不在错误中泄漏栈、SQL、host、账号额外状态或秘密。

Cookie 验收：

```text
__Host- 前缀
Secure
HttpOnly（会话/恢复）
Path=/
无 Domain
SameSite 符合契约
```

浏览器 E2E 确认 LocalStorage、SessionStorage、IndexedDB、URL、DOM 和前端日志中没有 Token。

安全 Header 测试包括 CSP、HSTS、`nosniff`、Referrer Policy、Frame Ancestors。使用测试页面验证 SSO 不能被 iframe。

## 10. OIDC 协议

### 10.1 Discovery/JWKS

- Discovery `issuer` 与配置完全一致。
- 只发布实际启用的 grant/response/algorithm。
- `S256` 存在，`plain` 不作为允许方法。
- JWKS 只有公钥，无私钥参数。
- 当前 `kid` 稳定，重启不变化。
- 缺失私钥时生产启动失败，不随机生成。

### 10.2 Authorization Code

正常：

- Confidential Client + Authorization Code + PKCE S256 成功。
- Code 2 分钟到期、只能使用一次、绑定 client/redirect/code challenge。
- ID Token `iss/aud/nonce/sub/sid/auth_time/acr/amr` 正确。

负向：

- 缺失/错误 `state`、`nonce`、`code_verifier`、`redirect_uri`、client secret 失败。
- `code_challenge_method=plain` 失败。
- Code 跨 client 使用失败。
- Code 重放失败。
- Redirect URI 子路径、大小写、查询变化、通配域、HTTP 生产地址失败。
- Implicit、Password、Device、Client Credentials、Dynamic Registration 未启用。
- 未审批 scope 返回标准 `invalid_scope`。

### 10.3 Token

- ID Token `aud=client_id`。
- LASLOG AT 仅有 `urn:las:resource:laslog` audience；AlienCommon 同理。
- Resource Server 拒绝错误 Issuer、Audience、签名、算法、过期、未来 `nbf` 和 ID Token。
- Access Token 不含邮箱、Minecraft、QQ、管理员/业务角色。
- Refresh Token family 从首次 Code 授权起绝对 14 天到期，每次刷新轮换但不顺延。
- 到 family 第 14 天边界后刷新失败；有效 SSO 会话可通过新的顶层 Authorization Code 流无密码重新授权。
- 多进程/多实例并发刷新经 PostgreSQL 分布式 single-flight 只有一个旧 Token 请求，不能只靠进程内锁。
- SSO 刷新事务的数据库行锁下会重新检查账号状态、凭据版本、全局 epoch 和客户端配置版本；与冻结/改密/全局退出并发时不能提交新 Token。
- 真实重复使用触发整个 family 撤销和安全事件。
- 改密、冻结、删除和全局退出后刷新失败。
- 用户能查看合作方 consent；撤销单个客户端只终止该客户端，不退出其他应用或 SSO 主会话。

## 11. BFF 契约

同一黑盒契约套件分别运行在：

```text
las-sso-spring-boot-starter
las-sso-django
```

必须一致验证：

- 登录 URL 生成和站内 `returnTo`；
- 回调 `state`/`nonce`/PKCE/Issuer/Audience；
- `(issuer, sub)` JIT 唯一映射；
- 用户名变化更新快照而不创建新用户；
- 完整登录同步资料，持续会话最多每 24 小时调用一次 UserInfo；同步仍只按 `(issuer, sub)` 定位用户；
- Token 仅在服务端存储；
- `/api/session` 未登录/已登录响应；
- 当前应用退出保留 SSO 主会话；
- 全局退出清除所有本地会话；
- Back-channel Logout `sid` 清理；
- Refresh single-flight 与失败恢复；
- 每个受保护请求检查 `auth_fresh_until`；AT 到期且刷新失败时不能仅凭本地 Cookie 继续，SSO 故障返回服务不可用；
- SSO 不可用时返回服务不可用而不是封禁/密码错误。

适配库契约主版本不兼容时，业务应用启动测试必须失败并给出明确错误。

## 12. 退出

### 12.1 当前应用退出

- LASLOG 当前退出只清除 LASLOG 会话/授权。
- AlienCommon 会话和 SSO 主会话继续有效。
- 再次进入 LASLOG 经顶层 Authorization Code 流可无密码返回。
- SSO/Revocation Endpoint 不可用时，LASLOG 仍先清除本地会话和 Cookie；远程撤销进入重试，不会导致用户保持登录。

### 12.2 全局退出

- 任一 BFF 发起 RP-Initiated Logout 后 SSO 主会话失效。
- 两个 BFF 均收到有效 Logout Token 并按 `sid` 清除本地会话。
- Logout Token 验证 `typ`、签名、`iss`、`aud`、`iat`、`exp`、`jti`、`events`、`sid`，并拒绝 `nonce`。
- 完全相同的有效 Logout Token 使用同一 `jti` 重传时返回已缓存的成功结果且不产生副作用。
- 相同 `jti` 但 Token 内容不同返回 `400`；已退出会话收到合法重复通知仍返回成功。
- BFF 临时 `5xx` 时 SSO 延迟重试。
- Back-channel 永久失败时，授权撤销在 Access Token/刷新边界最多约 15 分钟阻断会话。
- 从用户点击全局退出到两个正常 BFF 清除会话的目标为数秒，测试记录实际值。

### 12.3 管理员强制下线

与全局退出相同，并额外验证管理员审计、原因和目标 UUID。冻结用户后即使业务 Cookie 残留，也不能获得新 AT/Refresh Token。

## 13. 管理员功能

- 用户按用户名/UUID查询、cursor 分页和过滤正确。
- 冻结/解冻幂等，原因和审计必填。
- 唯一管理员不能冻结、删除、解任自己。
- 客户端 Redirect/Logout/Back-channel URI 精确校验。
- 第一方/合作方 consent 配置限制正确。
- Client Secret 只显示一次，摘要存储。
- 每个客户端始终只有一把活动 Secret；维护窗口轮换后旧密钥立即失败，`config_version` 递增并撤销既有授权/会话。
- 缩小 scope/audience 或禁用客户端会撤销现有授权；合作方扩大 scope 会要求重新 consent；仅改展示名不强制退出。
- 网页 API 不存在 JWT 签名私钥读取/上传/删除端点。
- 管理员自由文本按不可信内容转义，不能存储型 XSS。

## 14. 注销

- 申请必须重新输入密码。
- 申请后 `deletion_status=PENDING`、执行时间准确为 30 天并全局退出，`account_status` 不被注销流程覆盖。
- 等待期只允许取消/确认页面，不允许普通 OIDC 登录。
- 30 天内重新认证可取消。
- 到期任务幂等执行，凭据/会话/授权删除，用户 UUID 墓碑保留。
- 重跑任务不创建第二个事件或恢复已删资料。
- 管理员暂缓必须有原因、最长 90 天，到期自动释放；释放后任务可继续执行。
- 历史业务内容不由 SSO 直接删除。
- 冻结/争议账号按最终运营保留规则测试。

## 15. 审计、隐私与日志

对每种管理员写操作验证：

```text
actor UUID
actor session
action
target
reason
result
request_id
timestamp
```

安全事件至少覆盖登录失败、恢复、改密、Token 重用、冻结、删除、全局退出。

自动扫描应用、代理、审计和错误追踪输出，确认不存在：

```text
测试密码 canary
恢复码 canary
Client Secret canary
Authorization Code
Access/Refresh/ID/Logout Token
Cookie / Set-Cookie
完整 IP
```

验证保留任务在边界前不删除、边界后删除，并保留永久用户名历史和 UUID 墓碑。

## 16. 故障与恢复演练

### 16.1 依赖故障

- PostgreSQL 断开：Readiness 失败，新认证/写操作停止，没有脏提交。
- 短期状态表缺失/锁超时：注册和恢复失败关闭；登录不绕过限流；并发刷新不误报重用。
- BFF Back-channel `5xx`：outbox 重试、监控和积压告警。
- 系统时钟偏移：超过阈值告警；Token 校验只允许 60 秒。
- 磁盘接近满：告警且不会因日志无限写入损坏数据库。

### 16.2 备份恢复

在空环境恢复 PostgreSQL 后，按运维手册生成新安全 epoch 和新签名 key，再运行完整冒烟。验证：

- schema 版本；
- 用户/管理员；
- Client 配置；
- 历史用户名；
- 恢复后生成新 `security_epoch`，恢复前所有会话/Authorization/RT 即使数据库记录为 Active 也不能使用；
- 使用新签名 key/`kid`，BFF 拒绝灾难前旧 Token；
- PostgreSQL 短期状态表和两个 BFF 本地会话清空，全部用户重新登录；
- outbox、注销任务和清理任务；
- 两个 BFF 新登录/退出。

### 16.3 换钥

- 常规轮换期间新旧 JWKS 同时可见。
- 切换后新 Token 使用新 `kid`，旧 Token 仍可验证到期。
- 删除旧公钥前门禁能发现仍未过期的旧 Token。
- 泄漏演练验证 BFF 可拒绝指定旧 `kid` 并强制全局重新登录。

## 17. 性能与稳定性

在生产同规格或可换算环境：

1. 50 并发登录，记录 Argon2、数据库和响应 p50/p95/p99。
2. 20 requests/s 混合负载运行 10 分钟。
3. 100 个会话同时刷新，适配库不重复使用旧 RT。
4. 1,000 用户全局下线/冻结事件不会造成 outbox 永久积压。
5. 一个 BFF 持续失败时，重试有退避且不影响另一个 BFF。
6. 日志和审计写入不会耗尽磁盘或显著拉高登录延迟。

这不是容量承诺；测试结果写入发布记录，实际阈值根据部署硬件确定。

## 18. 用户验收场景

### 18.1 普通用户

用户能够：

- 注册并保存一次性恢复码；
- 使用用户名/密码登录两个业务网站；
- 修改显示名、头像和冷却期允许的用户名；
- 查看并撤销会话；
- 退出当前应用而保留另一个应用；
- 全局退出；
- 修改/恢复密码并让旧会话失效；
- 重发恢复码；
- 查看并撤销已授权合作网站；
- 申请并取消 30 天注销。

用户不能：

- 查看 Token 或 Client Secret；
- 修改业务权限、Minecraft 绑定或稿件权限；
- 使用历史用户名登录；
- 绕过 90 天用户名冷却。

### 18.2 唯一管理员

管理员能够：

- 首次登录强制改密并保存恢复码；
- 查询、冻结、解冻、强制下线用户；
- 经人工核验签发一次性恢复凭据；
- 创建/禁用 OIDC Client、管理精确地址/scopes/audience、轮换 Client Secret；
- 查看安全事件和审计。

管理员不能：

- 查看/设置用户密码；
- 查看用户恢复码；
- 冒充用户；
- 冻结/删除/解任自己；
- 在网页中操作 JWT 签名私钥、数据库、备份或部署权限。

## 19. 发布证据

每次候选发布保存：

```text
commit/build 标识
依赖锁定清单
数据库迁移版本
自动测试摘要
OIDC/BFF 契约测试摘要
安全扫描摘要
性能测试摘要
备份/恢复演练时间
冒烟测试操作者与结果
已接受例外及到期日
```

没有可观察证据时不能把功能标记为“已验证”。
