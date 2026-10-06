# Private Metrics and Alerting Development Delivery — 2026-10-06

- ID **0.1.0-dev.20261006.6**; [Chinese](../zh-CN/integration/development-delivery-2026-10-06-6.md), [manifest](development-delivery-2026-10-06-6.json), [monitoring contract/operations/evidence](../operations/monitoring-and-alerting.md).

## Delivered scope

SSO-only fixed-category Micrometer counters for form-login, known-admin failures, internal authentication/eligibility, refresh outcomes/committed reuse, new HTTP registration and persisted Back-channel receipt transitions; password hash timers preserve original encoder policy. Commit-sensitive observations wait for commit. No user/client/IP/token/request labels. Optional scheduled DB snapshot exports active sessions and pending/dead/expiredclaim/oldest logout work plus validity/freshness/failure. Scrapes do not queryDB; failed polls retain explicitly invalidated last-known values.

Opt-in monitoring profile provides separate loopback management listener9091, required independent cost10–14bcrypt hash and fixed collector username. Stateless GETPrometheus-only security, no Cookie/browser saved request; browser/OIDCclient credentials rejected, collector cannot business-query, appport/nonGET denied. Disabled accidental exposition denied. Health moves to management listener in this profile; base readinessDB/liveness behavior retained. No API/schema/migration/credential-factor/business-permission change; starter/reference public APIs/resources unchanged.

Prometheus adapter1.17.1 adds seven server runtime-lock rows; starter/BFF locks and Boot4.1.1/Framework7.0.9/Security-AS7.1.1/Tomcat11.0.26/Jackson3.1.7/BC1.86/pgJDBC42.7.13/Log4j2.25.5 baseline preserved. Collector/rule-tests/7alert-rule/Alertmanagerprivatewebhook/8panel10queryGrafana inputs underdeploy/monitoring, no credential included.

## Verification

Forced Java21/Docker **21:42:33–21:48:35 Asia/Shanghai**: **504server+193starter+37reference=734**,0failures/errors/skips,20tasks executed,actualPG. Seventeen new tests plus expanded refresh/receipt metric assertions. Native.module/POM-only isolated .6 consumers compile and verify patched graph; only local filepublication. Both final-hashbootJARs passed default/application-owned actualNginxTLS/Edge browser login/reauth/currentglobalHTTPSBack-channel/Cookie/forward-IP guards22:11–22:12. Prior .5admin/restore evidence remains historical, not rerun in this round.

Monitoring final runtime22:01:03–22:06:30: actualJAR/PG18.4/Prometheus3.6.0/Alertmanager0.28.1/bearerwebhook,6stages passed. Actual domain/JVMpool scrape and10PromQL expressions,1moldoutbox/DBsnapshotfailure and2mscrapeauthfailure rules fired and each resolved to real authenticatedwebhook. Expressions/windows unchanged; polling/notification cadence explicitly accelerated fixture. Onlyboundedalertname/job/instance/severitylabels. Config/rule unit validations passed; actualGrafanaimport/render and all7rulelivefiring not claimed.

Final scans:241lock/157extractedJARpackage rows0reportedvuln, Semgrep232paths1retainedredirectfalsepositive0errors, Gitleaks source/JAR/allhistory0matches. TLSremote-rule fetch failure corrected with verified identical cached rulepacks, no source upload. DirectopaqueJAR0package remains ineffective coverage. Five finalmonitoring+eightfinalTLSlogs passed targetedsecretchecks; coverage/advisory freshness remains as prior scan contract.

## Consumption and provenance

Fromlas-sso-server/, Java21/Docker:

```powershell
.\gradlew.bat dependencies --write-locks --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.6" "-PlasSsoStarterVersion=0.1.0-dev.20261006.6" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.6" --no-daemon --console=plain
```

Base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **plus uncommitted worktree**. Source302fingerprint `75864917a6114ef9018bf33cc54b5353b2bfc4cf7c86615384418e904bad9e9d`; unchangedalgorithm scope, one newprofile/fiveJava/fourtest/fivedeployinputs. Scan303additionallyCompose; manifest18artifact/script/config/migrationhashes and3locks(onechanged). HEAD alone cannot reproduce currentcontent; defaultSNAPSHOT/olderIDJARs preserved.

| Artifact | SHA-256 |
| --- | --- |
| Server bootJAR | ee73250a4276f80dbe397dfa14277f52cf2489d4a67f57a582a1fcb9e059ac19 |
| Reference BFF bootJAR | b151301f8d6b3ab7651a6214772236c6ad67ddbf19a64a34954816b817c71691 |
| Starter plainJAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

Scananchors: three-lock`92eab0ace238e8b73a40fcfed3c359b03e93c6bb30f5668baa6662adbd073b50`, extractedJAR`b4257c448f7e6cc3c1f7ebb77d83680539bafc0f3fdd27e867e7ca4922ae2f4c`, Semgrep`a9edc26cf80e95e58765f8a8f9fb55a50eda638256c4464465ab0e8701d673b0`. EmptyGitleaksJSONsamehashasprior. Runtime summary`f3c72c6ff14a1b074d53ed67260331a40fccf7ff725ae346d6e573b86e940983`. Rawreports private temporary, no long-termretention claim.

Dedicatedmonitoring/TLScontainers,network/DBvolumes,secret/hashfiles andacceptanceports cleared. No commit/push/hostedproductionrelease/deployDB/consumerimplementation/globalCAhosts change. Readiness, metrics, alerts and samples are developerdelivery, not approvedproductionconfiguration.

## Remaining

ProductionprivateTLS/rotation/failurethrottle, actualGrafanaimport/render/permissions, realoncall routing/failureescalation, remainingdomainmetrics/diskbackupclockprobes, sustainedmultiuserOIDCrefreshlogout/largeDBpolling/metricscost remain. Formalcommittedsignedrelease, officialdomainCA/CDNfirewall, productionbackupkey, consumer15mepoch and broaderB1 durability remain independent. This closescoremonitoringimplementation andthreeactualalert/recoverypaths only.
