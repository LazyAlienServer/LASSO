# 外部接入

[目录](README.md) · [English](../en/external-integration.md) · [SDK](../../sdk/README.md)

## 通用 OIDC

每环境使用独立 Confidential 服务端/BFF 和持续维护的 OIDC 库。在 `/admin/clients` 登记 client_id、名称/类型（LAS 控制部署为 FIRST_PARTY，审核合作方为 REVIEWED_PARTNER）、精确 HTTPS 回调/post-logout/Back-channel URI、`openid profile` 和资源 audience；秘密一次性私密保管。合作方 consent、第一方免 consent 均不放宽安全检查。不将 Secret 放浏览器，不用 implicit/password grant。

从精确 Issuer Discovery 获取端点，不能混用 localhost/127.0.0.1 或其他公网域名。使用 Code+PKCE S256、client_secret_basic、一次性有时限的 state/nonce/登录事务、站内允许列表 returnTo；验签/算法/kid/iss/aud/azp/时间/nonce 后，以 `(issuer,sub)` 唯一映射。sub 是不可变 UUID，用户名可变且大小写敏感。`LasUserPrincipal.getName()/getUsername()` 返回 UUID，`loginUsername()` 才是登录名。新业务身份不自动获敏感角色。

Token/Secret 留服务端；回调可向服务端传 code，但不得进入前端存储/分析/日志。浏览器只持有随机 Secure/HttpOnly/Lax、host-only 业务 Session Cookie。业务角色、Minecraft 绑定/准入由消费方负责。ID Token 不是 API Bearer；资源服务器校验 AT 的 audience/scope/issuer/签名/时间。

默认 AT15分钟，RT family 绝对14天，轮换不顺延，以实际 Token 到期为准。同会话并发刷新须合并，明确拒绝则清登录，暂时故障返回独立503而非封禁。当前站退出先结束本地登录，再尝试 RFC7009，不结束 SSO 主会话。全部退出使用 Discovery end_session、保留的 ID hint、精确已登记 post-logout URI 与一次性 state。服务端为存活授权保存跨刷新 HMAC hint 关联，但不能重建升级前已丢的 hint。

Back-channel 无浏览器 Cookie，接收签名 form `logout_token`；校验 typ=logout+jwt、RS256/issuer/audience/时间/jti/events/sid或sub，拒 nonce/畸形 Claim，按本地 absolute lifetime 保存重放/sid 撤销标记。成功意味着本地执行已提交，不只是解析请求；按 `(issuer,sid)` 幂等撤销并防迟到回调/刷新登记复活。

## Spring Boot Starter

按 [SDK](../../sdk/README.md)消费固定 file 仓库坐标。应用需 servlet Web、OIDC JSON 转换器、Boot4.1/JDK21，自行审查最终依赖/锁。最小外部配置：

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

实际 servlet Cookie 同名并设置 Secure/HttpOnly/Lax/Path=/，无 Domain。端点 GET `/api/auth/login`、`/api/auth/session`、`/logged-out`；POST `/api/auth/logout`、`/api/auth/logout/global`、`/internal/oidc/backchannel-logout`。Cookie 变更需 CSRF，仅签名 Back-channel 协议 POST 例外，不豁免浏览器 API 分组。`/api/auth/session` 表示本地身份，不是业务授权响应。

自有 SecurityFilterChain 时 Starter 不覆盖它。一次调用 `LasSsoSecurityConfigurer.configure(http, existingSuccessHandler)` 接入 repository、PKCE、回调/登记/刷新/退出过滤器；路由/CSRF/权限仍由应用配置，之后不覆盖包装后的 success handler。[样例](../../examples/reference-bff/README.md)含默认与自有两种模式。默认授权客户端/刷新合并/session index/replay/revocation sink 为单 JVM，使用默认 HttpSession；重启丢业务登录，再顶层 SSO 重入。跨重启保登录/多实例需等效协调 Adapter。sid 标记至少保留本地 absolute lifetime（默认14天）。

可选参考https,durable-revocation在独立PostgreSQL/reference_revocation迁移内嵌参考V1–V2，见[模板](../../config/README.md)。已提交加密任务/lease/8次campaign恢复；V2加精确代次offline dead恢复、完成保留、认证排空换key。请求LasSsoRevocationSubmission.REQUEST_ATTRIBUTE提供submit结果，非OP回执；pre-submit补偿/分布式业务会话仍由消费方负责，见[运维](operations.md)。

## LAS 第一方资格与近期密码

仅 ACTIVE FIRST_PARTY Basic 客户端可 GET `/internal/v1/principals/{UUID}/service-access`，不能以浏览器 Cookie/Bearer 替代。no-store 响应精确七字段：issuer、subject、decision、reason、stateEpoch、stateVersion、changedAt。未知 UUID 为200 DENIED/PRINCIPAL_NOT_FOUND/version0/changedAt null；全局 epoch 缺失返回503。认证/预算失败不能当 ALLOWED。冻结/注销等待/held/必须改密/凭据禁用拒绝，合法 ACTIVE 允许；这是身份服务资格，不是业务角色。

仅在 `(issuer,subject,stateEpoch)` 内比较版本。epoch 不透明、不可排序；消费方保留同代单调事实，遇意外代次先 fail closed，以新本地 generation fresh 对账，清旧证据、拒迟到旧代响应，不能随响应反复切代。查询/活跃刷新补偿必须在一分钟内观察拒绝；包不实现消费方 timer/cache，SERVICE_ACCESS_CHANGED 仍延期，Logout 不是资格同步。

消费方高风险操作通过标准授权 customizer 发起 `prompt=login` 与适当 max_age，关联 pending 操作/本地浏览器登录会话/state/nonce/PKCE，验签初始 ID Token auth_time，仅在发起会话内15分钟使用密码证据。OP 浏览器/请求挑战5分钟、JDBC 原子完成/消费。Refresh/Token issue time 不推进密码证据。Starter `LasSsoSecurityConfigurer` 提供授权 customizer，验证登录后通过 Spring `OidcUser.getIdToken().getAuthenticatedAt()` 读取签名证据；它们不实现消费方 pending/expiry 门禁。弹窗本身不是证据，不增加 MFA 放行门槛。

验收：双客户端、并发刷新、改名仍同身份、当前/全部退出、重复/迟到 Back-channel、禁用客户端、网络/提供方故障、后端重启、一分钟资格拒绝、旧 epoch 在途拒绝、发起会话15分钟及 refresh 不刷新密码证据。真实消费方结果单独记录，不用样例/单测代替。
