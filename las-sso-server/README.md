# LAS SSO Server

活跃身份服务工程，包含服务端、[Starter](las-sso-spring-boot-starter/README.md)和[参考 BFF](las-sso-reference-bff/README.md)。`../SAS-Test` 是忽略的历史协议演示，三个模块不依赖它。

## 工程基线

- JDK 21、Gradle Wrapper 9.6.1、Spring Boot 4.1、Spring Security Authorization Server 7.1。
- PostgreSQL、Flyway V1–V16、Spring Session JDBC；第一版不引入 Redis。
- ALTCHA 本地工作量验证；密码默认 Argon2id，身份主体固定 UUID。
- 精确依赖版本见 `gradle/libs.versions.toml` 和三个模块的 `gradle.lockfile`。
- Java 编译/测试/格式化/可重复归档规则统一在根 `build.gradle.kts`，协作规则见[CONTRIBUTING](../CONTRIBUTING.md)。

## 本地运行

要求 Docker 与 JDK 21。从本目录执行：

```powershell
docker compose up -d
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

SSO 在 `http://127.0.0.1:9000`；开发 PostgreSQL 在 `127.0.0.1:54329`。初始化管理员后可访问 `/login`、`/register`、`/account`、`/account/security`、`/account/sessions`、`/account/recovery-codes` 等页面。

`local` profile 使用每次启动变化的临时签名 key，登记两个明确 local-only 的示例客户端。非 local/test 环境必须显式使用 `production`，持久 PKCS#12 RSA key 和外部秘密配置；不能复制开发客户端或示例凭据。生产 Web 启动还检查管理员、安全 epoch 和已有 ACTIVE 客户端；空库先用非 Web bootstrap。见[上线计划](../docs/zh-CN/implementation/production-launch-plan.md)及[启动门禁记录](../docs/implementation/production-startup-gates-2026-10-07.md)。

## 构建与测试

```powershell
.\gradlew.bat spotlessApply --no-daemon --console=plain
.\gradlew.bat build -PrequireDatabaseTests=true --no-daemon --console=plain
```

`build` 验证三模块 Java 格式并运行测试。数据库测试使用 Testcontainers；`-PrequireDatabaseTests=true` 拒绝跳过测试，Docker 必须可用。日常专项可使用 `:test --tests "完整类名"`，但部分报告和跳过不能作为完整交付证据。

从仓库根目录验证代码库和报告：

```powershell
python scripts/verify.py repository
python scripts/verify.py tests --require-no-skips
```

依赖升级后解析受影响模块并审查锁差异：

```powershell
.\gradlew.bat dependencies :las-sso-spring-boot-starter:dependencies :las-sso-reference-bff:dependencies --write-locks --no-daemon --console=plain
```

## 管理员维护

先启动数据库并构建 `bootJar`。这些入口在真实终端隐藏读取密码，不接收密码参数：

```powershell
.\scripts\bootstrap-admin.ps1 -Username InitialAdmin
.\scripts\bootstrap-admin.ps1 -UserId 019c10a5-b800-7000-8000-000000000001
.\scripts\reset-admin-password.ps1
```

前两条分别用于空库创建和按 UUID 提升已有用户，按实际场景选择。存在有效注销申请者须先由所有者取消；维护操作不自动取消申请。临时密码 24 小时有效，首次登录强制修改。

默认脚本只选择固定 `0.1.0-SNAPSHOT` JAR。辨识版本必须传精确 `-JarPath $ServerJar -ExpectedSha256 $ServerJarSha256`；生产加 `-Profile production` 并通过受限外部配置供应数据库、issuer/key/pepper，禁止用通配符猜最新制品。

恢复数据库后使用 `rotate-security-epoch-after-restore.ps1`，指定 ExpectedEpoch、Reason 和精确 JAR/hash，在停止所有写入者后交互确认；签名 key 和消费方缓存/会话屏障另做，见[恢复流程](../docs/zh-CN/operations/restore-epoch-barrier.md)。

## 参考客户端与监控

启动 SSO 后另开终端：

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun
```

参考 BFF 默认 `http://127.0.0.1:8088`，一次授权流程内保持同一主机名。可选 `application-security`、`https`、`durable-revocation` 配置见[样例说明](las-sso-reference-bff/README.md)。

监控为可选 `monitoring` profile：独立 loopback 管理端口 9091、专用外部 bcrypt 采集凭据、只允许无状态 GET Prometheus。启用后 health 也迁到管理端口；数据库参与 readiness，liveness 不依赖 DB。指标、Prometheus/Alertmanager/Grafana 输入及私有传输规则见[监控文档](../docs/zh-CN/operations/monitoring-and-alerting.md)，不要从公网代理管理口。

## 契约与当前状态

P05默认每5分钟历史清理batch100，终态session/已用凭据90天、全Token及RT family过期授权/REVOKED RT摘要/成功outbox30天；有效hint/RT、pending/dead/审计事实保护。只读 `scripts/history-status.sql` 和配置/超时/吞吐/vacuum/pepper说明见[English](../docs/operations/runtime-history-retention.md)／[中文](../docs/zh-CN/operations/runtime-history-retention.md)。目标容量/审计归档另验，不能把本机样本当生产保证。

- [OIDC 与 BFF](../docs/architecture/oidc-and-bff-contract.md)：Code/PKCE、刷新、重认证、退出；签名 `auth_time` 不因刷新推进。
- [第一方接入](../docs/integration/first-party-system-sso-integration-guide.md)：服务访问资格与身份令牌区别，版本只在同 epoch 内比较。
- [V15 刷新退出 hint](../docs/zh-CN/implementation/refresh-logout-hint-history.md)：仅 HMAC 历史关联、类型限定查找和授权删除级联。
- [安全与恢复](../docs/architecture/security-and-recovery.md)、[部署指南](../docs/operations/sso-deployment-guide.md)、[生产上线计划](../docs/zh-CN/implementation/production-launch-plan.md)。

阶段测试数字和制品摘要保留在[交付记录](../docs/integration/development-delivery-2026-10-06-7.md)与[状态地图](../docs/implementation/status-issues-and-plan-2026-10-02.md)，不是 README 的动态测试总数。原交付不可被格式化或新的构建覆盖。

SSO 拥有身份、凭据、账号生命周期、会话和第一方服务资格；业务角色、Minecraft 身份/白名单/封禁和消费方高风险门禁由业务项目负责。浏览器不保存 OAuth Token；数据和日志不得泄露密码、Token、Cookie、恢复码或密钥。
