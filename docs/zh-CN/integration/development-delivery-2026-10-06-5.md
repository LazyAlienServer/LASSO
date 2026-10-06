# 数据库readiness修复与最终制品开发交付 — 2026-10-06

- ID **0.1.0-dev.20261006.5**；[English](../../integration/development-delivery-2026-10-06-5.md)／[manifest](../../integration/development-delivery-2026-10-06-5.json)／[最终真实验收](../operations/final-artifact-acceptance-2026-10-06.md)／[容量监控限制](../operations/capacity-readiness-2026-10-06.md)。

## 修复与范围

升级`.4`JAR真实TLS/admin/restore重验通过，后续DBdown发现readiness200而query503。新增回归先red，baseapplication.yml显式纳`readinessState,db`。最终HTTP/PGtest及liveJAR验readiness200→503→200/liveness始终200/query503→200/无healthcomponent详情。新增1test，无生产Java/API/migration/凭据因素/业务规则变。Boot4.1.1/Framework7.0.9/Security-AS7.1.1/Tomcat11.0.26/Jackson3.1.7/BC1.86/pgJDBC42.7.13/Log4j2.25.5和三锁保持.4。

## 验证

Java21/Docker最终强制 **19:43:45–19:49:43 Asia/Shanghai**，**487server193starter37reference=717**，0失败/错误/跳过、20tasks全执行、实际PG含down/restart。两bootJAR/startermetadata/localfilepub/native.module和POM-only新旧API消费已验；starter字节hash保持，BFF runtime只辨识版本变化。最终fingerprint含改进fixedportstop/starttest，后重build保持外部演练所有runtimehash。

精确final.5JAR双referencechain在真正privateNginxTLS/Edge、真hiddenbootstrap/强改download/offlinereset废旧状态、实际PGdump/restore/newkeyepoch/旧CookieCodeATRT拒/freshlogin/后台任务/globalLogout全通过，详[验收](../operations/final-artifact-acceptance-2026-10-06.md)。有界livequery/login/Argon2/JVMpoolSQL/stopstart观察完成，持续production sizing/领域埋点alerts仍pending；最终8workerquery60.93req/s/p95187.02ms、16worker91allow5budget429/无failurebudgetrow泄漏，是短workstationfixture非productionSLA。

finalTrivy三锁234/解包JAR150包0报告vuln，Semgrep221path1保留人工redirect误报0error，Gitleaks source/JAR/allhistory0match。同[扫描记录](../implementation/security-scan-2026-10-06.md)DBrules限制，opaqueJAR0package不算有效覆盖。最终定向logsecret：8TLS4admin20restore2capacity，不称所有秘密/漏洞已排除。

## 命令与来源

从`las-sso-server/`，Java21/Docker：

```powershell
.\gradlew.bat :test --tests "org.las.sso.config.DatabaseReadinessIntegrationTest" --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.5" "-PlasSsoStarterVersion=0.1.0-dev.20261006.5" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.5" --no-daemon --console=plain
```

Base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **加未提交工作区**。source287 fingerprint `f23381a9ae0f86f3624d1be1a2bd1decc2695113b3f1bb3acc334dcffff1ed57`，同.4算法范围加1test；manifest12artifact/script/config/migrationhash/3unchangedlocks，扫描288另含Compose。HEAD不复现，默认SNAPSHOT/旧ID保，无commitpush/托管签名发行/productionDBdeploy/consumer实施。

| 制品 | SHA-256 |
| --- | --- |
| Server bootJAR | f845e54b7dcde555894a66d9a52f458c48baeb5cb95e4db3c5a7461461f3ae65 |
| Reference BFF bootJAR | 34af02ba84a1026c20171a35349bad49568ccd6b3985d57d5a3b92c6c2c8e78e |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

privatefinalscan锚点：三锁SCA `8fd8f646a50f46bf79b4c4e60f64922b37d2c6e2278b4eff8967fb141778f5aa`、解包SCA `29fecc631b5673cdbcd09c136593cf69678e85d54f187a746bbe402630831b40`、Semgrep `28ea56859a790380427c3b5f3dcd1a03967405c5d9f61734bd939c8c0bfe1c91`，各emptyGitleaksJSON `37517e5f3dc66819f61f5a7bb8ace1921282415f10551d2defa5c3eb0985b570`。

专用appDBproxy容器volume/network、detachprocess/generatedprivatekeycerttrust/settings/state均清，四acceptanceportfree，无globalCAhosts。private临时report/inventory/cache/log保本机无长期保证。

## 剩余

领域指标/privateexport/dashboard/alertdelivery、实际生产硬件持续混合负载、productionimageOSJDKproxy/fullruntime log/DAST/UIaccessibility、officialdomainCArenewal/CDNfirewall、生产backupkeycustody与实际consumer15m/epoch/adoption仍开。完整B1presubmitgap/customdurableSession/index/replay/keyrotation及可选multiinstance独立，资格事件延期；不称productionready/正式signedrelease完成。
