# LAS SSO Spring Boot Starter

Java 21 servlet BFF 接入库。消费者使用服务端 OAuth 授权客户端和 HTTP Session，浏览器只接收安全会话快照；[参考 BFF](../las-sso-reference-bff/README.md)是样例，不是 LASLOG 后端。

## 功能与配置

| 入口/能力 | 语义 |
| --- | --- |
| `GET /api/auth/login` | Authorization Code + PKCE S256；安全应用内 `returnTo` 与 state/Session/十分钟授权事务绑定 |
| `GET /api/auth/session` | 无 Token 的会话快照、no-store；授权过期/暂时不可用分别处理 |
| 自动刷新 | AT 默认到期前60秒刷新，按本地会话拥有 Token，同 JVM 同 Session 并发合并 |
| `POST /api/auth/logout` | 先清本地授权/认证/Session，再提交拥有的 Token 撤销；不结束其他会话或 SSO |
| `POST /api/auth/logout/global` | RP-Initiated Logout、随机短期单次 state 和精确登记返回 URI |
| `POST /internal/oidc/backchannel-logout` | 成熟 JWT 验证、固定 issuer/RS256/typed Logout Token、短 iat/exp、sid/sub 失效和重放执行回执 |

配置 confidential OIDC registration：固定 issuer、独立 client-id/client-secret、精确 callback/退出地址及 `openid,profile`。业务 Secret 放外部受限设置，不能提交。支持的属性在 `LasSsoClientProperties`：registration-id、refresh-before-expiry、post-logout-redirect-uri、logout-success-uri、logout-state-ttl、session-cookie-name、session-absolute-lifetime。

`SESSION_EXPIRED` 拒绝后结束本地登录；暂时 SSO 故障返回 `503 SSO_UNAVAILABLE`，保留本地状态但拒绝当前请求。刷新不提供近期密码证据。消费方必须绑定自己的待执行操作、本地会话、state/nonce/PKCE和签名 auth_time，并验证十五分钟规则。

## 应用自有安全链

注入 `LasSsoSecurityConfigurer`，在自有 `SecurityFilterChain` 调用 `configure(http, applicationSuccessHandler)` 一次；应用保留路由授权及成功处理器。组合器安装共享 repository、PKCE/返回事务、刷新/登记、当前退出和仅 POST Back-channel 的 CSRF 例外。不要再次独立安装这些 Filter 或用未包装的 successHandler 覆盖登记。

需要采用返回目标时显式包装 `LasSsoReturnToSuccessHandler`，业务检查仍由应用保留。可选 trusted authorization-builder customizer 使用支持的公开 overload；不会默认转发任意浏览器 prompt/max_age 参数。

接入示例和完整生命周期契约：[English](../../docs/integration/starter-security-composition.md)／[中文](../../docs/zh-CN/integration/starter-security-composition.md)。

## 存储与作业扩展

| 公共接口 | 默认行为和扩展责任 |
| --- | --- |
| `LasSsoAuthorizedClientRepository` | HTTP Session 中的 Token 状态，按本地会话和 registration 所有权保存；不采用无关 Spring repository |
| `LasSsoBackChannelSessionStore` | 默认进程内索引和 revoked-sid 标记，配置期限内拒迟到登记；自有存储保持等效时序保护 |
| `LasSsoLogoutReplayStore` | 单 JVM 执行回执；必须实现 `process`，旧 accept-only adapter 接收503且不执行 |
| `LasSsoRevocationTaskSink` | 默认进程内重试；持久适配正常返回必须代表已提交接受，不等于 OP 撤销完成 |
| `LasSsoClientErrorHandler` | 可替换无秘密响应接点，认证/存储/撤销失败不能恢复已结束登录 |

公开 Session lifecycle listener 支持销毁和 Session-ID 换号；默认 session-absolute-lifetime 14天。单 JVM 标记和回执不提供跨重启/多实例保证。参考 BFF 的可选加密 PostgreSQL 队列只证明已提交任务重启恢复，不能补跨存储提交前崩溃窗口，详见[持久撤销](../../docs/zh-CN/integration/reference-durable-revocation.md)。

## 构建与发布

从 `las-sso-server/`：

```powershell
.\gradlew.bat :las-sso-spring-boot-starter:test --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=file:///absolute/path/to/repository" "-PlasSsoStarterVersion=$DeliveryVersion" --no-daemon --console=plain
```

默认 `0.1.0-SNAPSHOT`；明确 `lasSsoStarterVersion` 和仓库位置才生成辨识交付。发布包含 POM/native module metadata，Security patch 对齐同时导出；消费应用自己的 BOM/锁/override 仍须核验最终图。远程鉴权、不可变保存和发布批准属于发行配置，本库没有硬编码远程密码。

LASLOG 的显式源码 probe 可用 `verifySsoStarterDependency -PlasSsoSourceBuild=../../../LASSSO/las-sso-server`，只替换依赖探针，不自动给 LASLOG 运行时启用 OIDC。不要依赖全局 Maven Local 或隐式兄弟仓库。

## 验证与边界

签名 Code/JWKS/Logout、同会话刷新、不同会话隔离、临时/终态故障、重放冲突、返回事务和 Adapter 替换已有专项测试。实际默认/自有 BFF TLS、短期限 Token 刷新和退出有[历史制品验收](../../docs/zh-CN/operations/grafana-mixed-load-2026-10-07.md)，原数字和 hash 不表示新构建已验。

SSO V15 保留已签发 ID Token hint 的 HMAC 关联，使初始 OidcUser 在刷新后仍可全局退出，见[退出连续性](../../docs/zh-CN/implementation/refresh-logout-hint-history.md)。

初期业务进程重启后重新走 SSO；持久业务登录、多实例协调、消费端数据库、可靠撤销采用、资格缓存和业务权限由消费方完成。SSO 的监控凭据/端口/身份 schema 不进入本库或消费方表。当前计划与上线门禁见[生产上线计划](../../docs/zh-CN/implementation/production-launch-plan.md)。
