# LAS SSO Server

LAS SSO 的正式后端工程。`../SAS-Test` 仅是历史协议演示，本工程不依赖它。

## 技术基线

当前[交付.20261006.7](../docs/zh-CN/integration/development-delivery-2026-10-06-7.md)修actualrefresh后globalLogout旧IDhint400：V15HMAC-only关联/授权cascadeclear，735全量0skip。最终Grafana实际导入desktopmobileViewer、双BFFchain十分钟mixed通过，见[hint契约](../docs/zh-CN/implementation/refresh-logout-hint-history.md)／[实际验收](../docs/zh-CN/operations/grafana-mixed-load-2026-10-07.md)。schema现在V1–V15，依赖三锁/starterAPI保持，生产soak另验。

当前[交付.20261006.6](../docs/zh-CN/integration/development-delivery-2026-10-06-6.md)实现领域metrics与可选`monitoring`profile：独立loopback管理端口9091/专用bcrypt hash、无状态GETPrometheus、cachedDBsnapshot、Prometheus/Alertmanager规则与Grafana配置；734全量0skip/真实3类firingresolved/TLS双链。配置、实际指标范围和未验生产边界见[监控操作](../docs/zh-CN/operations/monitoring-and-alerting.md)，启profile后health改用管理端口。

历史[交付.20261006.5](../docs/zh-CN/integration/development-delivery-2026-10-06-5.md)将数据库纳readiness，真实down/restart验200→503→200、liveness始终200，新增test后717全量0skip。最终JAR重跑TLS/admin/restore及有界容量，见[真实验收](../docs/zh-CN/operations/final-artifact-acceptance-2026-10-06.md)／[容量与监控边界](../docs/zh-CN/operations/capacity-readiness-2026-10-06.md)。当时领域metrics/alerts未实施，后续.6范围见上。

当前[交付.20261006.4](../docs/zh-CN/integration/development-delivery-2026-10-06-4.md)升级Boot4.1.1/Framework7.0.9/Tomcat11.0.26/Jackson3.1.7/BouncyCastle1.86/pgJDBC42.7.13/Log4j2.25.5，Security/AS7.1.1不变。三锁、716全量零跳过和制品消费通过；[扫描记录](../docs/zh-CN/implementation/security-scan-2026-10-06.md)保留17CVE处置、源码误报与覆盖限制。

最新[交付.20261006.3](../docs/zh-CN/integration/development-delivery-2026-10-06-3.md)包含完整 `deploy/nginx/las-forwarding.conf` 与referenceBFF可选 `https` profile，真实隔离TLS/SecureCookie/双链HTTPS退出风控已验；716全量绿，defaultlocal不变。正式域名证书/CDN/firewall按[代理验收](../docs/zh-CN/operations/tls-proxy-acceptance.md)另核。

最新[开发交付.20261006.2](../docs/zh-CN/integration/development-delivery-2026-10-06-2.md)修revokedbrowserOIDC授权续行，并完成实际WindowsPTY/PowerShell隐藏输入与Edge管理员firstchange/reset隔离验收，486server193starter37ref=716绿；旧.1恢复证据保历史。

当前本地交付[.4说明](../docs/zh-CN/integration/development-delivery-2026-10-05-4.md)包含参考BFF可选durable-revocation profile，独立PG/key/任务迁移；SSO身份库V1–V14不变。参考37测试与真实JVM重启撤销已有证据，默认BFF仍不需DB。

- Java 21
- Spring Boot 4.1
- Spring Security Authorization Server 7.1（Spring Authorization Server 的后继版本）
- PostgreSQL 18
- ALTCHA Java 2.0.3（PoW v2）
- Flyway
- Gradle Wrapper 9.6.1（Kotlin DSL）
- Testcontainers

解析后的依赖保存在三个模块的 `gradle.lockfile`。升级 Version Catalog 或 Spring Boot 后，应执行 `.\gradlew.bat dependencies --write-locks` 和 `.\gradlew.bat :las-sso-spring-boot-starter:dependencies :las-sso-reference-bff:dependencies --write-locks`，审查三锁及最终打包版本。

版本选择依据和阶段 0 结果记录在 `../docs/implementation/phase-0-implementation.md`。

## 本地运行

1. 启动 Docker Desktop。
2. 启动依赖：

   ```powershell
   docker compose up -d
   ```

3. 运行服务：

   ```powershell
   .\gradlew.bat bootRun --args="--spring.profiles.active=local"
   ```

