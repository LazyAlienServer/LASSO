# 管理员维护与撤销Cookie OIDC交付 — 2026-10-06

.20261006.2历史快照，后续[.20261006.3](development-delivery-2026-10-06-3.md)修deployheader入口/增referenceHTTPSprofile与真TLS验收；原manifest非当前scope来源。

- ID **0.1.0-dev.20261006.2**，本地可辨识组合，旧ID保历史，.1hash不等current源码。
- [English](../../integration/development-delivery-2026-10-06-2.md)／[manifest](../../integration/development-delivery-2026-10-06-2.json)／[维护验收](../operations/administrator-maintenance-acceptance.md)。

## 缺陷修复

真实adminreset后retainedoldSSOCookie发新OIDC，filter结束旧Session后、Spring保存验证原Authorization前redirectlogin，真密码通过却到account非BFF。reset本身已经撤销安全状态；两普通/forced actualPGMockMvc新回归先红（freshanonymouspending为空）。

AccountSessionValidationFilter现清旧SecurityContext/Session，**仅PathPattern匹配GET/oauth2/authorize** anonymous继续原Spring验证clientredirectscopePKCEprompt/max_age，原OIDCpasswordfilter在校验后建newbrowsertransaction保存currentrequest。不拷旧savedrequest/challenge/auth_time，不保存任意未验证redirect、不保旧认证。普通API/browserexpiry拒绝不变，wrongoldpassword/promptnone/badmaxage/evilredirect仍拒。

只改一serverfilter和真实OIDC测试，無dependencylock/migration/SPI/permission/因素/UI/scripts变更，不是RP15m gate。starterbytehash不变正常。

## 命令验证

Java21/Docker，las-sso-server/：

```powershell
.\gradlew.bat :test --tests "org.las.sso.config.OidcPasswordReauthenticationIntegrationTest.revokedBrowserAuthorizationKeepsValidatedRequestThroughFreshPasswordLogin" --no-daemon --console=plain
.\gradlew.bat :test --tests "org.las.sso.config.OidcPasswordReauthenticationIntegrationTest" --tests "org.las.sso.security.AccountSessionValidationFilterTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.2" "-PlasSsoStarterVersion=0.1.0-dev.20261006.2" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.2" --no-daemon --console=plain
```

两初red→green；中间71=reauth17+filter1+persistence53过后补badage，最终reauth18新增7：revokedsid普通forced/new anonymous/真密码CodeS256/signstate nonce sid auth_time；credentialversion普通forced/wrongold拒/new密码callback；none login_required/nocode；evilredirect/oldchallenge拒；badmaxage拒新密码事务。

编译String误代HttpMethod校正。testwrongpassword共network达到fixtureCAPTCHA/UI200，隔离network后绿，不放宽prodlimit。早browser错预期login401、原302/login?error校准，非新漏洞。

约 **03:46–03:51 Asia/Shanghai** 强制 **486server+193starter+37refBFF=716** 零failerror skip20tasks/两JARmetadata/真PG。shutdownpool停TC日志不掩失败。最终hashJAR再实际维护全流程。

## 真维护console/browser

真实两PowerShell/exactJARhash/WindowsPTYJava21、独立PG18.4/RSA3072key/随机外配置，local只初clientsseeder，后用nonlocal持久key。Edge headlessPlaywright1.55.1/defaultrefBFF/双Context，无已有用户生产库。详细见[运维记录](../operations/administrator-maintenance-acceptance.md)。

bootstrapmismatch nonzero不改→成功隐藏两输入uniqueadmin/temp24h/version1；双temporarySession强改/不能管理accountrecoveryOIDC。缺CSRF/foreignOrigin403/确认不符不发/wrongtemp401不改。UI204→ACTIVE2/tempNULL/旧SSOtemp拒。

new密码管理页/恢复UI十个fourgroup80bit、真实download内容匹配后删除、reload无plaintext/DBdigest。双BFFactualCodePKCE/Token/UserInfo。停SSO，true resetcancel/mismatch/reuse/short均nonzero快照不变，hidden successACTIVE3/oneaudit/recoverySessionAuthRTfamily废/outbox保存。

同持久key重启，先等真resetBack-channel再查BFF，双Session清；oldSSOCookie/AT/RT/password拒。新reset密码retainedcookie可回BFF/管理/global真实signed清双。password/consoletranscript/Token/codefile只harness内存不print，download删除，四logtargetedpasswordkeypeppersecretJWT等无命中；operatoroffline保存/copyprint/旧码fullbrowser恢复未验。

两独立native.module/POM-onlyoldnewAPIcompile/Security7.1.1/localfile.2，无MavenCLI/hosted。

## 来源

base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **加未提交worktree**，284sourcebuildtestGradlescripts，fingerprint22581a0b9f1aa5b548ac93732d6042dd0a0933b5a8112961972e75ce7b5fd968，10artifactscriptmigration/3unchangedlocks，相对las-sso-server排docs/buildexternal。

| 制品 | SHA-256 |
| --- | --- |
| Server bootJAR | e5a178a1cf728a7c59b13313cd5802495781ee5093158e098bc595dfb1de54ce |
| Reference BFF bootJAR | b0f07051f78672c7309e2ac57b231894af80528e7da93677ec2924d84e21535a |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

defaultSNAPSHOT、.2不覆盖.1，source未commit/hash非signature/reproducibility，无commitpush/productiondata/remote deploy，临时serviceDBkeyconfig清理记录。

最终已核processidentity停SSO/BFF，删专用PG容器volume、外credentials/pepper配置与PKCS#12fixturekey，浏览器下载恢复plaintext已删，无production资源。

## 剩余

空库维护/firstchange/reset核心已isolated真验；populated晋升/取消、live24hexpiry/其他平台终端/operatoroffline/fullrecoveryUIaccessibility/productionTLSproxySecureCookie独立。非consumer15m/generationcache/B1presubmitgap/持久Sessionindexreplaymultiinstance。cachedoldkid/productionRPOkeybackup、queue deadkeyrotation/fullscan容量monitor/externalURI/signedcommitrelease开放；旧restorequeue证据按旧范围保留，不称全部早processdrill重跑。
