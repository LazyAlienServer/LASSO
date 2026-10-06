# Database readiness repair and final-artifact development delivery — 2026-10-06

- ID **0.1.0-dev.20261006.5**, [Chinese](../zh-CN/integration/development-delivery-2026-10-06-5.md), [manifest](development-delivery-2026-10-06-5.json), [final external acceptance](../operations/final-artifact-acceptance-2026-10-06.md), [capacity/monitoring limits](../operations/capacity-readiness-2026-10-06.md).

## Repair and scope

The upgraded `.4` JARs passed real TLS/admin/restore repetition. A subsequent real DB outage exposed readiness200 while business queries503. New regression failed before the repair; base `application.yml` now explicitly includes `readinessState,db`. Final real HTTP/PG regression and live JAR outage verify readiness200→503→200, liveness200 throughout, queries503→200, no health component details. One new test; no production Java/API/migration/credential factor or business policy change. Boot4.1.1/Framework7.0.9/Security-AS7.1.1/Tomcat11.0.26/Jackson3.1.7/BC1.86/pgJDBC42.7.13/Log4j2.25.5 and all three locks unchanged from `.4`.

## Verification

Final Java21/Docker forced build **19:43:45–19:49:43 Asia/Shanghai**: **487server+193starter+37reference=717**, zero failures/errors/skips,20tasks executed, actual PG including database outage/restart. Two bootJARs, starter metadata, isolated local publication, native-module/POM-only old+new API consumers verified. Starter bytehash intentionally unchanged; BFF runtime only version identification changes. Final source fingerprint includes the improved fixed-port stop/start test; subsequent forced rebuild preserved all runtime hashes used in external drills.

Exact final .5 JARs passed both reference chains under actual privateNginx/TLS/Edge, real hidden bootstrap/first-change/download/offline reset/old-state rejection, and actual PGdump/restore/newkeyepoch/oldCookieCodeATRT refusal/freshlogin/scheduler/globalLogout; see detailed [acceptance](../operations/final-artifact-acceptance-2026-10-06.md). Bounded live query/login/Argon2/JVM/pool/SQL and stop/start observation completed, but sustained/production sizing and domain instrumentation/alerts remain pending. Final recorded8worker query60.93req/s/p95187.02ms;16worker91allows/5budget429, no leaked failure-budget rows. This is a short workstation fixture, not a production SLA.

Final Trivy three-lock234/extractedJAR150package rows0reported vulnerabilities; Semgrep221paths/1retained manually reviewed redirect false positive/0errors; Gitleaks source/JAR/all-history0matches. Same DB/rules/coverage restrictions as [scan record](../implementation/security-scan-2026-10-06.md); direct opaqueJAR0packages is not counted as coverage. Targeted final runtime secret checks:8TLS+4admin+20restore+2capacity logs. No universal no-secret/no-vulnerability claim.

## Commands and provenance

From `las-sso-server/`, Java21, running Docker:

```powershell
.\gradlew.bat :test --tests "org.las.sso.config.DatabaseReadinessIntegrationTest" --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.5" "-PlasSsoStarterVersion=0.1.0-dev.20261006.5" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.5" --no-daemon --console=plain
```

Base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **plus uncommitted worktree**; source287 fingerprint `f23381a9ae0f86f3624d1be1a2bd1decc2695113b3f1bb3acc334dcffff1ed57`, same algorithm/scope as .4 with one new test. Manifest12artifact/script/config/migration hashes,3unchanged lock hashes; scan inventory288 additionally includesCompose. HEAD alone cannot reproduce this combination. DefaultSNAPSHOT and older IDs preserved; no commit/push/hosted signed distribution/productionDB/deployment/consumer implementation.

| Artifact | SHA-256 |
| --- | --- |
| Server bootJAR | f845e54b7dcde555894a66d9a52f458c48baeb5cb95e4db3c5a7461461f3ae65 |
| Reference BFF bootJAR | 34af02ba84a1026c20171a35349bad49568ccd6b3985d57d5a3b92c6c2c8e78e |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

Private final scan anchors: three-lockSCA `8fd8f646a50f46bf79b4c4e60f64922b37d2c6e2278b4eff8967fb141778f5aa`, extractedJARSCA `29fecc631b5673cdbcd09c136593cf69678e85d54f187a746bbe402630831b40`, Semgrep `28ea56859a790380427c3b5f3dcd1a03967405c5d9f61734bd939c8c0bfe1c91`; each emptyGitleaksJSON `37517e5f3dc66819f61f5a7bb8ace1921282415f10551d2defa5c3eb0985b570`.

Dedicated app/DB/proxy containers/volumes/network, detached processes and generated privatekey/cert/trust/settings/state files removed; four acceptance ports free, no globalCA/hosts modifications. Private temporary reports/inventories/cache/logs remain without long-term retention guarantee.

## Remaining

Domain metrics/private export/dashboard/alert delivery, sustained representative production hardware load, production image/OS/JDK/proxy and fullruntime logs/DAST/UI/accessibility, officialdomainsCArenewal/CDNfirewall, productionbackup/keycustody and actual consumer15m/epoch/adoption remain. BroaderB1 crash gap/custom durableSession/index/replay/keyrotation and optional multi-instance work retain their separate scope; independent qualification events remain deferred. Production readiness and formal signed release are not declared.
