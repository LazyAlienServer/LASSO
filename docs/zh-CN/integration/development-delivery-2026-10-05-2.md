# UserInfo 修复与真实浏览器开发交付 — 2026-10-05

.2历史快照，后续[.3交付](development-delivery-2026-10-05-3.md)增加默认sid登记时序保护/维护制品选择；本manifest不等当前源码。

- ID：**0.1.0-dev.20261005.2**，可辨识本地开发组合。
- [English](../../integration/development-delivery-2026-10-05-2.md)／[JSON manifest](../../integration/development-delivery-2026-10-05-2.json)。
- 修复后服务端/参考BFF使用.2；[.1](development-delivery-2026-10-05.md)保留历史快照，其manifest不代表当前源码。

## 1. 实际缺陷与修复

真实参考BFF请求openid profile，Code兑换成功却因invalid_user_info_response结束回调。Discovery公布的/userinfo对服务端Bearer请求302跳转/login。既有starter签名OP fixture只请求openid、仅Token/JWKS，未触发真实UserInfo。

AS链原缺resource-server支持；只加JWT仍失败，因为Security7.1.1自动选应用Bean ClientAuthenticationRateLimitConverter作为resource-server AuthenticationConverter。该客户端Secret预算观察器返回null，不解析Bearer；针对性Filter探针确认正确AS链有Bearer/UserInfo但无Bearer认证。

