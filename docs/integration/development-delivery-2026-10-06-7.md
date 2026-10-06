# Refresh/Logout Continuity And Grafana Mixed-Load Development Delivery

- ID **0.1.0-dev.20261006.7**, verified2026-10-07. [Chinese](../zh-CN/integration/development-delivery-2026-10-06-7.md), [manifest](development-delivery-2026-10-06-7.json), [hint repair](../implementation/refresh-logout-hint-history.md), [ADR-0011](../adr/0011-preserve-id-token-logout-hint-association.md), [Grafana/mixed-load](../operations/grafana-mixed-load-2026-10-07.md).

## Changes

Actual .6 short-token longer workload exposed global logout400 after successful refresh: BFF initial ID Token was no longer the server's current token. New PostgreSQL/MockMvc regression failed before repair. V15 HMAC-only ID Token→authorization history and atomic capture-before-replace fix logout hint lookup without broadening AT/RT/code lookup or changing the existing client/principal/exact-return validator. Association disappears with the authorization via cascade; pre-upgrade current token captured on first subsequent save, already lost earlier hints cannot be recovered. No plaintext historicalToken/passwords/newcredentials/permissions or BFFprincipal/auth_time rewriting. Details and lifecycle/pepper/history-sizing limits are in the paired implementation record.

Dependency versions/three locks, monitoring inputs, dashboard and starter APIs unchanged from .6. SSO schema is now **V1–V15**. A new .7 identified combination preserves historical .6 JARs/manifests. Starter bytehash unchanged is intentional; reference BFF changes version identification only.

## Verification

Final Java21/Docker forced build **2026-10-07 00:07:51–00:12:17 Asia/Shanghai**: **505server+193starter+37reference=735**, zero failures/errors/skips,20tasks executed, actual PostgreSQL including V15/new regression. Regression asserts actualrefresh-issued replacement, originalhint successful logout, firstupgradecurrentcapture/two32bytehashes, unregisteredreturn400/authunchanged, noATRTlookup, cascade and repeathint400. Initial synthetic IDToken is fixture; the final real TLS browser workload proves the live behavior.

Actual Grafana12.2.0 imported the exact unchanged repository dashboard/private Prometheus datasource. Viewer1440×1000/390×844 sees eight panels with real data/canvas variations, no query/page errors/horizontal overflow; save/overwrite/datasourcewrite403. Screenshot and permissions evidence recorded. Early mobile missing-panel timeout was lazy mounting corrected by actual scroll, not a dashboard code change.

Final .7 default/application-owned BFF chains ran **301.154/302.178seconds**, each five subjects/nine independent browserSessions, NginxTLS/JVMtrustedCA/CodePKCE/JWKS/UserInfo/actualrefresh/currentglobalBack-channel under closed-loop800mspacing. Total12,598successful operation observations:48passwordOIDClogin/24currentexit/24SSOreentry/16globalexit.120observed refresh successes/0reuse,40processedBack-channel/0finalpendingdead/budgetrows, unrelatedsentinel retained. Expected401SESSION_EXPIRED accepted only during post-revocation checks; normaltraffic200. ShortfixtureAT45s/refreshbefore10s is explicit, not default15m acceptance. See detailed latency/memory samples,oneunattributed samplinggap and nonproduction/soak limits in the operations record. Prior .6alert firing and .5administrator/restore evidence remains historical, not re-executed on .7.

Final scans:241lock/157JARpackage rows0reportedvuln, Semgrep233paths1retainedredirectfalsepositive0errors using sameverifiedrules, Gitleaks source/JAR/allhistory0matches. SameDB/rules/advisory/coverage limits as prior passes; opaqueJAR0package not effective coverage. Finalcontainer/processlogs targetedsecretcheck no matches; no universal no-secret claim. Native.module/POM-only isolated .7consumers compile unchangedold/newAPIs and resolve fixedbaseline; localfilepublication only.

## Build And Provenance

From`las-sso-server/`, Java21/Docker:

```powershell
.\gradlew.bat :test --tests "org.las.sso.persistence.AuthorizationPersistenceTest.originalIdTokenHintStillLogsOutAfterRealRefreshReplacesTheStoredIdToken" --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.7" "-PlasSsoStarterVersion=0.1.0-dev.20261006.7" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.7" --no-daemon --console=plain
```

Base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **plus uncommitted worktree**. Source303fingerprint `ee911dc45866501f70ce9773fdc19228bf1fafd55b37f3ef82a5117c300fe0d2`, same scope/algorithm plus V15; scan304 additionallyCompose. Manifest18artifact/script/config hashes plus3unchangedlocks; migration source is included in fingerprint. HEAD alone cannot reproduce current content. DefaultSNAPSHOT/older identified artifacts retained.

| Artifact | SHA-256 |
| --- | --- |
| SSO bootJAR | ef4d296328005a66615efb0618ec1146128289dc31f5bcd968cb2b3b48548b43 |
| Reference BFF bootJAR | a8ddc1e12feff9370fdc3b810b8b3feb714dd06b51e6788d52ab9ef21cd0c8c2 |
| Starter plainJAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

FinalSCAhashes: three-lock`a7320a67e13783331300a69b47a64b9dacf18319a7301a9a1c7aed7d91360afa`, extractedJAR`ff0794947c6eadbbb82d15aa0fc405c5f9dacae1ab0ffbd67b84e63d053d9217`, Semgrep`6e598a7f69a0e1e851983932fcc5fd1a2771b84b1662bd1115e0752fe6dc3ab5`. EmptyGitleaksJSONsamehash as earlier. Runtime summary`ef41bee420b87e2fa7d1eb405b2ef0b614bf99b67dfa01959e2625427595f37a`; private rawreports/cache/screenshots/logs temporarily retained without long-term provenance retention guarantee.

DedicatedGrafana/Prometheus/TLSapp/proxy/DBcontainers/volumes/network and generatedaccount/admin/collector/cert/key/settingsfiles removed; acceptanceportsfree/no globalCAhosts change. No commit/push/productionDB/deployment/signedhostedrelease or businessconsumer implementation.

## Remaining

Hours-scale ramp/soak/realproductionhardware/network/largeDB/monitoringoverhead and IDhint-history lifecycle sizing, real Grafana productionauth/retention/backup/update, privateTLScredentialrotation/oncallfailureescalation, remainingdomain/externalprobes/UI/productiondomainCDNfirewall, backupkeycustody and actualconsumer15m/epoch remain. BroaderB1/customdurableSession/index/replay/multiinstance and formalcommittedsignedrelease retain independent scope. This closes actualGrafana import/display/Viewer boundaries and controlledmixedload while repairing the discoveredrefresh→logout defect, not production readiness.
