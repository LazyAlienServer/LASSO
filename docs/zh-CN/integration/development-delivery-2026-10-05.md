# 可辨识服务端/Starter开发交付 — 2026-10-05

.1历史快照：后续真实参考BFF联验发现UserInfo缺陷，修复服务端/浏览器证据见[交付.2](development-delivery-2026-10-05-2.md)。本manifest不代表当前源码fingerprint。

- ID：**0.1.0-dev.20261005.1**，本地开发兼容组合，非托管不可变生产release。
- 英文：[Identified server/starter development delivery](../../integration/development-delivery-2026-10-05.md)。
- [机器来源清单](../../integration/development-delivery-2026-10-05.json)。
- 范围：SSO-03公共授权参数/验证Token证据接点、SSO-06本地组合、SSO-04epoch；businessstore/permissions/highrisk归消费方。

## 1. 坐标/来源

| 项 | tuple |
| --- | --- |
| server | las-sso-server-0.1.0-dev.20261005.1.jar |
| starter | org.las:las-sso-spring-boot-starter:0.1.0-dev.20261005.1 |
| refBFF | las-sso-reference-bff-0.1.0-dev.20261005.1.jar，仅sample |
| 工具链 | Java21/Boot4.1.0/Framework7.0.8/SecurityAS7.1.1/JOSE10.9.1/clientSDK11.38.2 |
| DB | V1–V14，无新migration |
| 默认version | 不传显式property仍0.1.0-SNAPSHOT |

basecommit8cee3ed62fd7c53664e8026b99664c73fae5f19d、branchcodex/fix-sso-quality-review，**加未提交worktree**，不能HEAD还原。manifest263源码/build/test/gradle相对path-NUL-filehash-LF排序aggregateSHA256 `96c47532d86d04efd33006ca23db66ddde65f36a3427ac309e178579a959d846`，不含docs/build/workstationinit/externalconfig/Secret。manifestpath相对las-sso-server。

| artifact | SHA256 |
| --- | --- |
| server | c4957310e2f5bd33f99aaf41634f7ea94225bb3ebfdd230143a82975a6e8d587 |
| starter | 513874a0e5cb8d6918c7649617ca795c8bb3b7c2866f5ce6d4292717b50d05b5 |
| refBFF | 699b8491d5a5762d33db2588cee788aa0e7d00bff98b6ff6ab10e2a5abc8e9a3 |

另POM/.module/三lockhash清单，标实际build非签名/commit。不要相同ID覆盖不同内容，改ID重算；重build等价source也可不同Jarhash，sourcefingerprint也比。生产Secret/issuer/key/clientcfg不在tuple。

## 2. 公共授权customizer：SSO-03

原configure(http,applicationSuccess)兼容，新public overload接受Consumer<OAuth2AuthorizationRequest.Builder>，SpringPKCE之后、本地state/returnattributes之前，用trusted应用配置，不透传browserquery：

```java
las.configure(http, applicationSuccess, builder ->
    builder.additionalParameters(parameters -> {
        parameters.put("prompt", "login");
        parameters.put("max_age", "0");
    }));
```

每chain一次用一种overload。例子会令该chain每授权都要求password，非新consumerreauthendpoint/默认普通login建议。per-operation须应用pendinglocaltransaction/trustedresolver逻辑，不透传任意参数。customizer须保留clientredirectstate noncePKCE，替换builder字段归应用非自动安全合并。

Spring验证issuer/aud/sign/time/nonce/CodePKCE及LASregister后success可取OidcUser.getIdToken().getAuthenticatedAt()、sub/sid。应用须关联发起browser/login/pendingoperation、拒missingfuturestale、保留真实签名时间并自守十五分钟到期/撤销。普通callback/refresh不自动证recentpassword，refresh不升旧auth_time；不自动replay危险write/跨会话分享/加MFA业务claim。默认browserprompt/max_age/auth_time不透传，公共customizer显式trusted值。

两新签名callback：包外chain promptlogin/max_age0/state nonceS256returnTo/actualHTTPCodePKCE RSAJWKS、successauth_time读取/立即logout；另一默认不透传query。OP签名loopbackfixture、入站Servlet/事件fixture，auth_time由OPfixture，不证明liveLASbrowser/本地15m门禁；原server/JDBCtest另验SSO前置。生产browserresponse无Token/auth_time新增，testheader非API。

## 3. 查询兼容：SSO-04

server新增stateEpoch，strictDTO更新；仅同issuer/sub/epoch比较version，unknown含currentepoch。opaque不可排序UUID/time。restore前必须newsecurityepoch，consumer屏障废oldcache/inflight并安全建current；missingblank503/no-store不补造。七字段/precedence/恢复义务见[English查询](../../implementation/service-access-epoch-contract.md)／[中文](../implementation/service-access-epoch-contract.md)、[ADR-0007](../adr/0007-scope-service-access-versions-to-security-epoch.md)。

原5mSSOchallenge/V14、B4process/legacy503、B5timestampstate/returnTo、no业务claim继续；consumeradapter守契约。仅公共方法overload/queryfield，无新存tokencredential。

## 4. 构建/实际本地消费

serverJava21Docker：

```powershell
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261005.1" "-PlasSsoStarterVersion=0.1.0-dev.20261005.1" --rerun-tasks --no-daemon --console=plain

.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261005.1" --no-daemon --console=plain
```

实际filedevrepo在预批准temp、无remotecred/globalMavenLocal，含精确coordinate/Jar/POM/module。包外两Gradleconsumer nativeBoot4.1.0platform、无SSOcomposite/继承锁，原生.module与POM-only禁metadataredirect两种都**编译旧新configure调用**/Security全7.1.1。实际制品compile解析非MavenCLI/liveBFF/全应用验收。machine临时path非projectdefault，别机器显式repopublish/transfer并核hash，server是bootJar非starter依赖。

2026-10-05约**01:45–01:49上海**强制**460server/178starter**零失败/错误/跳过、**18task执行**，BFFcompileNO-SOURCE，两bootJar/metadata指定ID。较419/176新增S223+query17/load1共41server、callback2。XMLS2 17:46:24.196、query17 17:47:04.060、load1 17:47:10.736、callback30 17:49:06.338UTC日期为10-04，DB实际运行。build/publication后记录source/artifacthash。

## 5. 边界/外部

SSO-03已交publicsource/compiledcustomizer/验证Token接点，RPpending/callback/expiry/revoke仍external。SSO-06有identifiedlocaltuple/hasheslocks/sourcecommands/两制品消费，未commitsource/release签名/托管immutablerepo/liveconsumer/productionrelease。

SSO-04serverepoch/lifecycle/wire与本机8worker80query(59.57rps/p95203.5ms)非productionSLA/livebackupcache。SSO-05待external exactreachableURI。B1持久可靠接受/登记ordering/live双browserSSOrefBFF/productionTLSproxykey/backupcrashcapacityscan独立。无commitpush/生产DB/托管发布deploy，仅可辨识本地devpublication。
