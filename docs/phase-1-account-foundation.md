# LAS SSO Phase 1：账号基础实施记录

- 实施日期：2026-08-02 至 2026-08-03
- 状态：账号领域、管理员 bootstrap、密码登录保护、强制改密、公开注册、恢复码找回、ALTCHA v2、幂等和 PostgreSQL 限流闭环完成
- 工程：[las-sso-server](../las-sso-server/README.md)

## 1. 已实现

- 密码规则：12–128 个 Unicode code point、UTF-8 最多 512 bytes；不裁剪空格、不做 Unicode 规范化、不要求字符种类组合。
- 拒绝与当前用户名完全相同的密码，并加载本地常见/泄漏密码拒绝表。
- 新密码使用 Argon2id：16-byte salt、32-byte hash、19 MiB memory、2 iterations、parallelism 1；哈希以 `{argon2id}` 标识保存。
- 使用 Bouncy Castle 1.84 提供 Spring Security Argon2 实现。Spring Security 官方说明 Argon2 encoder 需要 Bouncy Castle，并建议按部署环境调优：[Password Storage](https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html)。依赖版本依据 [Bouncy Castle Java downloads](https://www.bouncycastle.org/download/bouncy-castle-java/)。
- 注册领域事务同时创建 `users`、`usernames`、`password_credentials` 和 10 个恢复码；数据库冲突回滚整个事务。
- 恢复码格式为 `LAS-XXXX-XXXX-XXXX`，排除易混淆字符；数据库仅保存 `HMAC-SHA-256(recovery pepper, normalized code)`。
- recovery pepper 通过 `LAS_SSO_RECOVERY_PEPPER` 注入；local/test 仅使用明确标记的非生产值。
- 管理员 bootstrap 支持创建数据库中的第一个用户，或按 UUID 提升现有普通用户。
- bootstrap 使用 PostgreSQL transaction advisory lock 和唯一管理员部分索引双重防并发。
- 临时管理员密码 24 小时过期，凭据状态设为 `PASSWORD_CHANGE_REQUIRED`，并写管理员维护审计。
- 提升现有用户时递增 `credentials_version`，废除恢复码、撤销活动会话并删除既有 Authorization。
- `PASSWORD_CHANGE_REQUIRED` 主体可以验证临时密码，但在 principal 上明确携带受限状态；后续 Web 安全层必须只允许改密和退出。
- 提供 `scripts/bootstrap-admin.ps1`，密码只从交互式隐藏输入读取，不接受密码参数或环境变量。
- 提供 `scripts/reset-admin-password.ps1`，自动定位并显示唯一管理员，确认后隐藏读取最终密码；Java 维护事务使用同一密码策略和 Argon2id，设置 `ACTIVE`、递增凭据版本、清除锁定并撤销恢复状态、会话与 Authorization，同时写审计、安全事件和全局退出 outbox。
- Flyway V4 增加登录失败窗口时间和锁定索引。
- 显式配置 `DaoAuthenticationProvider`；不存在的用户名仍由 Spring Security 执行假密码哈希，外部统一重定向到 `/login?error`。
- 普通账号 30 分钟内累计 10 次失败锁定 15 分钟；管理员 15 分钟内累计 5 次失败锁定 30 分钟。锁定写入 `security_events`，成功登录清零计数。
- 临时密码登录统一跳转 `/password-change-required`；受限 principal 访问其他页面会被重定向，访问其他 API 返回 `PASSWORD_CHANGE_REQUIRED`。
- `POST /api/v1/me/password-change` 支持普通密码和临时密码改密，保留 CSRF 防护。
- 改密事务验证当前凭据和新密码策略，禁止复用旧密码，更新 Argon2id 哈希、递增 `credentials_version`、清除锁定、废除恢复凭据、撤销活动会话和 Authorization，并写安全事件；成功后注销当前 HTTP 会话。
- Spring Boot 4 的 MockMvc 支持来自独立 `spring-boot-webmvc-test` 模块：[Boot 4 test modules](https://docs.spring.io/spring-boot/reference/testing/test-modules.html)。
- Flyway V5 增加限流桶、CAPTCHA challenge 和恢复会话表；限流使用 PostgreSQL UPSERT 原子计数，challenge 使用条件更新保证只消费一次。
- 短期状态键使用独立 pepper 做 HMAC-SHA-256；过期数据由带 advisory lock 的 5 分钟定时任务分批清理。
- `POST /api/v1/captcha/challenges` 使用官方 ALTCHA Java 库生成签名 PoW v2 challenge，并绑定用途、UUID v7 challenge ID、5 分钟 TTL 和客户端 IP 网段摘要。
- `POST /api/v1/registrations` 接入 CSRF、Origin/Referer 与 Issuer 同源校验、小时/每日/全局原子限流、challenge 原子消费、账号事务和 RFC 9457 风格错误响应。
- V6 `registration_idempotency` 通过 advisory lock 串行同一 UUID key；24 小时重试返回既有账号但不再次返回恢复码，异请求复用同一 key 返回冲突。
- 登录失败按客户端 IPv4 `/24`、IPv6 `/56` 网段写入 PostgreSQL 原子固定窗口；默认 20 次/15 分钟后要求 `LOGIN_RISK` ALTCHA，50 次/30 分钟后返回 `429` 和 `Retry-After`。
- 风险登录在密码认证前验证与网段、用途绑定的一次性 ALTCHA payload；缺失、错误、过期或重放均不能继续密码认证。
- 普通用户名/密码失败继续使用统一提示，并以 Servlet 异步上下文和独立定时调度器加入 150–450ms 随机抖动；不使用 `Thread.sleep` 占用认证工作线程。阈值、窗口和抖动范围均位于 `las.sso.login-risk` 配置段。
- 登录成功和 `POST /api/v1/me/reauthentication` 在当前 Spring SSO Session 中记录最近密码认证时间；重新认证会再次读取当前密码哈希、轮换 Session ID，并提供 15 分钟敏感操作门禁。正式 `user_sessions` 持久化映射仍留在后续会话管理阶段。
- `GET /api/v1/me/recovery-codes/status` 只返回可用数量和最近生成时间；`POST /api/v1/me/recovery-codes/rotate` 要求近期认证，并在同一事务废除旧批次、写入 10 个新码和安全事件，明文只在本次 `no-store` 响应出现一次。
- `/account/recovery-codes` 按个人中心设计稿统一承载恢复码状态、重新生成与普通用户改密弹层；`/account/security` 和 `/password-change` 仅作为旧入口跳转，不再维护第二套普通账号安全页。临时密码会话仍进入 `/password-change-required` 的受限改密版本。页面包含字段级错误、提交状态、密码可见性控制、近期认证渐进披露以及恢复码复制/下载反馈。
- `POST /api/v1/recovery/sessions` 精确匹配大小写敏感用户名，并以原子条件更新消费恢复码；成功只签发 10 分钟、数据库仅保存 HMAC 摘要的 `__Host-las_recovery` 受限 Cookie，不建立完整登录。
- 恢复尝试按用户名、IP 网段和全局计数；低风险时不增加人机验证步骤，用户名 15 分钟内超过 3 次或 IP 网段超过 10 次尝试后要求一次性 `RECOVERY_RISK` ALTCHA，硬上限继续返回 `429`。
- `PUT /api/v1/recovery/password` 在单一事务中设置新 Argon2id 密码、递增 `credentials_version`、消费全部恢复会话和本次恢复码、废除人工恢复凭据、撤销 `user_sessions` 与 Authorization，并写安全事件及 `GLOBAL_LOGOUT_REQUESTED` outbox；同批其他未使用恢复码继续有效。
- 已认证 Spring Session 在每个后续请求核对数据库 `credentials_version` 和账号状态；密码恢复或改密后的旧 principal 会被清理并要求重新登录。正式会话元数据、列表和逐个撤销仍等待 `user_sessions` 映射阶段。
- `/password-recovery` 实现两步渐进界面：先验证恢复码，再设置并确认新密码；风险 ALTCHA 按需展开，完成后不自动登录，并明确引导重新登录和生成新恢复码。

## 2. 当前验证

测试覆盖：

- 密码长度、空格保留、常见密码和用户名相同密码拒绝；
- 10 个恢复码唯一性、格式和 HMAC 稳定性；
- 注册事务、UUID v7、Argon2id 哈希匹配和恢复码数据库数量；
- 完全相同用户名冲突不会留下孤立用户；
- 提升现有管理员时临时密码状态、角色和恢复码撤销；
- 唯一管理员离线重置直接启用最终密码，并原子撤销恢复状态、会话和 Authorization；
- 全新数据库创建首个管理员、24 小时到期时间和审计记录；
- 既有 Phase 0 Authorization、UUID principal 和 Logout Token 测试。
- 真实表单登录成功/失败处理、数据库失败计数清零；
- 普通账号达到阈值后锁定及安全事件；
- 临时密码登录强制跳转和受限主体路由拦截；
- 普通/临时密码改密、Argon2id 新哈希、凭据版本升级和恢复码撤销。
- 20 路并发下限流计数无丢失、只放行前 5 次；challenge 只能消费一次且过期后拒绝；清理任务只删除过期短期状态。
- 登录 IP 前置阈值、`LOGIN_RISK` 缺失/成功/重放、IP 硬拒绝与 `Retry-After`；非阻塞抖动调度不会同步等待请求线程。
- 恢复用户名大小写、统一无效凭据响应、CSRF/Origin、受限 Cookie 属性、一次性完成、密码与凭据版本更新、旧会话/Authorization/恢复码撤销及全局退出 outbox。
- 同一恢复码并发提交只有一个成功；恢复风险阈值前三次不要求 CAPTCHA，达到阈值后必须完成与网段和用途绑定的 `RECOVERY_RISK` challenge。

2026-08-04 完整测试结果：SSO 服务 9 个测试套件、58 个测试，starter 5 个测试套件、15 个测试；`failures=0`、`errors=0`、`skipped=0`。全局退出覆盖 RP-Initiated Logout 的登记回调、`state` 返回、授权与会话撤销、SSO 会话已过期时按已验证 ID Token `sub` 撤销，以及历史 Authorization 缺少 `sid` 时以 `sub` 发送 Back-Channel Logout 的兼容场景；刷新令牌覆盖轮换、旧 Token 重用、14 天绝对期限、账号/凭据/全局 epoch/客户端版本失效、部署期旧 Token 导入和双请求并发；starter 另覆盖 `auth_fresh_until` 到期边界阻断；管理员测试覆盖角色与近期认证门禁、用户查询、冻结/解冻、强制下线、审计、安全事件和 outbox。

本机参数下集成用例中的首次 Argon2 路径约 1 秒，JIT/类加载预热后的注册路径约 0.1 秒。该数字只说明开发机测试结果，不替代生产同规格硬件的 p50/p95、并发和内存基准。

## 3. 当前接入进展与尚未完成

- 已增加 `/account` 个人中心，并连接现有账号安全页。
- 已增加初版 `las-sso-spring-boot-starter` 与最小 `las-sso-reference-bff`。
- 已通过真实浏览器验证 Authorization Code + PKCE、`state`、`nonce`、服务端 Token 交换、本地 BFF Session 和受保护页面。
- starter 已在每个受保护请求上调用服务端授权客户端管理器，支持 AT 到期前刷新、单 JVM/同 Session 刷新合并，以及刷新失败后的本地会话失效。
- 本地 `laslog-bff` 同时登记 `localhost:8088` 与 `127.0.0.1:8088` 的精确回调，避免开发主机名不同导致授权入口 `400`。
- JDBC Authorization 使用同一套受限 Jackson mapper 读写 LAS principal，避免授权码兑换时因自定义主体无法反序列化而返回 `500`。
- 已使用 Spring Session JDBC 将 HTTP Session 持久化到 PostgreSQL，并将其与 `user_sessions` 安全元数据关联；个人中心支持会话列表、逐个撤销和退出所有设备，`last_password_auth_at` 也会持久化。
- 普通会话为 24 小时滑动闲置期/14 天绝对上限；受信任设备为 30 天滑动闲置期/90 天绝对上限；管理员会话保持 45 分钟闲置期/10 小时绝对上限且不能选择信任设备。
- Authorization 已关联 SSO `sid`，ID Token/AT 会携带 `sid`；全局退出、单会话撤销、改密和管理员重置会写入可认领、可重试的 Back-Channel Logout outbox。
- Flyway V9 以带独立 pepper 的 HMAC 保存 Refresh Token 历史，不额外保存明文；SSO 刷新事务使用 PostgreSQL 行锁执行 14 天 family 绝对期限和安全版本检查。成功刷新原子轮换 Token；旧 Token 重用会撤销整条 family、写 `CRITICAL` 安全事件并投递 Back-Channel Logout，安全状态不匹配会写 `WARNING` 事件并投递退出。
- starter 已实现 `POST /api/auth/logout/global`：从 Discovery 读取 `end_session_endpoint`，服务端携带 `id_token_hint`、精确登记的退出回调和随机 `state` 发起 RP-Initiated Logout；业务会话立即失效，回调 `state` 短期、单次校验，ID Token 不进入前端脚本。SSO 使用标准 OIDC Logout 校验，并在成功后撤销该主体全部会话和 Authorization。
- starter 在每个受保护请求完成刷新尝试后重新读取服务端授权客户端，并以当前 Access Token `expiresAt` 作为 `auth_fresh_until` 强制边界；`expiresAt <= now`、Token 缺失或到期时间缺失都撤销本地认证并返回 `401 SESSION_EXPIRED`，SSO 暂时故障仍返回 `503 SSO_UNAVAILABLE` 且不误删会话。
- starter 已提供 Back-Channel Logout Token 校验、重放防护和本地 Session 失效。当前会话注册表与重放缓存仍是单 JVM 实现。
- 已实现 `/admin/users` 管理员页面和用户查询/详情 API；冻结、解冻和强制下线禁止对自己及管理员账号操作，要求同源 CSRF、15 分钟近期认证和原因，并写入 `admin_audit_events` 与 `security_events`。冻结和强制下线复用全局撤销与 Back-Channel outbox。
- 尚未完成：starter 的 PostgreSQL 持久化授权客户端、跨实例刷新锁及 Back-Channel 会话索引/重放缓存。
- 产品管理功能尚未完成：管理员人工恢复凭据一次性交付、OIDC 客户端管理、删除请求与审计查询页面。

注册、登录、风险 ALTCHA、恢复密码、改密、近期认证、恢复码一次性交付、个人中心、活动会话和管理员用户管理界面已经按现有 LAS Passport 视觉语言实现，并提供桌面、平板和手机响应式布局。管理员离线重置命令、最小 BFF 端到端样例、单实例 Token 自动续期、`auth_fresh_until` 强制边界、持久化 SSO 会话、RP-Initiated Logout 及 Back-Channel Logout 的单实例闭环已经完成；下一批优先补管理员人工恢复凭据和 OIDC 客户端管理，多实例持久化按实际部署需要推进。
