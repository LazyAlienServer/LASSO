# 登录返回目标与本地授权事务

- 状态：2026-10-04 B5 实现/受控验证完成，真实消费/部署待验收。
- 英文对应：[Login return target and local authorization transaction](../../integration/login-return-target.md)。
- 决策：[ADR-0004](../adr/0004-bind-local-return-target-to-login-transaction.md)，用户确认自有链显式接入。

## 1. 输入和 URI 规则

`GET /api/auth/login?returnTo=<应用内路径>` 接受最多一个可选目标。缺省保持原登录；空、重复、格式错误、不安全或超过2048字符返回 no-store **400**，无Location/新Session。配置的直接 `/oauth2/authorization/{registrationId}` 入口在启动Spring授权前同样拒绝。正常登录入口跳转302/no-store。

目标是应用context内、单个 `/` 开头路径，如：

```text
/projects/42
/projects/42?tab=members&filter=active
/search?q=中文
/项目/42
```

使用JDK解析URI并将合法Unicode转ASCII URI。拒绝scheme/authority、开头`//`、fragment、反斜杠、raw/解码后控制/空白、错误百分号编码、点路径段、分号、编码路径分隔/界定符。path/query一次URI解码后剩余`%`也拒绝，排除嵌套编码。普通查询`a%2Bb`允许，编码空白不在初期规则内。规则有意保守，业务需扩展语法应另行确认，不自行绕过解码检查。

入口将完整目标编码成一个本地授权参数，保留`?`/`&`/percent/非ASCII。最终加`request.getContextPath()`：context `/bff`下目标`/projects/42`返回`/bff/projects/42`，不由Host/转发头选择源。目标端点仍负责业务鉴权及自己的二次跳转。

## 2. state/Session/期限绑定

公开configurer在标准PKCE resolver上组合内部resolver/repository。目标/创建时间在OAuth2AuthorizationRequest **内部attributes**，不发送OP query/additional parameters；state/nonce/PKCE/精确callback保留Spring。

- 每本地Session一个待完成授权，新请求替换该Session旧请求/目标，保留原Spring行为；不同Session独立。
- callback在发起Session中要求单个匹配state。错误浏览器/state或重复state拒绝，不消费正确待完成请求。
- 匹配授权在Code兑换前只移除一次；创建时间非未来且严格小于十分钟，精确十分钟拒绝。过期匹配请求移除后须重新登录。
- 消费目标此时仅request-local、尚未交成功代码；Code/nonce/签名及Session登记成功后，`LasSsoAuthenticationSuccessHandler`通过公开 `LasSsoReturnTo.target(request)`交付。
- callback returnTo不能覆盖，OAuth错误/nonce或签名错/过期或重放/登记失败不跳目标，后续无目标登录不沿用旧目标。

消费在单JVM的Servlet Session对象上同步标准HttpSession repository，不保证共享存储/每请求包装Session的分布式原子消费。远程认证结束前已消费事务，失败须新登录，不能沿用旧state。部署前没有时间属性的旧请求也须重新发起。手动替换授权存储/resolver必须保留契约，configurer不提供持久adapter。

## 3. 应用自有成功处理

默认链用 `LasSsoReturnToSuccessHandler`选择已验证目标，缺目标委托原固定`/` fallback；目标302/no-store并清默认Spring保存请求/错误。它只是导航，不自动重放POST/危险写操作。

自有成功delegate有目标时**仍执行，不被替换/跳过**，可读公开目标、自行响应，或在业务检查后调用公开最终跳转：

```java
var fallback = new SimpleUrlAuthenticationSuccessHandler("/home");
fallback.setAlwaysUseDefaultTargetUrl(true);
var finalRedirect = new LasSsoReturnToSuccessHandler(fallback);
las.configure(http, (request, response, authentication) -> {
    // 在这里执行应用自己的成功检查；拒绝时直接停止。
    finalRedirect.onAuthenticationSuccess(request, response, authentication);
});
```

纯跳转delegate可直接传 `new LasSsoReturnToSuccessHandler(existingRedirectHandler)`。不要把业务检查包装成fallback，因为有目标时fallback不执行。回归证明应用403不会被starter强制目标跳转。参考BFF可选application-security明确采用handler，首页登录链接请求`/protected`；已编译但未启动浏览器/profile验收。

目标处理在callback验证/登记之后，保留B2立即退出索引/B1Token所有权/B4；不会产生近期密码证据或授予目标访问权限。

## 4. 已执行证据

原两项签名回归先红：`/projects/42`及查询目标被丢弃，回首页；state绑定实现后变绿。扩展直接授权非法目标发现Spring resolver通用错误500；专用匹配Filter改400/no-store且保留已有有效事务。这不同于fixture起初误认为500会有redirect的断言。补丁应用导致一次编译错误已修，是setup而非生产缺陷。

- `LasSsoReturnToTest` **45项**：允许/拒绝URI矩阵、query转发/context、重复/过长/缺省、callback不可信参数、精确到期/未来、错误/重复state、替换/浏览器隔离及确定性并发单次repository消费。
- `LasSsoOidcCallbackIntegrationTest` **28项**，相对B4新增14：真实签名Code/PKCE HTTP/JWKS，目标成功/篡改/重放、浏览器和最新请求、错误nonce/OAuth、兑换前过期、重复state、并发仅一次exchange、自有显式/保留处理器、业务拒绝/登记失败及既有退出/生命周期。确认目标不进入OP参数。
- 既有10项Session链继续通过，最后相关专项 **83项**（45+28+10）零失败/错误/跳过。

服务端目录命令：

```powershell
.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest.defaultSignedLoginReturnsToTheValidatedOriginalTarget" --no-daemon --console=plain
# 原两项红灯，后续扩大套件绿。

.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoReturnToTest" --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest" --tests "org.las.sso.client.LasSsoSessionChainIntegrationTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

2026-10-04约14:23–14:26（Asia/Shanghai）最新强制全量 **178server+176starter** 零失败/错误/跳过，12actionable全执行；参考BFF编译/处理资源、自身NO-SOURCE。新增59，无schema/依赖/锁改动。旧红灯XML覆盖，输出/状态地图§3.7保留阶段。

## 5. 验证边界

签名套件入站Servlet、login controller调度、ID事件为fixture；真实Spring filters/providers/repository、HTTP Token/JWKS和RSA执行。精确时间/repository并发用Clock/Session fixture，不是live MVC绑定、浏览器/实际SSO/容器/代理规范化、样例UI渲染、共享Session/重启/多实例或消费业务验收。有二次redirect的真实业务端点还需自己审查。版本/制品交付、A3/S3/S4/S5/S6/D1独立跟踪；未提交/发布/部署。

随后2026-10-04 15:46–15:49 A3内部hash前budget修复已验，最新203server/176starter零失败/错误/跳过，见[预算契约](internal-basic-authentication-budget.md)。后续server证据不扩大本文return/browser/shared-store范围。