本地 profile 使用仅限开发的临时 RSA key，每次重启都会变化。非 `local`/`test` profile 必须提供持久化 PKCS#12 RSA key 配置，缺失时启动失败。

`local` profile 会写入两个固定开发客户端：`laslog-bff`（LASLOG `localhost:8088`）和 `aliencommon-bff`（AlienCommon `localhost:8000`）。它们使用源码中明确标记为 local-only 的示例密钥，禁止复制到其他环境。

## 初始化唯一管理员

恢复维护另提供 `scripts/rotate-security-epoch-after-restore.ps1`，必须指定精确JAR/ExpectedEpoch/Reason，非Web禁调度/交互确认。它执行SSO库安全状态屏障；持久key更换与consumer屏障仍需另做，见[恢复流程](../docs/zh-CN/operations/restore-epoch-barrier.md)和[当前交付.20261006.1](../docs/zh-CN/integration/development-delivery-2026-10-06-1.md)。

已辨识版本维护可传 `-JarPath $ServerJar -ExpectedSha256 $ServerJarSha256`；不传时保留固定 SNAPSHOT 默认，不会自动选最新 JAR。精确路径/摘要核验、命令与验证边界见[维护制品说明](../docs/zh-CN/operations/maintenance-artifact-selection.md)。

先启动本地 PostgreSQL 并构建 JAR：

```powershell
docker compose up -d
.\gradlew.bat bootJar
```

数据库还没有用户时，交互式创建首个管理员：

```powershell
.\scripts\bootstrap-admin.ps1 -Username InitialAdmin
```

已有普通用户时，按不可变 UUID 提升：

```powershell
.\scripts\bootstrap-admin.ps1 -UserId 019c10a5-b800-7000-8000-000000000001
```

脚本会在终端隐藏读取并二次确认临时密码，不支持 `-Password` 参数。临时密码 24 小时有效且首次登录必须改密。非 local 环境还必须提供 Issuer、签名 key、数据库、`LAS_SSO_RECOVERY_PEPPER`、`LAS_SSO_TRANSIENT_SECURITY_PEPPER` 和独立的 `LAS_SSO_CAPTCHA_HMAC_SECRET` 等生产配置。第一版不依赖 Redis；限流、CAPTCHA、注册幂等和恢复短期状态使用 PostgreSQL 独立表并定时清理。

管理员忘记密码且恢复码不可用时，在维护窗口构建 JAR 并运行：

```powershell
.\gradlew.bat bootJar
.\scripts\reset-admin-password.ps1
```

命令会显示数据库中唯一管理员的用户名和 UUID，输入 `YES` 确认后隐藏读取并二次确认新的最终密码。成功后密码立即为 `ACTIVE`，无需再次改密；旧恢复状态、会话与 Authorization 会失效。脚本不接受管理员 ID 或密码参数，密码不会进入 shell 历史或日志。

## 通行证界面

服务启动后可访问：

- `http://127.0.0.1:9000/login`：用户名与密码登录；同一 IP 网段失败达到阈值后按需显示本地 ALTCHA；
- `http://127.0.0.1:9000/register`：公开注册、ALTCHA 本地工作量验证和一次性恢复码交付。
- `http://127.0.0.1:9000/password-recovery`：使用大小写敏感用户名和一次性恢复码重设密码；风险升高后按需显示 ALTCHA。
- `http://127.0.0.1:9000/account`：登录后的个人中心，显示稳定 UUID、当前用户名与账号管理入口。
- `http://127.0.0.1:9000/account/security`：登录后的改密、近期认证和恢复码重新生成。

界面由 Spring MVC + Thymeleaf 同站点提供，ALTCHA Widget 使用本地 WebJar，不依赖第三方 CDN。布局覆盖桌面双栏、平板单栏和 320px 以上手机视口。

登录风险控制默认在同一 IP 网段 15 分钟失败 20 次后要求一次性 `LOGIN_RISK` 验证，30 分钟失败 50 次后返回 `429`。普通失败响应带 150–450ms 非阻塞随机抖动；本地调试可在 `las.sso.login-risk` 下调整阈值、窗口和抖动范围。

恢复码轮换要求当前 SSO Session 在 15 分钟内完成过密码认证。重新认证只解除门禁，不会自动重放此前失败的轮换请求；用户必须再次确认生成。近期认证时间同时保存在 Spring Session JDBC 和 `user_sessions.last_password_auth_at` 中。

