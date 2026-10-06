# Servlet HTTP内部认证与登录风控验证

- 状态：2026-10-04 A1/A2既有修复通过真实loopback TCP/Tomcat/PostgreSQL；proxy/TLS/browser/部署待独立验收。
- 英文对应：[Servlet HTTP verification of internal authentication and login risk](../../implementation/servlet-path-security.md)。
- 跟踪：[状态地图§3.14](../../implementation/status-issues-and-plan-2026-10-02.md)。

## 1. 实际层级与范围

ServletPathSecurityIntegrationTest在127.0.0.1随机HTTP端口启动真实SSO，Java HttpClient原编码target经TCP发送，不自动redirect，无MockMvc/注入认证/测试permit chain/controller。Tomcat11.0.22/Security7.1.1/实际filter MVC/Argon2/SpringSessionJDBC用一次性PostgreSQL18.4。ServletContextPathSecurityIntegrationTest继承同矩阵、另实例context=/passport。每类**48项**，共**96新增**（每实例28登录风控+20内部认证）。

共享test properties显式loopback/forward-headers-strategy=none、停scheduling/JDBCcleanup，login风险window240h；test阈值CAPTCHA2失败/block3、零jitter。防矩阵跨短窗口的fixture，非生产政策变化或15/30m边界验收。每case只清一次性库相关预算scope，非并发压测/多实例协调。

账号/客户端走owning service/repository，type/status metadata明示SQL。人类login实际取CSRF/form/password/换SessionId/Cookie、后续请求JDBC重载，没有Mockprincipal/session。HTTP helper显式把收到的Secure Cookie在loopback HTTP转发，因此非浏览器Secure/SameSite/__Host强制或TLS证据。CAPTCHA POST Origin显式固定test issuer http://127.0.0.1:9000而非随机listenport；属fixture输入，不验livehost/proxyorigin。未验所有context UI链接。

## 2. 矩阵/结果

### A1：专用第一方内部认证

普通与三种literal单编码路径：principals→%70rincipals、internal→%69nternal、service-access→service-%61ccess，两context均：

- 合法ACTIVE FIRST_PARTY Basic查known/unknown UUID分别ALLOWED/ACTIVE、DENIED/PRINCIPAL_NOT_FOUND，minimal/no-store，成功budget退款。
- anonymous401/Basic realm=LAS SSO internal/no-store，不返资格。
- 真实普通用户JDBC Cookie不能替Basic，之后仍可查自己的profile。
- wrongsecret/unknownclient/partner/disabled/metadata缺失/不支持Basic/malformedBasic/无关Bearer编码路径拒绝。
- 合法Basic＋人类Cookie查询成功但不覆盖已有持久认证。
- 十次实际错误请求耗尽**client**预算，正确Basic在普通/编码两路429/Retry-After/no-store；非新增networkthroughput验收。
- double编码/semicolon参数/encodedslash/NUL/backslash即有合法Basic/人类Cookie也400，无decision/subject/stateVersion。Boot通用error可能回显调用者自己的path/UUID，非身份状态查询。

### A2：登录处理/风控

/login、/log%69n、/%6cogin在两context：

- 未受限正确登录成功，仅一userSession/subject-indexed JDBCSession，后续真实HTTP可查现有/api/v1/me/profile。
- CSRF缺失403，无认证/失败预算/用户计数变化。
- wrongpassword同user/IPcounter，redirect到context loginerror。
- blockednetwork429/Retry-After，无认证会话。
- required但missing/malformed CAPTCHA走captcha redirect，不验密码/改用户失败数。
- 实际HTTP签发并ALTCHA求解proof仅一次login，另一helper不能重放。
- proof正确但password错，proof消费一次/记失败，不能换正确密码重用。
- CSRF先拒、不消费proof，后续合法request同proof可用。
- 错purpose/network/expired拒且未消费，明示service/time/network fixture，不替代HTTP正对照。
- 实际错误HTTP跨普通/编码进入CAPTCHA，再solvedproof错误密码触发IPblock。
- IPblock在proof消费之前，forwardheaders明确禁用时Forwarded/XFF不绕；不批准生产trustedproxy。
- doublelogin/encodedtrailingslash/semicolon/NUL/backslash不认证、不改用户passwordfail（拒绝400/403/404）。

支持既有matcher/授权/风控在直连Servlet层一致，无新增生产repair，仅两test/文档，无schema/deps/permission/credential/proxy配置变化。

## 3. Fixture修正与证据

最初3blocked绿；扩大root40项12fixture失败：profile helper请求未实现/api/v1/me、CAPTCHA Origin用随机port非固定issuer。改现有/profile与显式issuer Origin后二套40均绿，非产品漏洞。

扩大48阶段子类@SpringBootTest替换父properties，新增startup assertion子类48均setup失败；root2过严error断言因400回显输入path红。共享设置移继承@TestPropertySource，error只验无资格字段不验无自带UUID。最终**48+48**绿且明确assert loopback/forward/window。未把阶段错误算新A1A2缺陷、不造oldcode红；本任务验既有修复，历史问题证据保留。

server目录Java21/Docker：

```powershell
.\gradlew.bat :test --tests "org.las.sso.security.ServletPathSecurityIntegrationTest" --tests "org.las.sso.security.ServletContextPathSecurityIntegrationTest" --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.security.ServletPathSecurityIntegrationTest" --tests "org.las.sso.security.ServletContextPathSecurityIntegrationTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.security.LoginFailureResponseJitterTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

约23:27–23:28Asia/Shanghai最后相关**181=48+48+6+25+53+1**零失败/错误/跳过；约**23:30–23:34**2026-10-04强制全量**419 server/176 starter**同零失败/错误/跳过、12task全执行、BFF重编译NO-SOURCE。root48XML15:32:45.005UTC，context48XML15:32:33.975UTC，日志实际Tomcat端口/context/PostgreSQL。较D1历史323新增96。早XML后覆盖，输出/状态§3.14保留阶段。

## 4. 剩余

A1/A2现已修/直连Servlet TCP与根context验证；生产proxy请求归一/forwardtrust、TLS浏览器cookie/JS/UI可访问性、完整release配置未验。非任意URI fuzz/所有container dispatch method、并发CAPTCHA/browser、生产threshold窗口边界/吞吐连接池/crashrestart/live多实例/全量日志Secret验收。

S2随后23owningwriter/failure见[client记录](client-secret-concurrency.md)；SSO-04epoch/lifecyclewire17与小TCPload见[query记录](service-access-epoch-contract.md)，[identifiedlocal交付](../integration/development-delivery-2026-10-05.md)最新460server178starter，上方419/176保留Servlet阶段。consumer观察/恢复屏障、actualrestore/productioncapacity、B1持久live消费独立；本Servlet步骤未commit/发布deploy/用户库。实际target/filterMVC闭环补原MockMvc/DBrace。
