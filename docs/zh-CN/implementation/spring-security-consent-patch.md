# Spring Security同意页补丁与依赖交付

- 状态：2026-10-04 D1/CVE-2026-47877依赖修复与受控兼容验证完成；完整扫描/真实发布验收独立。
- 英文对应：[Spring Security consent-page patch and dependency delivery](../../implementation/spring-security-consent-patch.md)。
- 跟踪：[状态地图§3.13](../../implementation/status-issues-and-plan-2026-10-02.md)。

## 1. 官方核验与范围

[官方Spring公告](https://spring.io/security/cve-2026-47877/)指默认同意页未编码用户值，7.1.0/7.0.0–7.0.6受影响，OSS修复7.1.1/7.0.7。custom consent不受该公告影响，但LAS合作方使用默认renderer，本轮不新增自定义页绕过。公开[7.1.1BOM](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-bom/7.1.1/spring-security-bom-7.1.1.pom)统一Authorization Server与Security模块patch。

核对[7.1.0源码](https://github.com/spring-projects/spring-security/blob/7.1.0/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/web/DefaultConsentPage.java)直接拼值，[7.1.1](https://github.com/spring-projects/spring-security/blob/7.1.1/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/web/DefaultConsentPage.java)HTML escape。早期应用注入未成功不改写成成功攻击。新增依赖renderer测试隔离converter/认证fixture，证明依赖缺陷，不声称每个值可通过LAS client/scope/UUID校验或已浏览器执行script。

## 2. 构建对齐/锁审查

catalog新增spring-security=7.1.1。server/BFF在Boot管理后导入Security BOM；starter本地Boot spring-security.version属性覆盖，导出api(platform(Security BOM))及实际直接使用config/client/JOSE的catalog版本依赖。原已有transitive库现直接声明，保证消费交付。

| 解析坐标 | 原→新 |
| --- | --- |
| server九Security项含Authorization Server/test | 7.1.0→7.1.1 |
| starter/BFF各八Security项 | 7.1.0→7.1.1 |
| 三模块Nimbus JOSE | 10.9→10.9.1 |
| starter/BFF Nimbus OAuth2/OIDC SDK | 11.37.2→11.38.2 |
| starter/BFF导出Security BOM | 新7.1.1platform锁项 |

三锁由Gradle生成非手改；starter原四test-Jackson新增保持、早于D1。无其他锁坐标/version/config变化。Boot4.1.0/Framework7.0.8/Jackson3.1.4/Java21保持。上游Security请求部分更高Framework/Jackson/Micrometer，但当前Boot管理仍对本组合权威；明示patch override在此回归，不称整体Boot升级或官方默认组合。无migration/账号权限/Secret/公开route/生产源码变化。

## 3. 制品元数据兼容

初BOM-only源码解析正确但.module未导出；native platform修Gradle格式，隔离POM-only消费者仍回到Security7.1.0。直接版本config/client/JOSE后两消费方式全部Security7.1.1。

工作区外一次性file repo先后发0.1.0-d1-probe/0.1.0-d1-probe-2。独立Gradle probe用native Boot4.1.0platform＋后者，无SSO composite/解析rule/继承锁。一路gradleMetadata()，一路mavenPom()+ignoreGradleMetadataRedirection()，断言非空Security模块集每个7.1.1；修直接依赖后都过。初POM-only7.1.0红灯保留阶段。

仅证明这两种Gradle解析元数据路径，不是实际Maven CLI（环境无Maven）或任意消费者override/strict旧锁/托管SDK发布。consumer仍审自己的BOM/version/锁，导出platform是recommendation非enforced。无远程upload/全局MavenLocal。默认starter/server仍0.1.0-SNAPSHOT，最终普通版本POM/.module已重生成，未commit。

在明确一次性repo重跑本地签发，Windows参数引用：

```powershell
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$ProbeRepositoryUri" "-PlasSsoStarterVersion=0.1.0-d1-probe-2" --no-daemon --console=plain
```

独立probe两subproject都native implementation(platform(Boot4.1.0))＋probe starter，task读取runtimeClasspath.incoming.resolutionResult，要求Security非空且每version7.1.1。内容改变重跑用新probe版本，不作正式release坐标。

## 4. 回归与打包证据

DefaultConsentPageDependencyTest新增**5项**实际依赖endpoint filter/默认renderer、受控converter/认证输入：clientId/consent state/principal/requested scope/approved scope。7.1.0有效red五项均原样HTML、7.1.1五项green要求原危险string不在/escape在。首次六项在render前400属fixture错误非XSS证明；改converter隔离并删不被该Code filter传递的user_code，不称device/requestURI渲染已覆盖。

AuthorizationConsentIntegrationTest新增**4项**真实配置Spring security/provider/PostgreSQL/MockMvc：合作方批准/JDBC Consent、CodePKCE签名UUID/aud/nonce/RT、原caller state重定向保留，取消无批准、其他主体不可批准、一方免consent。caller注入marker不渲染因真实flow生成自己的consent state，证兼容非应用XSS复现。实际password filter/encoder登录注册fixture，Servlet/browser传输mock。

最后server专项**110=5+4+10reauth+53persistence+2JDBC+25budget+11client**；starter专项**131=28callback+10chain+33logout+15receipt+45target**及BFFcompile，全零失败/错误/跳过。2026-10-04约**22:24–22:26Asia/Shanghai**强制全量**323 server/176 starter**同零失败/错误/跳过，**18actionable全执行**，BFF重编译NO-SOURCE，server/BFF bootJar与默认publication metadata生成。相对S6的314新增9；renderer XML14:25:25.244UTC，consent14:25:21.810UTC，实际PostgreSQL18.4Testcontainers。

server目录Java21/Docker命令：

```powershell
.\gradlew.bat dependencies :las-sso-spring-boot-starter:dependencies :las-sso-reference-bff:dependencies --write-locks --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.config.DefaultConsentPageDependencyTest" --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.config.DefaultConsentPageDependencyTest" --tests "org.las.sso.config.AuthorizationConsentIntegrationTest" --tests "org.las.sso.config.OidcPasswordReauthenticationIntegrationTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.session.JdbcSessionPersistenceTest" --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --tests "org.las.sso.admin.AdminClientServiceTest" --no-daemon --console=plain

.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest" --tests "org.las.sso.client.LasSsoSessionChainIntegrationTest" --tests "org.las.sso.client.LasSsoLogoutTokenValidationIntegrationTest" --tests "org.las.sso.client.LasSsoBackChannelLogoutControllerTest" --tests "org.las.sso.client.LasSsoReturnToTest" :las-sso-reference-bff:compileJava --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication --rerun-tasks --no-daemon --console=plain
```

三模块dependencyInsight核对实际runtime/lock。两bootJar BOOT-INF/lib所有Security7.1.1，server含fixedAuthorization Server，两者JOSE10.9.1，BFFSDK11.38.2。仅打包证据非JAR实际启动。redXML后续覆盖，输出/状态§3.13保留阶段。

## 5. 剩余

D1指定依赖已升级、补丁受控兼容已验。A1/A2随后补直连TCP/Tomcat rootcontext，见[Servlet记录](servlet-path-security.md)/状态§3.14，最新419 server176starter，上方323/176保留D1。完整SCA/SAST/Secretlog/browser script与consentCSP/TLSproxy/生产key/liveartifact/备份restartdeploy、S2/B1完整交付独立，不推断其他CVE发布ready。无schema/生产DB/deploy/commit/托管发布，预防renderer编码与制品解析核验现有runtime/打包证据。