OIDC 授权端点现已处理 `prompt=login` 和 `max_age`：先验证客户端、精确回调、scope、PKCE 和参数，再要求当前账号在 `/oauth2/reauthenticate` 重新输入密码；未登录者完成一次真实密码登录即可继续。`max_age=0` 强制验证，正数按实际密码时间判断，`prompt=none` 在证据不足时返回 `login_required`。该密码表单要求 CSRF、同源、用户/IP 限流；成功轮换 HTTP Session ID，但保留稳定 SSO `sid`。

每个浏览器只保留一个五分钟、绑定原授权参数及主体/`sid` 的待验证事务，新事务替换旧事务。V14 的 `oidc_password_challenges` 用 PostgreSQL 条件更新原子完成/消费，Spring Session JDBC 保存原请求关联；重复或跨浏览器使用不能获得免验证返回。密码认证时间在初始 Authorization 中固定，签名 ID Token 的 `auth_time` 使用该快照，刷新与其他会话的后续密码认证均不更新旧授权的证据。缺少可信密码时间的旧会话/授权不补造 `auth_time`。

`OidcPasswordReauthenticationIntegrationTest` 覆盖真实 PostgreSQL、密码表单、签名 Token/Code 兑换、刷新、异常参数、请求关联、CSRF/Origin、到期与限流；`JdbcSessionPersistenceTest` 覆盖待验证事务 JDBC 重载和竞争消费。这是 SSO 侧验证，LASLOG 当前尚未启用 OIDC 或业务二次认证，仍须实现本地会话独立的 state/nonce/PKCE、证据消费与十五分钟门槛。

HTTP Session 已通过 Spring Session JDBC 持久化到 PostgreSQL，并关联 `user_sessions` 安全元数据。普通会话采用 24 小时滑动闲置期/14 天绝对上限，勾选“信任此设备”后为 14 天滑动闲置期/90 天绝对上限；管理员会话固定为 45 分钟闲置期/10 小时绝对上限。`/account/sessions` 可撤销单个会话或退出所有设备。

管理员可从 `/admin/users` 查询账号并查看凭据、会话和授权摘要；冻结、解冻和强制下线要求重新输入管理员密码、填写审计原因，并在同一事务写入管理员审计与安全事件。冻结和强制下线会撤销目标账号的 SSO 会话、Authorization，并通过 outbox 通知业务 BFF。

Refresh Token 每次成功使用都会轮换，但 family 的绝对期限固定为首次签发后 14 天且不顺延。SSO 在同一 PostgreSQL 事务内锁定 Token 历史和 Authorization，并复核账号、凭据版本、全局 security epoch 与客户端配置版本；检测到旧 Token 重用时撤销整条 family、记录高危安全事件并通过 outbox 请求对应 BFF 退出。历史表只保存带独立 pepper 的 HMAC，Spring Authorization Server 的标准 Authorization 表仍按框架要求保存可用 Token 值，因此数据库必须按高敏秘密存储保护。

密码恢复不会建立完整登录。恢复码验证成功后只设置 10 分钟、单次使用的 `__Host-las_recovery` 受限 Cookie；完成改密会递增凭据版本、消耗本次恢复码并撤销旧会话和 Authorization，同批其他未使用恢复码继续有效，随后必须用新密码重新登录。

## 测试

最新状态见[问题清单](../docs/implementation/status-issues-and-plan-2026-10-02.md)。2026-10-05identifiedtuple **0.1.0-dev.20261005.1**强制460server/178starter零失败错误跳过18task含两bootJar/metadata，BFFcompileNO-SOURCE；S223/query17+load1/callback2新增。sourcefingerprint/Jar锁hash/原生POM-only制品compile与public参数/七字段epoch见[开发交付中文](../docs/zh-CN/integration/development-delivery-2026-10-05.md)／[English](../docs/integration/development-delivery-2026-10-05.md)。默认仍SNAPSHOT，server/refBFF可用lasSsoServerVersion/starterlasSsoStarterVersion显式build，未commit/托管release/deploy；live消费proxyTLSbrowser/restorecapacityfullscan独立。

```powershell
.\gradlew.bat test
```

纯 JVM 测试始终运行。PostgreSQL 集成测试使用 Testcontainers；Docker 不可用时会明确跳过，不能把跳过当成持久化验证通过。

## 业务系统接入样例

完整的合作网站申请、客户端登记、各框架实现、登录续期、退出和联调流程见
[业务网站接入实施指南](../docs/integration/business-system-sso-integration-guide.md)。
LAS 自营系统另见[第一方网站接入指南](../docs/integration/first-party-system-sso-integration-guide.md)，
身份中心服务器部署见[SSO 部署指南](../docs/operations/sso-deployment-guide.md)。

