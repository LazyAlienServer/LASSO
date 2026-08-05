# LAS SSO Spring Boot Starter

这是业务系统 BFF 的初版接入骨架，当前提供：

- `GET /api/auth/login`：进入 Spring Security OAuth2 Login。
- `GET /api/auth/session`：返回不含 Token 的安全会话快照，并设置 `Cache-Control: no-store`。
- Authorization Code + PKCE S256。
- 每个受保护请求检查服务端授权客户端；AT 默认在到期前 60 秒使用 RT 自动续期。
- 刷新完成后重新读取服务端授权客户端，并强制要求当前 AT 的 `expiresAt` 晚于当前时间；该时间就是 `auth_fresh_until`。到期且无法得到新 AT 时清除本地认证，API 返回 `401 SESSION_EXPIRED`。
- 同一 BFF 实例内，同一个 Session 的并发请求合并为一次刷新。
- SSO 明确拒绝 RT 时删除服务端授权客户端并注销本地 Session；API 返回 `401 SESSION_EXPIRED`，页面请求重新进入登录流程。
- SSO 网络或服务端暂时故障时保留本地 Session，但当前受保护请求返回 `503 SSO_UNAVAILABLE`，不会把故障误判为账号撤销。
- `POST /api/auth/logout`：只退出当前应用。
- `POST /api/auth/logout/global`：服务端生成 RP-Initiated Logout 请求，立即注销本地业务会话，并用一次性短期 `state` 校验 `/logged-out` 回调；ID Token 不暴露给浏览器脚本。
- `POST /internal/oidc/backchannel-logout`：校验 SSO 签发的 Logout Token，并按 `sid`/`sub` 注销本地 Session；相同 Token 重放保持幂等，同一 `jti` 的不同 Token 会被拒绝。

业务项目仍需配置 confidential client 的 `issuer-uri`、`client-id`、`client-secret`、回调地址和 `openid,profile` scope。若项目自己声明了 `SecurityFilterChain`，starter 不会覆盖它，项目必须自行接入 OAuth2 Login 与相同的 PKCE、安全路由和退出规则。

刷新提前量可通过 `las.sso.client.refresh-before-expiry` 配置，例如 `60s`。全局退出可配置 `las.sso.client.post-logout-redirect-uri`、站内 `logout-success-uri` 和最长 15 分钟的 `logout-state-ttl`；退出后地址必须精确登记到 SSO 客户端。

当前默认使用 Spring HTTP Session 保存授权客户端，刷新合并、全局退出回调状态、Back-Channel 会话索引和 Logout Token 重放缓存也只覆盖单个 JVM 实例，适合本地样例，不代表生产集群完成。生产接入前仍需实现 PostgreSQL 持久化授权客户端、跨实例刷新锁及会话索引/重放缓存。
