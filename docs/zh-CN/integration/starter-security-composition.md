# Starter 安全链组合与生命周期

- 状态：2026-10-03 公开组合/生命周期/错误接点已实现并通过测试；部署验收分别记录。
- 英文对应：[Spring starter security-chain composition](../../integration/starter-security-composition.md)。
- 源码：`las-sso-server/las-sso-spring-boot-starter`；工作区契约不代表制品已发布。

## 1. 应用自有 SecurityFilterChain

可选参考[持久撤销profile](reference-durable-revocation.md)仅替原sink、加密PG提交/restart恢复，不持久TokenSessionindexreplay或关闭local退出→submitcrash。默认Starter/publicAPI不变，见[ADR-0009](../adr/0009-reference-bff-durable-token-revocation.md)。

应用定义 `SecurityFilterChain` 后 starter 默认链会退让。注入 `LasSsoSecurityConfigurer`，在负责 LAS OAuth2 端点、本地会话和退出的链上调用一次 `configure(http, applicationSuccessHandler)`，安装与默认链相同的协议/生命周期组件；应用路由和业务授权仍由应用决定。

```java
@Bean
SecurityFilterChain applicationSecurity(HttpSecurity http, LasSsoSecurityConfigurer las) throws Exception {
    http.authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/", "/error", "/assets/**", "/api/auth/login", "/api/auth/session",
                    "/logged-out", "/internal/oidc/backchannel-logout").permitAll()
            .anyRequest().authenticated());

    var success = new SimpleUrlAuthenticationSuccessHandler("/protected");
    success.setAlwaysUseDefaultTargetUrl(true);
    las.configure(http, success);
    return http.build();
}
```