工程包含两个独立子模块：

starter 已增加 Maven publication 和可配置本地制品库目标，详见 `las-sso-spring-boot-starter/README.md`。源码 composite 与本地发布/消费已验证；未建立托管不变版本发布或生产 SDK 配置。

- `las-sso-spring-boot-starter`：给 Spring Boot BFF 提供登录入口、安全会话快照、Authorization Code + PKCE、当前应用退出和全局退出的基础接入。
- `las-sso-reference-bff`：最小可运行的业务系统样例；OAuth Token 只保存在服务端 Session，不返回浏览器。

先运行本地身份中心，再另开终端启动参考 BFF：

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun
```

访问 `http://localhost:8088` 或 `http://127.0.0.1:8088`。`local` profile 会登记这两个主机名对应的精确回调地址；开发时不要在一次授权流程中途切换主机名。

可选自有安全链样例：`.\gradlew.bat :las-sso-reference-bff:bootRun --args="--spring.profiles.active=application-security"`。该 profile 使用公开 `LasSsoSecurityConfigurer`，登录成功返回 `/protected`；默认 profile 继续使用同一组合器构建的 starter 默认链。样例已重新编译，尚未启动做浏览器验收。详见[公开接入说明](../docs/zh-CN/integration/starter-security-composition.md)。

B5已提供安全returnTo：首页登录链接显式请求`/protected`，默认/可选链均支持；自有success须显式采用公共target/final redirect，不跳过业务检查。目标在本地授权state/Session十分钟事务绑定，非法400，旧无时间属性授权需重新登录；B5阶段强制178server/176starter零失败/跳过，BFF编译/资源处理、仍NO-SOURCE。见[返回契约](../docs/zh-CN/integration/login-return-target.md)，样例UI/browser未验收。

当前 starter 已支持单 JVM 内的 AT 自动续期、同 Session 刷新合并、刷新失败失效处理、每请求 `auth_fresh_until` 强制检查、当前应用退出后的后台 Token 撤销重试、RP-Initiated Logout，以及 Back-Channel Logout Token 校验、重放防护和本地 Session 失效。SSO 全局退出和单会话撤销会通过 PostgreSQL outbox 可靠投递 Back-Channel Logout。它还不是生产完整 SDK：当前应用退出的撤销队列仍是进程内实现，PostgreSQL 持久化授权客户端、持久化撤销 outbox、跨实例刷新锁及会话索引/重放缓存仍待实现。

LASLOG 初期接受业务重启后旧登录失效、重新 SSO，SSO JDBC 持久化不变。B1/B2 公开组合、B3 发送确认和 B4 严格接收/单 JVM 回执有专项证据；B4阶段强制全量178 + 117项零失败/跳过，BFF重编译/`test NO-SOURCE`。用户批准 Replay SPI 增量 process，旧 accept-only adapter 未升级接收 503；[升级契约](../docs/zh-CN/integration/backchannel-logout-receipts.md)和[发送契约](../docs/zh-CN/integration/logout-delivery-acknowledgements.md)明确边界。持久接受/真实消费/浏览器/重启仍待验。资格只有查询/版本，独立事件延期。

个人中心的“已授权应用”按 OIDC `client_id` 聚合显示授权范围。用户撤销某个应用时，只删除本人对该客户端的 Authorization 和 Consent，写入安全事件，并通过 PostgreSQL outbox 向该客户端投递 Back-Channel Logout；其他客户端授权和 SSO 主会话不受影响。

## 安全边界

2026-10-05S2owningwriter双向/连续Secret/实际BasicOAutherror/SQLfaultrollback/幂等23DB扩大通过，无新clientproduction修复。资格新增stateEpoch用户ADR-0007，复用security_epoch、同SQLsnapshot七字段，version只同epoch单调，blankmissing503；17query+1小TCPload记录非productionSLA。见[client](../docs/implementation/client-secret-concurrency.md)、[query](../docs/implementation/service-access-epoch-contract.md)及双语开发交付，consumer恢复代次屏障/local15m门禁仍独立。

A1/A2已补直连Servlet TCP：两实际Tomcat实例root与/passport各48项，专用第一方Basic/编码路径/真实Cookie隔离、loginCSRF/password/JDBC会话/单次HTTP签发CAPTCHA/IPblock与歧义target；96新增/181专项/419server176starter强制通过，本轮test/docs无新生产修复。HTTP helper手工SecureCookie/固定issuerOrigin、长testwindow和forward禁用非productionproxy/TLS/browser/生产窗口容量验收。见[中文](../docs/zh-CN/implementation/servlet-path-security.md)／[English](../docs/implementation/servlet-path-security.md)。