AuthorizationServerConfiguration显式配置BearerTokenAuthenticationConverter/既有JWT decoder，并设置UserInfo Bearer401入口，不受HTML Accept影响。Spring OIDC provider仍核验活动Authorization/AT与openid scope；缺失/错误/撤销Token及仅Cookie均不能读UserInfo。无依赖、锁、schema、scope或认证因素变化。依据：[Spring自动选择源码](https://github.com/spring-projects/spring-security/blob/7.1.1/config/src/main/java/org/springframework/security/config/annotation/web/configurers/oauth2/server/resource/OAuth2ResourceServerConfigurer.java)、[OIDC UserInfo](https://openid.net/specs/openid-connect-core-1_0.html#UserInfo)。

## 2. 回归与真实浏览器

新增OidcPasswordReauthenticationIntegrationTest.realIssuedAccessTokenCanReadUserInfoWithoutBrowserCookieAndRevocationStopsIt：真实注册/密码登录/Code+S256兑换后，无browser Session查询。修复前多次expected200/actual302，JWT-only仍红，显式converter后绿。扩大验GET/POST200、UUID sub/preferred_username/no email，匿名及HTML Accept401/WWW-Authenticate、cookie-only401、撤销旧AT及错误Bearer401。

```powershell
.\gradlew.bat :test --tests "org.las.sso.config.OidcPasswordReauthenticationIntegrationTest.realIssuedAccessTokenCanReadUserInfoWithoutBrowserCookieAndRevocationStopsIt" --no-daemon --console=plain
.\gradlew.bat :test --tests "org.las.sso.config.OidcPasswordReauthenticationIntegrationTest" --tests "org.las.sso.config.AuthorizationConsentIntegrationTest" --no-daemon --console=plain
```

约03:36（Asia/Shanghai）专项15=11+4，零失败/错误/跳过，真实PostgreSQL18.4/Testcontainers。临时带标签Filter/callback诊断已在.2构建前从仓库源码删除。

联验使用.2实际boot JAR、Java21、loopback HTTP issuer http://127.0.0.1:9000、BFF http://127.0.0.1:8088、一次性PostgreSQL18.4、local客户端/临时key，Playwright1.55.1驱动系统Edge headless。每次两个独立BrowserContext与新账号，默认链和application-security profile均通过：

| 场景 | 实际断言 |
| --- | --- |
| 注册 | 实际CSRF/CAPTCHA/注册API及ALTCHA widget求解；十个新四组恢复凭据，不打印/保留明文 |
| 双登录 | 两密码登录真实Code/PKCE/Token/JWKS/UserInfo回调到/protected；同UUID、不同BFF Cookie、两活动SSO sid/Authorization |
| 重认证 | prompt=login/max_age=0到SSO密码页；别浏览器challenge401；错密码不签发新证据；正确重输完成BFF回调、签名auth_time更新/SSO sid不变 |
| 当前退出 | A匿名/B仍登录、两SSO会话活动；A再次顶层SSO免密码返回 |
| 全局退出 | A完成真实SSO退出/return-state；B经真实签名Back-channel在脚本15秒等待内匿名、活动SSO会话0 |

隔离脚本在BFF生成的授权Location上加标准重认证参数，保留其服务端state/nonce/PKCE；一次性fixture DB内存检查签名claims，BFF验证真实callback。这是SSO协议验收，**不是消费pending操作/十五分钟高风险门禁**。公共可信customizer仍有.1独立签名fixture/包外编译证据。

强制全量后重新启动manifest最终hash的server/BFF JAR，默认/自有两模式闭环再次通过。脚本/log仅预批准外部browser-acceptance临时目录，不是项目默认配置；联验结束停止进程/删除一次性数据库容器。

最终四进程日志针对性检查未见fixture密码/client-secret canary或Bearer/JWT/恢复码/session-cookie值pattern，不等于完整源码/history/生产秘密扫描。

早期多次尝试耗尽一次性注册限流429，只重置fixture桶；Playwright URL-route注入未让预期参数进入重定向，仍普通登录/protected，改BFF Location标准参数后完成。两者为环境/脚本校准，非新生产缺陷。

## 3. 可辨识制品与全量

指定属性三模块.2、默认仍0.1.0-SNAPSHOT，Flyway V1–V14不变。Starter坐标org.las:las-sso-spring-boot-starter:0.1.0-dev.20261005.2。

来源为codex/fix-sso-quality-review基线8cee3ed62fd7c53664e8026b99664c73fae5f19d **加未提交工作区**，不能只按HEAD还原。263源码/build/test/Gradle文件fingerprint：64bee2f4a7d0563aa7b43f64e31226132cd298fd5d1a56d2321b15be86df1365，路径相对las-sso-server，不含docs/本机外部配置/Secrets。

| 制品 | SHA-256 |
| --- | --- |
| Server boot JAR | b1a0b4a98e2601cf96522c8b82d4b9a75b905e9e0480f3c4e75ea6f6bc85b8ba |
| Reference BFF boot JAR | d9d7ca250c4bd825572a67d2d53f73780084a50bd1ef4b97b9491662ae15d3c9 |
| Starter JAR | 513874a0e5cb8d6918c7649617ca795c8bb3b7c2866f5ce6d4292717b50d05b5 |

本轮改服务端配置/回归不改starter类，因此starter字节hash不变；POM/module版本/hash和三锁另记。

在las-sso-server/，Java21/Docker：

```powershell
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261005.2" "-PlasSsoStarterVersion=0.1.0-dev.20261005.2" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261005.2" --no-daemon --console=plain
```

约03:53–03:57（Asia/Shanghai）强制461server+178starter=639项，零失败/错误/跳过，18任务执行，两bootJar/metadata生成。BFF仍test NO-SOURCE，browser独立联验不算Gradle测试。临时file仓库发布.2，独立原生module/POM-only消费者重编译旧新public API/Security全7.1.1，无composite/继承锁，非MavenCLI/托管release。

## 4. 边界

本地HTTP/browser不验生产TLS/proxy/Secure Cookie、完整UI/可访问性、真实刷新到期计时、持久作业/replay/index、重启/多实例或通知先于登记协调。消费pending/十五分钟到期撤销/业务权限归消费方。actual backup/epoch屏障、持续容量、完整扫描、管理员交互维护、外部精确URI、committedsource/托管不可变release仍独立。

.1原内容保留，新内容.2，hash非release签名。无commit/push/生产数据库/远程发布或部署。