示例使用标准 Spring import 和 `org.las.sso.client.LasSsoSecurityConfigurer`。参考 BFF 提供已编译的等效 `application-security` 可选 profile：

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun --args="--spring.profiles.active=application-security"
```

这是可用启动命令，不是本轮真实浏览器执行证据；须先启动 SSO，按服务端 README 保持 issuer/回调主机名一致。

2026-10-05identifiedstarter另publicconfigure(http,success,Consumer<OAuth2AuthorizationRequest.Builder>)，应用显式trusted标准prompt/max_age，原overload不变。保留clientredirectstate noncePKCE/localreturnattributes，callback成功后OidcUser验证IDTokenauth_time可取；RP须pendinglocalSession/operation绑定/expiryrevoke，default不透传browserprotocol。签名fixture/制品compile解析与界限见[开发交付](development-delivery-2026-10-05.md)／[English](../../integration/development-delivery-2026-10-05.md)。

组合器安装：

- 标准 Spring OAuth2 Login、PKCE customizer 和统一的 `LasSsoAuthorizedClientRepository`；
- 登录成功包装器，在应用成功处理器/跳转前登记已验证的本地会话；
- 各一个刷新 Filter 和请求登记 Filter，使用相同 repository/会话所有权；
- `/api/auth/logout` 当前应用退出、Cookie 清理、本地失效后再提交远程撤销；
- 仅 **POST** `/internal/oidc/backchannel-logout` 的 CSRF 例外，其他应用写操作和退出保留 CSRF。

沿用标准授权/回调路径，应用须让端点和receiver进入正确链并允许接收。组合器不决定业务角色/公开路由/CORS/entry point；B5已提供校验/事务绑定returnTo，自有成功处理器按下文显式采用。重复configure拒绝，不要后来覆盖repository或未包装handler；多链/registration/custom路径独立验收。

## 2. 回调登记与失败语义

Spring 先验证授权响应、用 PKCE/客户端认证兑换 Code、验证 ID Token、保存授权客户端、执行 Session 防固定保护并保存认证；`LasSsoAuthenticationSuccessHandler` 随后在跳转前登记配置的 LAS registration 的 `sid`、subject 和最终本地 `HttpSession`。仅已认证且 registration 匹配 `las.sso.client.registration-id` 的 OAuth2 结果参与，LAS 登录要求已验证 OIDC subject 和非空 `sid`。

包装器先移除当前本地 Session 的旧索引，再登记新身份，避免后续登录或防固定处理看到旧 principal 时保留前一个 sid 映射。已有会话仍有请求期登记 Filter；应用通过组合器接入，不实例化包私有 Filter。

`LasSsoBackChannelSessionStore.register` 必须允许重复登记，正常返回意味着索引已可供 `invalidate` 使用，失败须抛出。回调/请求登记失败会尝试清理索引、删除本地授权、清除认证/失效 Session，并用既有退出 Handler 的 Token 快照提交撤销，随后输出 `SESSION_REGISTRATION_FAILED`；不调用应用成功 delegate。远程提交遵守 sink 接受保证，不代表 OP 已撤销。

原wrapper修复**成功回调后至下一请求前**窗口。2026-10-05默认Registry另增用户确认sid撤销标记，包含无匹配Logout，保留配置sessionAbsoluteLifetime/默认14天。markercheck与index publish/snapshot同短锁，Session/监听在锁外；迟到登记抛错，原cleanup在业务success前结束登录。同subject不同sid不受影响。[ADR-0008](../adr/0008-retain-sid-revocations-before-local-registration.md)定义期限/兼容；自有adapter须自行等效保护。Token/index仍非跨存储事务，在途业务/restartcrash/sharedstore/durablemultiinstance独立。

## 3. Session 生命周期

即使应用自有安全链，自动配置仍通过名为 `lasSsoSessionLifecycleListener` 的 `ServletListenerRegistrationBean` 注册公开 `LasSsoSessionLifecycleListener`。

- 销毁：幂等移除本地 Session ID 索引。
- 换号：移除旧 ID；存在已保存 Spring SecurityContext 时，按当前已验证 LAS 身份登记新 ID。初次登录可能尚未保存认证，随后成功包装器负责登记。
- 重建索引失败：失效本地会话，仅记录非秘密错误类别，抛通用错误而不传播 adapter 异常文本。

替换该命名 Bean 的应用须自行接入 `HttpSessionListener` 和 `HttpSessionIdListener` 两类行为。非 Servlet/共享会话存储需要对应生命周期 adapter；该监听器不代表 Spring Session/自有持久层兼容或跨实例协调已验收。

Spring 可能先保存授权客户端再轮换 Session ID。按字面 Servlet Session ID 保存 Token 的自有 adapter 必须在换号时关联或迁移所有权；starter 索引监听器不会移动它的 Token 行。默认/记录型测试 repository 委托同一个 HttpSession 对象，回调通过不代表按 ID 持久化的 adapter 已处理该转换。

## 4. 错误响应扩展

提供 `LasSsoClientErrorHandler` Bean 可替换刷新/登记错误输出：

```java
@Bean
LasSsoClientErrorHandler ssoErrors() {
    return (request, response, failure) -> {
        response.setStatus(failure == LasSsoClientErrorHandler.Failure.SESSION_EXPIRED ? 401 : 503);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + failure.name() + "\"}");
    };
}
```

失败枚举为 `SESSION_EXPIRED`、`SSO_UNAVAILABLE`、`SESSION_REGISTRATION_FAILED`。接点只输出响应，终态本地清理在它之前完成，受保护链不继续。暂时刷新故障保留会话/Token，但终止当前请求。Handler 不得报告成功、恢复认证、继续链或记录凭据/Token；不替代一般 Spring 认证/授权失败 Handler 或 B4 接收错误。

`DefaultLasSsoClientErrorHandler` 保留既有刷新行为：API 到期为 no-store 401 problem，非 API 到期跳 `/api/auth/login`，暂时 SSO/登记故障为 no-store 503 problem。既有刷新 Filter 三参公开构造器兼容，新增四参重载接入错误 Hook。`LasSsoAuthenticationSuccessHandler` 公开支持手动标准 Spring 接线，优先使用同一 LAS repository/刷新/退出组件的组合器。

## 5. 已执行证据与剩余事项

`LasSsoOidcCallbackIntegrationTest` **14 项**：默认签名回调→无后续请求立即签名退出、同主体双会话、包外应用自有链、替换 repository/index/error、登记失败、缺 sid、错误 RSA 签名/nonce、换号/销毁接点、再次登录、拒绝重复组合、CSRF 当前退出及真实 HTTP 刷新失败输出。OP fixture 核验 Basic、精确 redirect、S256 verifier，提供真实 RSA JWKS 并签发 ID/Logout Token；原 B2 回归先红后绿。

执行真实 Spring FilterChainProxy、Login provider/repository/manager 和 HTTP/JWT 客户端；入站 Servlet 为 Mock，ID 事件显式调度，Back-channel 路由通过终端 fixture servlet 调实际 Controller。未验证 MVC 参数绑定、真实容器/浏览器/SSO、持久 adapter/部署或退出先于登记的竞争。另新增两项刷新 Filter 的错误接点测试。

2026-10-03 全量强制重跑：**服务端 158 + starter 53 项**，零失败/错误/跳过；参考 BFF 重新编译，`test NO-SOURCE`。本步骤无依赖/锁/schema 修改。B3/B4/B5、持久接受、客户端重认证 resolver/消费和可辨识版本交付仍在[状态地图](../../implementation/status-issues-and-plan-2026-10-02.md)单独跟踪。

随后 2026-10-04 B3 发送确认/重试已修并验证，最新全量为 178 server / 71 starter；见独立[投递回执契约](logout-delivery-acknowledgements.md)。这组后续发送证据不扩大本文回调/生命周期验收范围。

B4阶段全量178server/117starter，自有replay须process/旧accept-only503，用户批准ADR-0003；见[升级契约](backchannel-logout-receipts.md)，非真实生命周期/持久已验。B5更晚结果见下节。

## 6. B5 返回目标更新 — 2026-10-04

configurer在标准授权请求存储上组合十分钟创建时间、同JVM/Session单次消费及目标内部属性，并有一个授权前校验Filter拒不安全目标400。默认登录使用已验证目标；自有success仍始终执行，读 `LasSsoReturnTo.target(request)`或在业务检查后最终调用 `LasSsoReturnToSuccessHandler`。不要把业务检查当其fallback，因为有目标时fallback不执行。

参考BFF profile显式采用跳转，首页登录请求`/protected`。公开签名保持；旧待完成请求无时间属性须新登录，自有resolver/storage须保留state/期限/目标契约。[中文](login-return-target.md)／[English](../../integration/login-return-target.md)及ADR-0004含URI/限制。最新178server/176starter零失败/错误/跳过；callback现28项、目标/事务45项，不推断liveMVC/browser/shared-store/消费验收。