D1/CVE-2026-47877指定同意页依赖已修：三个模块Security/Authorization Server7.1.1，Boot4.1.0/Framework7.0.8/Jackson3.1.4保持；三锁及NimbusJOSE10.9.1/clientSDK11.38.2审查。5renderer先红后绿/4实际DBconsent、110server/131starter与323/176全量通过，两bootJar所有Security7.1.1实查；starter原生/POM-only临时制品解析均7.1.1。仅临时filepublication非正式发布，默认SNAPSHOT未变；完整扫描/browser/生产独立，见[中文](../docs/zh-CN/implementation/spring-security-consent-patch.md)／[English](../docs/implementation/spring-security-consent-patch.md)。

S6新恢复码和人工恢复凭据已升为四组16随机32表字符80bit，普通十枚/HMAC/pepper/规范化/单次和期限不变。旧三组仍60bit按原未用/未撤销/人工expiry兼容，升级不重写/续期/批量复活；显式rotation/reissue用新格式。9JVM+33兼容数据库MockMvc、164相关及314/176强制绿，真实全部issuer更新/浏览器/容量待验。见[中文](../docs/zh-CN/implementation/recovery-credential-entropy.md)／[English](../docs/implementation/recovery-credential-entropy.md)。

S5已修复管理员墓碑详情：删除密码行后仍按UUID身份查询，三个凭据字段明确null，页面显示无凭据/不适用。现有列表/详情200/no-store、未知身份404、墓碑四类写409与按钮禁止保留。18数据库/MockMvc/实际模板回归、138相关及273 server/176 starter强制通过；role/认证/Servlet为fixture，浏览器/部署待验。见[中文](../docs/zh-CN/implementation/admin-tombstone-views.md)／[English](../docs/implementation/admin-tombstone-views.md)。

S4晋升前有效注销门禁已实现：维护advisory lock后先锁ACTIVE用户，再检查PENDING/HELD，拒绝`DELETION_CANCELLATION_REQUIRED`并提示所有者先取消/操作员再重试；不改角色/凭据/请求/安全状态、不自动取消。25数据库+3命令fixture/90专项和强制255 server/176 starter零失败/错误/跳过。真实终端隐藏/脚本JAR进程退出、浏览器取消/首次改密待验，见[ADR-0002](../docs/adr/0002-require-deletion-cancellation-before-admin-promotion.md)、[中文](../docs/zh-CN/implementation/admin-promotion-deletion.md)／[English](../docs/implementation/admin-promotion-deletion.md)。

S3注销路径已统一user→request锁，job预选expiry/pending用户并集、稳定DB UUID先锁/SKIP，锁后重检；保留整批事务/失败回滚，实际转换计数。S3阶段24新增PostgreSQLrace/40专项及强制227server/176starter零失败跳过，BFF重编译/NO-SOURCE；不代表全系统无死锁/live多实例/容量。见[ADR-0006](../docs/adr/0006-user-first-deletion-batch-locking.md)、[中文](../docs/zh-CN/implementation/deletion-concurrency.md)／[English](../docs/implementation/deletion-concurrency.md)。该阶段无schema/deps/部署变更，D1后续补丁见上。

A3 内部资格查询现使用独立失败/在途预算，默认客户端10/网段20每十五分钟；哈希前原子占用、成功仅归还本次，不对成功查询总数计费、不清旧失败。满桶429/Retry-After，DB故障503/no-store/problem+requestId；与OAuth失败桶独立。配置 `las.sso.first-party-authentication`，见[中文契约](../docs/zh-CN/integration/internal-basic-authentication-budget.md)／[English](../docs/integration/internal-basic-authentication-budget.md)与ADR-0005。A3阶段25新增DB/MockMvc回归和强制203server/176starter全绿，BFF重编译/NO-SOURCE；真实代理、容量、指标告警/多实例未验，没有生产迁移/依赖改动或部署。

- `Authentication#getName()`、Authorization `principalName`、OIDC `sub` 固定为 UUID v7。
- 可变用户名只作为登录输入和 `preferred_username`。
- 浏览器不保存 OAuth Token。
- 业务权限、Minecraft 绑定、白名单和内容权限不进入本项目。
- 生产禁止使用本地 profile、示例密码或临时签名 key。
