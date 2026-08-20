# LAS SSO Server

LAS SSO 的正式后端工程。`../SAS-Test` 仅是历史协议演示，本工程不依赖它。

## 技术基线

- Java 21
- Spring Boot 4.1
- Spring Security Authorization Server 7.1（Spring Authorization Server 的后继版本）
- PostgreSQL 18
- ALTCHA Java 2.0.3（PoW v2）
- Flyway
- Gradle Wrapper 9.6.1（Kotlin DSL）
- Testcontainers

解析后的依赖保存在 `gradle.lockfile`。升级 Version Catalog 或 Spring Boot 后，应执行 `.\gradlew.bat dependencies --write-locks` 并审查锁文件变化。

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

HTTP Session 已通过 Spring Session JDBC 持久化到 PostgreSQL，并关联 `user_sessions` 安全元数据。普通会话采用 24 小时滑动闲置期/14 天绝对上限，勾选“信任此设备”后为 14 天滑动闲置期/90 天绝对上限；管理员会话固定为 45 分钟闲置期/10 小时绝对上限。`/account/sessions` 可撤销单个会话或退出所有设备。

管理员可从 `/admin/users` 查询账号并查看凭据、会话和授权摘要；冻结、解冻和强制下线要求重新输入管理员密码、填写审计原因，并在同一事务写入管理员审计与安全事件。冻结和强制下线会撤销目标账号的 SSO 会话、Authorization，并通过 outbox 通知业务 BFF。

Refresh Token 每次成功使用都会轮换，但 family 的绝对期限固定为首次签发后 14 天且不顺延。SSO 在同一 PostgreSQL 事务内锁定 Token 历史和 Authorization，并复核账号、凭据版本、全局 security epoch 与客户端配置版本；检测到旧 Token 重用时撤销整条 family、记录高危安全事件并通过 outbox 请求对应 BFF 退出。历史表只保存带独立 pepper 的 HMAC，Spring Authorization Server 的标准 Authorization 表仍按框架要求保存可用 Token 值，因此数据库必须按高敏秘密存储保护。

密码恢复不会建立完整登录。恢复码验证成功后只设置 10 分钟、单次使用的 `__Host-las_recovery` 受限 Cookie；完成改密会递增凭据版本、消耗本次恢复码并撤销旧会话和 Authorization，同批其他未使用恢复码继续有效，随后必须用新密码重新登录。

## 测试

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

- `las-sso-spring-boot-starter`：给 Spring Boot BFF 提供登录入口、安全会话快照、Authorization Code + PKCE、当前应用退出和全局退出的基础接入。
- `las-sso-reference-bff`：最小可运行的业务系统样例；OAuth Token 只保存在服务端 Session，不返回浏览器。

先运行本地身份中心，再另开终端启动参考 BFF：

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun
```

访问 `http://localhost:8088` 或 `http://127.0.0.1:8088`。`local` profile 会登记这两个主机名对应的精确回调地址；开发时不要在一次授权流程中途切换主机名。

当前 starter 已支持单 JVM 内的 AT 自动续期、同 Session 刷新合并、刷新失败失效处理、每请求 `auth_fresh_until` 强制检查、当前应用退出后的后台 Token 撤销重试、RP-Initiated Logout，以及 Back-Channel Logout Token 校验、重放防护和本地 Session 失效。SSO 全局退出和单会话撤销会通过 PostgreSQL outbox 可靠投递 Back-Channel Logout。它还不是生产完整 SDK：当前应用退出的撤销队列仍是进程内实现，PostgreSQL 持久化授权客户端、持久化撤销 outbox、跨实例刷新锁及会话索引/重放缓存仍待实现。

个人中心的“已授权应用”按 OIDC `client_id` 聚合显示授权范围。用户撤销某个应用时，只删除本人对该客户端的 Authorization 和 Consent，写入安全事件，并通过 PostgreSQL outbox 向该客户端投递 Back-Channel Logout；其他客户端授权和 SSO 主会话不受影响。

## 安全边界

- `Authentication#getName()`、Authorization `principalName`、OIDC `sub` 固定为 UUID v7。
- 可变用户名只作为登录输入和 `preferred_username`。
- 浏览器不保存 OAuth Token。
- 业务权限、Minecraft 绑定、白名单和内容权限不进入本项目。
- 生产禁止使用本地 profile、示例密码或临时签名 key。
