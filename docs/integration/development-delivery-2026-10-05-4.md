# Durable reference revocation delivery — 2026-10-05

Historical .20261005.4 snapshot. [Delivery .20261006.1](development-delivery-2026-10-06-1.md) adds the executable SSO restore barrier and isolated real backup/key-replacement drill; this manifest is not current-source provenance.

- ID **0.1.0-dev.20261005.4**, local development combination; .1–.3 remain historical snapshots.
- [Chinese](../zh-CN/integration/development-delivery-2026-10-05-4.md), [manifest](development-delivery-2026-10-05-4.json), [ADR-0009](../adr/0009-reference-bff-durable-token-revocation.md), [profile contract](reference-durable-revocation.md).

## Scope

User selected optional reference-BFF PostgreSQL durable revocation verification. Profile durable-revocation supplies the existing sink, encrypts Token snapshots with independently configured AES-256-GCM key/AAD, commits acceptance before return and resumes leased/retry tasks after restart. No Client Secret in DB; delivery validates issuer/client/endpoint snapshots against current confidential registration and uses its current secret. Exact200/no redirects, two-minute fenced leases, persisted eight-claim budget/random retries/dead letters and success ciphertext erasure. Local logout remains before submit; pre-commit crash gap remains. This is reference sample code, not a reusable starter JDBC artifact or LASLOG schema.

Reference V1 queue migration is separate from SSO FlywayV1–V14. SpringJDBC7.0.8/Flyway12.4.0/PostgreSQLdriver42.7.11 plus Testcontainers2.0.5 test deps are added to reference BFF only and its lock regenerated. Starter/server source/dependency locks retain previous behavior; starter byte hash unchanged from .3 is expected. Default profile still requires no DB/key and uses memory; only explicit profile migrates. Reference application test is now real37 cases, not NO-SOURCE.

## Commands and execution

From las-sso-server/, Java21/Docker:

```powershell
.\gradlew.bat :las-sso-reference-bff:dependencies --write-locks --no-daemon --console=plain
.\gradlew.bat :las-sso-reference-bff:test --tests "org.las.sso.reference.revocation.JdbcRevocationIntegrationTest" --no-daemon --console=plain
.\gradlew.bat :las-sso-reference-bff:test --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261005.4" "-PlasSsoStarterVersion=0.1.0-dev.20261005.4" --rerun-tasks --no-daemon --console=plain
```

Final forced build about **22:03–22:11 Asia/Shanghai**: **461server+193starter+37referenceBFF=691**, zero failures/errors/skips,20tasks executed. PostgreSQL18.4/Testcontainers actually ran. Server shutdown pools again logged access to already-stopped fixture containers; no report failure/error/skip. Tests were added for a new feature, not a fabricated old-code security red.

Reference34 DB/AES/HTTP cases cover committed acceptance/independent outer rollback, realSQL insertion/claim/retry/receipt faults, exact200/status14matrix/no redirects, restart via new stores and actual JVM, expired lease/stale-owner fencing/concurrent claimers, eighth crash budget, HTTP200/receipt rollback/idempotent repeat, transport disconnect, metadata/task ciphertext substitution/key failures, endpoint/client/issuer changes/current-secret rotation, guarded endpoints/token input and real logout-handler failure cleanup. Three configuration cases verify default noDB, explicit profile's only sink/schema and missing configfail. Initial context tests lacked configured sample registration id/JWKS and failed startup; fixture corrected, not a production repair.

Actual local .4 SSO/BFF bootJARs, separate SSO and queue databases in disposable PostgreSQL, ephemeral local SSO signing key and independently generated queue key; headless Edge/Playwright1.55.1. Both default and application-owned chain modes:

1. Register/solve ALTCHA and independently password-login two browser contexts of one subject.
2. Pause only delivery worker, current-app logout A; A anonymous/B remains logged in; encrypted task committed attempt0 while original SSO authorization remains active.
3. Stop BFF process; new JVM with same queueDB/key starts worker, task completes attempt1 and nonce/ciphertext erased.
4. Original SSO access/refresh metadata invalidated; actual old RT refresh returns400 invalid_grant; other browser's authorization remains uninvalidated.
5. BFF restart loses local login as expected; still-active SSO browser can top-level reenter without password, then global logout returns safely.

Initial harness expected SSO revocation to delete authorization/security rows. Actual RFC7009 implementation retains invalidated metadata, so assertion was calibrated and strengthened with realRT invalid_grant. Not an SSO revocation defect. Final forced-build JARs restarted and both flows passed again. Public browser responses/log output did not expose Token/key/secret; targeted log check is separate from full scanning. Startup/acceptance scripts are external disposable helpers, not project defaults.

Starter .4 published only to isolated local file repository; independent native.module/POM-only consumers compile old/new public APIs and resolve all Security7.1.1 without composite/inherited locks. No hosted distribution or actual Maven CLI implied.

Ten acceptance process logs passed targeted fixture-password/encryption-key/client-secret canary and Bearer/JWT/recovery/cookie value-pattern checks. Final SSO/BFF processes and disposable database container/volume were stopped/removed, external temporary credential/key files deleted. This does not constitute a full source/history/production secret scan.

## Provenance

Base8cee3ed62fd7c53664e8026b99664c73fae5f19d/branchcodex/fix-sso-quality-review **plus uncommitted worktree**,278source/build/test/Gradle/scripts files. Fingerprint `49fab61275a19c707429107710e109bd6c3f52579409f78773c815449b32e348`. Manifest9artifact/script/reference-migration hashes and three locks are relative to las-sso-server; docs/build/external configs excluded from source fingerprint.

| Artifact | SHA-256 |
| --- | --- |
| Server bootJAR | 2fac17b9ed79f62f82313e7a852f6f16b3c2d6ce32eda3605da9b47c20033912 |
| Reference BFF bootJAR | ec4bd1547922a245e90c7fa2822fdfb0a19aec3ee9e0e4fcd00c6a9ea42c888d |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

Default versions remainSNAPSHOT; new content uses .4, .3ID/content preserved. Hashes are equality/provenance not release signatures/committed reproducibility. No commit/push/productionDB/remote publication/deployment.

## Remaining

Reference committed-task restart/retry is verified, not full durable B1 closure: pre-submit crash gap, persistent local Token/Session/index/replay/sid marker, custom/shared-store B2 coordination remain. No operator dead replay/cleanup/multikey rotation, productionTLS/key backup/restore/pooling/capacity/complete secret scans, arbitrary multiinstance app support or consumer adoption proved. Actual SSO backup/epoch barriers, successful interactive administrator drills/external exactURIs/consumer fifteen-minute gates/hosted signed release remain independent. The next durable work must explicitly choose the cross-store acceptance/recovery boundary, not declare existing public submit atomic with local Session removal.
