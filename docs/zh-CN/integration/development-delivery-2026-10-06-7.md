# 刷新退出连续性与Grafana混合负载开发交付

- ID **0.1.0-dev.20261006.7**，2026-10-07验证。[English](../../integration/development-delivery-2026-10-06-7.md)／[manifest](../../integration/development-delivery-2026-10-06-7.json)／[hint修复](../implementation/refresh-logout-hint-history.md)／[ADR-0011](../adr/0011-preserve-id-token-logout-hint-association.md)／[Grafana混合](../operations/grafana-mixed-load-2026-10-07.md)。

## 变更

实际.6 shortToken长些workload发现refresh后global400：BFFinitialIDToken已非servercurrent。PG/MockMvc新增回归先red，V15 HMAC-onlyIDToken→auth history与save前捕获修logoutlookup，不扩大ATRTcode/不改成熟clientprincipalexactreturnvalidator。cascade随auth删除、preupgradecurrent首次后save保留，旧已丢不可恢复。无rawhistorypassword/newcredentialpermission，不重写BFFprincipal/auth_time。详情/pepper/historysizing见配对记录。

依赖/三锁/monitorinputs/dashboard/starterAPI保.6，SSOschema **V1–V15**，新.7组合/旧.6JARmanifest保，starterhash不变预期，BFF只版本辨识。

## 验证

Java21Docker最终 **2026-10-07 00:07:51–00:12:17 Asia/Shanghai** **505server193starter37reference=735**零失败错误跳过/20task/actualPG含V15。actualrefreshreplacement/oldhintlogout/preupgradecapture两32bytehash/未登记return400且auth保持/ATRT不查/cascade/repeathint400。initialIDfixture非签名browser证明，finalactualTLS补live。

真正Grafana12.2.0导入unchangedrepo/dashboardprivateProme，Viewer1440×1000/390×844八panel真数据canvas，无querypageerror/overflow；saveoverwrite/datasourcewrite403，截图保。早mobiletimeout是lazy挂载scroll修非dashboard变。

final.7 default/appowned **301.154/302.178s**，每mode5subject9browser/NginxTLS/JVMCA/CodePKCEJWKSUserInfo/actualrefresh/currentglobalBack-channel closedloop800ms。两套**12,598operation成功观察**含48passwordOIDC/24current/24SSOreentry/16global；120refresh成功0reuse/40processedBack-channel/final0pendingdeadbucket/sentinel保持。仅postrevocation允401SESSION_EXPIRED，正常200。fixtureAT45s/before10s非default15m；详latencymemsample/1unattributedsamplinggap/非productionsoak边界。旧.6alert/旧.5adminrestore保历史未.7重跑。

finalscan241lock157JAR0reportedvuln/Semgrep2331redirect保误报0error/sameverifiedrules/Gitleaks三路径0match，同旧DB/advisorycoverage，opaqueJAR0package无效。finalruntime定向secret无匹配，非所有秘密排除。native.module/POM-only .7独立编译oldnewAPI/patchbaseline，只localfilepub。

## 构建与来源

从`las-sso-server/`，Java21/Docker：

```powershell
.\gradlew.bat :test --tests "org.las.sso.persistence.AuthorizationPersistenceTest.originalIdTokenHintStillLogsOutAfterRealRefreshReplacesTheStoredIdToken" --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.7" "-PlasSsoStarterVersion=0.1.0-dev.20261006.7" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.7" --no-daemon --console=plain
```

Base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **加未提交工作区**，source303fingerprint `ee911dc45866501f70ce9773fdc19228bf1fafd55b37f3ef82a5117c300fe0d2`同scopealgorithm加V15，scan304另Compose。manifest18artifactscriptconfig/3unchangedlocks，migration在sourcefingerprint。HEAD不复现，defaultSNAPSHOT/旧artifact保。

| 制品 | SHA-256 |
| --- | --- |
| SSO bootJAR | ef4d296328005a66615efb0618ec1146128289dc31f5bcd968cb2b3b48548b43 |
| Reference BFF bootJAR | a8ddc1e12feff9370fdc3b810b8b3feb714dd06b51e6788d52ab9ef21cd0c8c2 |
| Starter普通JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

scan三锁`a7320a67e13783331300a69b47a64b9dacf18319a7301a9a1c7aed7d91360afa`、JAR`ff0794947c6eadbbb82d15aa0fc405c5f9dacae1ab0ffbd67b84e63d053d9217`、Semgrep`6e598a7f69a0e1e851983932fcc5fd1a2771b84b1662bd1115e0752fe6dc3ab5`，emptyGitleaks同旧，runtime`ef41bee420b87e2fa7d1eb405b2ef0b614bf99b67dfa01959e2625427595f37a`。private rawreportcache/screenshot/log临时保无长期保证。

专用GrafanaPromeTLSapp/proxyDB/containerDBvolume/network及accountadmincollector/certkeysettings清，acceptanceportsfree/no globalCAhosts。无commitpush/prodDBdeploy/signedhostedrelease/consumer实施。

## 剩余

小时級rampsoak/productionhardwareDBnetworkvolume/metricsoverhead/IDhinthistorysizing，Grafana生产authretentionbackupupdate、privateTLSrotation/oncallfailureescalation、remainingdomainexternalprobeUI/officialdomainCDNfirewall/backupkey与actualconsumer15mepoch仍开。完整B1/customdurableSession/index/replay/multiinstance及formalcommittedsignedrelease独立。本次完成实际GrafanaimportdisplayViewer与受控混合并修真实refresh→logout缺陷，不称productionready。
