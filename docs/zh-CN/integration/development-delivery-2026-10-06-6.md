# 私有指标与告警开发交付 — 2026-10-06

- ID **0.1.0-dev.20261006.6**；[English](../../integration/development-delivery-2026-10-06-6.md)／[manifest](../../integration/development-delivery-2026-10-06-6.json)／[监控契约操作与证据](../operations/monitoring-and-alerting.md)。

## 交付范围

仅SSO固定类别Micrometer：formlogin/knownadmin失败/internal认证资格/refresh结果与committedreuse/新HTTP注册/Back-channelreceipt；hashTimer保原encoderpolicy。commit敏感待commit，无user/client/IP/token/request标签。可选独立DBsnapshot activeSession/pendingdeadexpiredclaimoldest/validfreshfailure，scrape不查DB，失败保明确invalidatedlastknown。

optinmonitoringprofile独立loopback9091，必须专用cost10–14bcrypthash/固定collectorname，无状态GETPrometheusonly/noCookieSavedRequest。browser/OIDCclient拒、collector不querybusiness、appport/nonGET拒、disabled即便手工expose也deny。health随managementlistener迁移，baseDBreadiness/liveness保持。无API/schema/migration/因素businesspermission变，starter/reference公共APIresources不变。

Prometheusadapter1.17.1加7serverruntime锁row，starter/BFF锁与Boot4.1.1/Framework7.0.9/Security-AS7.1.1/Tomcat11.0.26/Jackson3.1.7/BC1.86/pgJDBC42.7.13/Log4j2.25.5保。deploy/monitoring交collector/rule-test/7rule/Alertmanagerprivatewebhook/8panel10queryGrafana，无凭据。

## 验证

Java21/Docker强制 **21:42:33–21:48:35 Asia/Shanghai**，**504server193starter37reference=734**零失败错误跳过/20task/actualPG，17new与原refresh/receipt指标断言。native.module/POM-only独立.6编译/patch图核，只localfilepub。finalhash两个bootJAR default/appowned真实NginxTLS/Edge login/reauth/currentglobalHTTPSBack-channel/Cookie/forwardIP **22:11–22:12**通过，原.5adminrestore保历史，本轮未重跑。

monitorfinalruntime **22:01:03–22:06:30** actualJAR/PG18.4/Prometheus3.6.0/Alertmanager0.28.1/bearerwebhook6stage：domain/JVMpool及10PromQL，1moldoutbox/DBsnapshotfail与2mscrapeauthfail firing且resolved到真实authwebhook；expression/window不变，pollnotifycadence显式fixture加速。只alertname/job/instance/severity标签。config/ruleunit过，actualGrafanaimportrender/全部7livefiring不宣称。

finalscan241lock157JAR0reportedvuln、Semgrep2321redirect保误报0error、Gitleaks source/JAR/allhistory0match。rulesremoteTLS错误改用已验samehashcache/source不上送；opaqueJAR0package无有效覆盖。5monitor+8TLSfinalog定向secret无命中，沿旧coverage/advisoryfreshness限制。

## 消费与来源

从las-sso-server/，Java21/Docker：

```powershell
.\gradlew.bat dependencies --write-locks --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.6" "-PlasSsoStarterVersion=0.1.0-dev.20261006.6" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.6" --no-daemon --console=plain
```

base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **加未提交工作区**，source302fingerprint `75864917a6114ef9018bf33cc54b5353b2bfc4cf7c86615384418e904bad9e9d`同算法scope加1profile5Java4test5deploy；scan303另Compose、manifest18artifactscriptconfigmigration/3locks1changed。HEAD不复现，defaultSNAPSHOT/旧IDJAR保。

| 制品 | SHA-256 |
| --- | --- |
| Server bootJAR | ee73250a4276f80dbe397dfa14277f52cf2489d4a67f57a582a1fcb9e059ac19 |
| Reference BFF bootJAR | b151301f8d6b3ab7651a6214772236c6ad67ddbf19a64a34954816b817c71691 |
| Starter普通JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

scan锚点三锁`92eab0ace238e8b73a40fcfed3c359b03e93c6bb30f5668baa6662adbd073b50`、JAR`b4257c448f7e6cc3c1f7ebb77d83680539bafc0f3fdd27e867e7ca4922ae2f4c`、Semgrep`a9edc26cf80e95e58765f8a8f9fb55a50eda638256c4464465ab0e8701d673b0`。emptyGitleaksJSON同旧hash，runtime`f3c72c6ff14a1b074d53ed67260331a40fccf7ff725ae346d6e573b86e940983`，rawprivate临时无长期保留宣称。

专用monitorTLS容器networkDBvolume/secret/hashfile及ports清，无commitpush/托管productionrelease/deployDB/consumer/globalCAhosts。readinessmetricsalerts与样例是开发交付，不是approvedproductionconfig。

## 剩余

productionprivateTLS/rotation失败throttle、actualGrafanaimportrender权限、真人oncallroutefailureescalation、剩余domainmetrics/diskbackupclockprobe、持续multiuserOIDCrefreshlogout/largeDBpoll/metricsoverhead仍开。正式committedsignedrelease/officialdomainCA-CDNfirewall/productionbackupkey/consumer15mepoch与完整B1独立。本次仅完成coremonitor和3真alert/recovery。
