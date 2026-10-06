# sid 登记时序与维护制品交付 — 2026-10-05

.3历史快照，[.4交付](development-delivery-2026-10-05-4.md)增可选参考持久撤销/37测试，原manifest不等当前source锁。

- ID：**0.1.0-dev.20261005.3**，本地可辨识组合，.1/.2历史不覆盖。
- [English](../../integration/development-delivery-2026-10-05-3.md)／[manifest](../../integration/development-delivery-2026-10-05-3.json)／[ADR-0008](../adr/0008-retain-sid-revocations-before-local-registration.md)。

## 修改与兼容

用户明确选择默认sid撤销标记，期限为配置本地sessionAbsoluteLifetime/默认14天。Registry markercheck与index publish/snapshot同短锁，Session/container在锁外。有效Logout无匹配/失败仍标记，迟到登记抛错、原cleanup503清本会话Token/结束Session/提交撤销，不业务success。同subject不同sid独立，unregister不删marker，repeat invalidate延长，exact expiry再允许；流量触发最多每分钟过期扫描。原no-arg/SPI保留，新增Duration构造器，auto-config用当前properties。

marker每配置issuer/client独立且进程内，非durablequeue/sharedstore；无countquota，内存随期限内唯一revokedsid增长。自有adapter保替换接口且自行等效register/invalidate协调。已授权业务在途/restart/跨实例storage/markerexpiry后永久保护未提供；sid不复用/callbackexpiry/墙钟支持仍假设。legacy direct subject仅当前快照；wire Logout仍验签sid。

维护脚本新增-JarPath/可选-ExpectedSha256，保固定SNAPSHOT默认/不选latest；helper在Java前拒缺失/非JAR/hash不符，只印路径/hash，保隐藏密码和Java exit。三个脚本一起交付，非boot JAR内资源，manifest独立hash。[维护用法](../operations/maintenance-artifact-selection.md)说明边界。无迁移/dependencylock/认证因素/业务表变化。

## 执行证据

```powershell
.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest.signedLogoutBeforeCallbackRejectsLateSessionWithoutCallingApplicationSuccess" --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoBackChannelSessionRegistryTest" --tests "org.las.sso.client.LasSsoBackChannelLogoutControllerTest" --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest" --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261005.3" "-PlasSsoStarterVersion=0.1.0-dev.20261005.3" --rerun-tasks --no-daemon --console=plain
```

- 默认/自有两签名callback先红：早Logout204后仍成功login；修后两绿。
- 新Registry10：无匹配/别sid、repeat/unregister、失效失败重试、配置与14天精确boundary、repeat延长、两受控并发order、锁外listener重入/别sid继续、legacy subject/非法Duration。
- callback35新增5：默认自有callback前/实际HTTP Codeexchange中Logout、owning-token撤销/无success/新sid可登、错误签名不污染。RSA/JWKS/PKCE真实，入站Servlet/listener/receiverdispatch/OP为fixture。
- 中间59=10+15+34绿后补错误签名；最终全量确认registry10/callback35及原receiver/replay。
- 约 **17:33–17:39（Asia/Shanghai）** 强制 **461server+193starter=654** 零失败/错误/跳过18tasks，BFFcompilebootJar/testNO-SOURCE，数据库实际运行。shutdown有pool访问已停Testcontainers fixture日志，报告仍0fail/error/skip。
- 外部PowerShell9精确空格path/usernameUUIDreset/digestguard/nativeexit通过；Java启动fixture非交互administrator/DB验收。
- 两实际脚本亦启动最终.3 JAR/hash、仅临时PostgreSQL、无console：nonzero/INTERACTIVE_CONSOLE_REQUIRED，users/credentialversion/audit/session/authorization/epoch快照不变；成功隐藏密码bootstrap/reset仍未验。
- 最终.3实际SSO/BFF Edge Playwright1.55.1默认/自有：ALTCHA注册、双browser同主体login、绑定password重输/signedauth_time、当前退出隔离/免密码返回、全局退出/真实签名Back-channel清其他browser。沿用[.2脚本边界](development-delivery-2026-10-05-2.md)，非productionTLS/RP15m或browser时序race。
- 本地file发布.3，两独立native.module/POM-only项目旧新publicAPIcompile/Security全7.1.1，无composite/inheritedlocks。
- 最终四进程日志password/client-secretcanary/BearerJWT恢复码Cookiepattern无命中，临时进程/DB清理；非完整生产Secret扫描。

## 来源

base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **加未提交工作区**。267file fingerprint d489f9fbed5619fcb51c3f5b3e96a319cac9c78061ef52bcb056b204789c0515，此次纳入scripts三文件/新registrytest，不能与旧263scope直接比；相对las-sso-server，排docs/build/externalconfig。

| 制品 | SHA-256 |
| --- | --- |
| Server bootJAR | 1543ab08c9fb055065615ee95b3cb353615c76cf0d7dd6a972890165d581d09d |
| Reference BFF bootJAR | 28b784d7ec3fae981f2eb9e7e7c1e6d775df13e245b3ceb215bfb23c94efcd0f |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

POM/module/三scripts/三锁另hash，默认build仍SNAPSHOT仅显式property覆盖；ExpectedSha256未传不强制比digest。hash非release签名，无commitpush/remote/productionDB/deploy。

## 剩余

durable撤销接受/replayindex/custommulti-instance时序、crashrestart/旧在途callback、productionTLSproxySecureCookie、actualrestoreepochbarrier、持续容量fullscan、真实隐藏consolebootstrapreset、external精确URI、consumerpending15m/committedhostedrelease独立。本轮完成默认运行JVM sid时序及维护制品选择的指定层验证。
