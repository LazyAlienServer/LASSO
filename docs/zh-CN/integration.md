# 后端/BFF接入

[目录](README.md) · [English](../en/integration.md) · [API](api.md) · [参考BFF](reference-bff.md)

使用机密服务端 client 和 Code+PKCE S256。浏览器仅持有本地 HttpOnly Cookie，密码只在 SSO 输入，Token 保留在后端；业务授权与身份/资格分别判断。

## 1. 独立登记client

确定可信 issuer、业务 HTTPS origin、精确 callback/post-logout/Back-channel URL、audience/scope 和负责人。每个环境独立登记，不使用通配回调或其他容器的 localhost。管理员按 A05 私密交付 secret；当前创建界面固定 openid/profile。FIRST_PARTY 由 LAS 控制，REVIEWED_PARTNER 需审核及用户同意。

业务关联稳定的 `(issuer,sub)` UUID，不按登录名关联；改名不改变身份，OIDC 成功不自动授予业务角色。

## 2. 安装精确Starter

JDK21/Servlet Boot Web、OIDC JSON converter，审查最终BOM/locks。包内file Maven：

```kotlin
repositories {
    maven { url = uri("file:///absolute/package/sdk/maven"); content { includeGroup("org.las") } }
    mavenCentral()
}
dependencies { implementation("org.las:las-sso-spring-boot-starter:{{VERSION}}") }
```

外部registration配置：

```properties
spring.security.oauth2.client.registration.las-sso.provider=las-sso
spring.security.oauth2.client.registration.las-sso.client-id=${LAS_SSO_CLIENT_ID}
spring.security.oauth2.client.registration.las-sso.client-secret=${LAS_SSO_CLIENT_SECRET}
spring.security.oauth2.client.registration.las-sso.client-authentication-method=client_secret_basic
spring.security.oauth2.client.registration.las-sso.authorization-grant-type=authorization_code
spring.security.oauth2.client.registration.las-sso.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}
spring.security.oauth2.client.registration.las-sso.scope=openid,profile
spring.security.oauth2.client.provider.las-sso.issuer-uri=${LAS_SSO_ISSUER}
las.sso.client.registration-id=las-sso
las.sso.client.refresh-before-expiry=PT60S
las.sso.client.post-logout-redirect-uri={baseUrl}/logged-out
las.sso.client.session-cookie-name=__Host-my_business
las.sso.client.session-absolute-lifetime=P14D
```

实际 Servlet Cookie 使用同名、Secure/HttpOnly/Lax/Path=/，无 Domain。入口保留路径并覆盖代理头；秘密放包外，不进入前端或源码。

## 3. 安全链只组合一次

无自有链时 Starter 提供默认链。自有 SecurityFilterChain 调用 `LasSsoSecurityConfigurer.configure(http, existingSuccessHandler)` 一次，保留业务路由和 CSRF；采用返回导航时显式使用 `LasSsoReturnToSuccessHandler`。保留包装后的 success handler，不重复安装过滤器。授权 builder customizer 是可信后端配置，不透传任意浏览器 prompt/max_age。

| 端点 | 用途 |
| --- | --- |
| GET `/api/auth/login` | 一次state/nonce/verifier、受限local returnTo |
| GET `/api/auth/session` | 安全no-store身份快照，不是权限 |
| POST `/api/auth/logout` | CSRF本地退出、保SSO |
| POST `/api/auth/logout/global` | trusted end_session/初始hint/state/精确return |
| POST `/internal/oidc/backchannel-logout` | 签名协议接收，仅此POST豁免CSRF |

核code/state/session/nonce/PKCE、签名iss/aud/算法/time及UserInfo sub，在callback成功导航前存储/登记本地会话。

## 4. 存储与故障策略

| 公开接口 | 默认/适配责任 |
| --- | --- |
| LasSsoAuthorizedClientRepository | session拥有Token，成功save后访问 |
| LasSsoBackChannelSessionStore | JVM sid/sub索引、revoked标记，拒迟到登记 |
| LasSsoLogoutReplayStore | 执行回执，需实现process，旧accept-only返回503 |
| LasSsoRevocationTaskSink | JVM重试；持久normal return为commit接受，非远端完成 |
| LasSsoClientErrorHandler | 安全响应hook，不复活已结束登录 |

默认 repository/index/replay/refresh 为单 JVM；BFF 重启结束业务登录，lifecycle listener 处理会话销毁和 ID 变化。可选参考队列仅保存已接受作业。持久化适配需明确提交前不确定性及恢复策略，并验证实际 beans/worker。

同会话刷新使用 single-flight。明确 invalid_grant 结束本地登录；暂时 503 拒绝当前请求，不推断账号封禁或新密码证据。后继丢失、保存失败属于未知状态：本地拒绝，保留已接受的已知作业并评估残留授权。旧 Token revoke 200 不证明未知 family 已清理，不故意 reuse。

## 5. 资格与近期密码

FIRST_PARTY 调用七字段[资格 API](api.md)，实现一分钟观察/活动/缓存策略、同 epoch 高水位及可信新 generation 对账，拒绝旧的迟到回复。SSO 不实现业务权限或消费方缓存规则。

高风险动作绑定待执行动作、本地会话、state/nonce/PKCE，请求 prompt=login/max_age 并核验签名的初始 auth_time。仅在发起登录中复用严格小于 15 分钟的证据；退出、重启或撤销时清除。刷新、弹窗或本地 callback 不推进证据；callback 不执行危险命令，用户重新提交后再核授权和资源状态。

## 消费方验收

验证双 client/browser、改名、同会话并发刷新、本地/全局/重复迟到退出、重启、存储/provider 故障、client 禁用、冻结/注销暂缓、恢复 epoch/在途屏障及跨浏览器/精确到期密码证据。保存实际结果、版本和负责人；样例不能代替 LASLOG 接入验收。
