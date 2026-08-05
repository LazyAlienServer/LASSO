# LAS SSO 阶段 0 实施记录

- 实施日期：2026-08-02
- 工程：[las-sso-server](../las-sso-server/README.md)
- 状态：阶段 0 工程骨架、纯 JVM 验证和 PostgreSQL 集成验证均已完成

## 1. 已建立的正式工程

正式后端位于 `las-sso-server`，与历史协议演示 `SAS-Test` 隔离。构建使用 Gradle Kotlin DSL 和 Gradle Wrapper；依赖版本由 Version Catalog、Spring Boot dependency management 与 `gradle.lockfile` 共同固定。

当前基线：

| 组件 | 版本/策略 | 依据 |
|---|---|---|
| Java | 21 LTS | Spring Boot 4.1 支持 Java 17 至 26 |
| Spring Boot | 4.1.0 | 当前工程基线；要求 Gradle 8.14+ 或 9.x |
| Authorization Server | Spring Security 7.1.0 | 独立 Spring Authorization Server 已迁入 Spring Security 7 |
| Gradle | Wrapper 9.6.1 | Java 21 可用于运行 Gradle |
| PostgreSQL | 18.4 | 官方仍支持的主版本，开发镜像固定 patch 版本 |
| 短期安全状态 | PostgreSQL 独立表 | 第一版不增加 Redis 运维组件，使用原子 SQL 与定时分批清理 |

权威版本资料：

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Security Authorization Server reference](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/)
- [Spring Authorization Server 迁入 Spring Security 7](https://spring.io/blog/2025/09/11/spring-authorization-server-moving-to-spring-security-7-0/)
- [PostgreSQL versioning policy](https://www.postgresql.org/support/versioning/)
- [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html)

## 2. 已实现的基础边界

- Flyway `V1`：用户、大小写敏感且历史永久占用的用户名、密码凭据、恢复码、会话、注销请求、管理员审计、安全事件、outbox 和全局 `security_epoch`。
- Flyway `V2`：按 Spring Security 7.1 官方 JDBC schema 适配 PostgreSQL 的 Registered Client、Authorization 和 Consent 表。
- Flyway `V3`：客户端类型/状态、资源 audience、PKCE 标记、Back-channel Logout URI，以及 authorization 对应的凭据版本、security epoch、client config version、`sid` 与 refresh-token family 元数据。
- `Authentication#getName()`、Spring Security 用户名字段和 Authorization `principalName` 固定使用 UUID；可变用户名单独保存在 `loginUsername`。
- 用户名查询使用 PostgreSQL `COLLATE "C"` 与精确等值，`Alice` 和 `alice` 不互换。
- Authorization Code 2 分钟、Access Token 15 分钟、Refresh Token 14 天且每次轮换。
- `local` profile 固定登记 `laslog-bff` 和 `aliencommon-bff`，使用精确 loopback Redirect/Post Logout/Back-channel URI；local-only 示例 Secret 不得进入其他环境。
- 本地/测试环境生成临时 RSA key；其他 profile 必须加载持久化 PKCS#12 key，缺失时启动失败。
- Back-channel Logout issuer 生成 RS256 签名、`typ=logout+jwt`、含 `events`、`aud`、`jti`、`sid`/`sub` 且不含 `nonce` 的令牌。

## 3. 验证结果

执行命令：

```powershell
.\gradlew.bat test
```

截至 2026-08-02：

| 验证 | 结果 | 证据范围 |
|---|---|---|
| UUID v7 版本、variant、时间戳 | 通过 | 纯 JVM 单元测试 |
| Spring principal name 固定为 UUID | 通过 | 纯 JVM 单元测试 |
| Back-channel Logout Token 签名与 claims | 通过 | 纯 JVM 单元测试 |
| PostgreSQL Flyway 迁移 | 通过 | PostgreSQL 18.4 Testcontainers；V1-V3 实际执行 |
| Authorization JDBC 持久化与读取 | 通过 | 实际保存并按 ID 读回 UUID principal name |
| 用户名大小写敏感查询及 UUID principal | 通过 | `Alice` 命中、`alice` 不命中，principal name 为 UUID |
| PostgreSQL 短期状态 | 通过 | V5 迁移、原子限流、challenge 单次消费和过期清理已有真实数据库测试 |

当前完整测试报告为 25 项通过、0 项失败、0 项跳过，并成功生成可执行 JAR。

真实集成测试还确认了两项 Spring Boot 4 工程要求：Flyway 自动迁移必须引入 `spring-boot-starter-flyway`（PostgreSQL 仍需 `flyway-database-postgresql`）；自定义 JDBC Bean 必须暴露为 `JdbcTemplate`，才能让 Boot 创建应用使用的 `JdbcClient`。

Docker Desktop 启动后必须重新执行：

```powershell
.\gradlew.bat test --tests org.las.sso.persistence.AuthorizationPersistenceTest
.\gradlew.bat test
```

后续修改迁移或持久化配置时，仍应执行以上命令，且确认报告中 `skipped=0`。

## 4. 尚未实现，不能误认为已有

- 注册、改密、恢复码实际业务服务和 HTTP API；
- 密码 Argon2 参数基准、登录限流与 ALTCHA 服务端验证；
- 完整 Authorization Code + PKCE 浏览器流程；
- Refresh Token family 的绝对期限和版本校验执行逻辑；
- Back-channel Logout HTTP 投递、outbox 重试、接收方验签与会话清理；
- 注册/登录/恢复流程对 PostgreSQL 限流与一次性状态组件的完整接线；
- 管理员初始化与离线重置脚本；
- SSO 页面和 BFF 公共适配库。

下一阶段应先完成可运行的本地登录与 OIDC 纵向切片，同时补上数据库集成测试证据；不应在数据库迁移尚未真实运行时继续扩展大量业务表和 API。
