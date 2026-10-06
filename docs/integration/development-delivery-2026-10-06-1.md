# Executable restore barrier delivery — 2026-10-06

Historical .20261006.1 snapshot. [Delivery .20261006.2](development-delivery-2026-10-06-2.md) fixes revoked-browser OIDC continuation and completes isolated administrator maintenance acceptance. This manifest no longer matches current-source provenance.

- ID: **0.1.0-dev.20261006.1**, local identified development combination, superseding the current-source provenance of .20261005.4; previous delivery IDs/content remain historical.
- [Chinese](../zh-CN/integration/development-delivery-2026-10-06-1.md), [manifest](development-delivery-2026-10-06-1.json), [ADR-0010](../adr/0010-executable-restore-epoch-barrier.md), [operator procedure](../operations/restore-epoch-barrier.md).

## Change scope

The SSO server now supplies `SecurityEpochRecoveryService`, non-web command `rotate-security-epoch-after-restore`, and the matching PowerShell entry script. The caller must supply the expected restored epoch and a bounded non-secret reason, explicitly disable scheduling and confirm through an interactive console. The script selects the exact JAR and optionally verifies its trusted SHA-256. There is no recovery HTTP endpoint or automatic restore on startup.

The transactional service locks the global epoch row and rejects a missing/blank/mismatched epoch before changing state. It rotates to a random opaque epoch, revokes active SSO session metadata, deletes Spring Session/Authorization, revokes remaining refresh-token history, clears OIDC/password recovery/CAPTCHA/rate/idempotency transactions, and records a security event. Existing users/credentials/recovery credentials/client registrations/consent/deletion facts/audit and outbox payloads/receipts remain. Pending non-dead outbox claims are released, not deleted; schedules, attempts and dead-letter state remain unchanged. The old expected epoch cannot be reused after success, including concurrent operator calls.

Signing-key replacement, all other database writers being stopped, proxy drain and consumer session/cache reconciliation remain operator-owned prerequisites. The service neither proves writers are stopped nor atomically controls consumer databases/keys. No schema migration, dependency or lock change was introduced. Starter/reference-BFF source behavior is unchanged; the unchanged starter byte hash is intentional. The offline command is not ordinary global logout and cannot reconstruct post-backup password/account/credential changes.

## Tests and commands

From las-sso-server/, Java21/Docker:

```powershell
.\gradlew.bat :test --tests "org.las.sso.maintenance.SecurityEpochRecoveryServiceIntegrationTest" --tests "org.las.sso.maintenance.SecurityEpochRecoveryCommandTest" --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.1" "-PlasSsoStarterVersion=0.1.0-dev.20261006.1" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.1" --no-daemon --console=plain
```

Final forced build about **02:08–02:14 Asia/Shanghai**, **479server +193starter +37referenceBFF =709**, zero failures/errors/skips,20 tasks executed, both boot JARs and publication metadata generated. Database tests actually ran. As in previous runs, shutdown pools probed already-stopped Testcontainers fixtures; no failing/skipped report is disguised by those logs. Intermediate707 reflected the earlier16-test recovery stage; two outbox cases raised the final increment to18.

New recovery suites total18 cases:

- **12 actual PostgreSQL transaction cases**: seeded JDBC sessions/attributes, active authorization/security/refresh history including orphaned family history, unfinished/completed challenge constraints, short state and permanent facts; success/preservation; six SQL-trigger faults with full state rollback (sessions, Authorization, challenges, operation idempotency, outbox claim release, security audit); invalid/missing/mismatched/repeated epoch/reason and concurrent expected-epoch operators; claimed pending outbox versus completed/dead preservation.
- **6 command cases**: non-web/disabled-scheduler guards, no console, cancellation, malformed/duplicate options and successful confirmed delegation. Console is a test fixture at this layer.

Initial implementation failures were corrected before delivery: Spring constructor selection and final transactional service prevented context startup; a missing user fixture violated the Session foreign key; the early password-challenge approach violated incomplete-challenge semantics. The final service clears challenges rather than inventing their completion. Consent/outbox preservation was checked against the existing restore contract before acceptance. These are development/test failures of the new flow, not a claim that a previously released restore command had been exploited.

## Real isolated restoration

The external disposable harness used PostgreSQL18.4, source/empty target databases, actual `pg_dump -Fc`/`pg_restore --exit-on-error`, two RSA3072 PKCS#12 keys with distinct kid, Java21 bootJARs, system Edge/Playwright1.55.1, and a real Windows pseudoterminal (`pywinpty3.0.5`). Only fixture secrets were used. Source initialization used local client setup once; browser flows and recovery subsequently ran with explicit persistent signing keys, not local ephemeral signing.

1. The real JVM bootstrap command accepted hidden password input twice, created one fixture administrator and did not echo its password. This verifies console bootstrap only, not administrator first-change/reset UI acceptance.
2. Register an ordinary account through actual ALTCHA and log in two independent browser contexts through the reference BFF. Read real qualification `(epoch,version1)`, issue AT/RT/ID Token and keep unconsumed Code/PKCE and BFF callback transactions privately in memory.
3. Seed an explicit stale-claimed outbox event and near-due deletion scheduler fixture. Quiesce SSO, dump source and reload the same persistent pre-restore key; after backup, fixtureSQL freezes the ordinary account/version2. This produces real old-snapshot restoration, not an accepted browser-admin mutation test.
4. Stop SSO/BFF, restore into a freshly created target. Run the **actual PowerShell recovery entrypoint/JAR/hash** in a PTY, confirm interactively and verify the transaction changed epoch/cleared old login state while permanent snapshot facts stayed identical. Install the independent persistent post-restore key/kid and start target SSO/BFF with scheduling still disabled.
5. Assert version1/ALLOWED belongs to a different epoch than pre-restore version2. A test-only consumer generation barrier rejects stored late old-epoch replies and prior request generations. It is not LASLOG runtime/cache code.
6. Old SSO/BFF cookies cannot authenticate, old AT UserInfo returns401, old RT and old Code/valid verifier return400 invalid_grant, and the old BFF callback cannot consume its lost pending transaction. Old ID Token signature verifies with the pre-restore public key but fails with the new public key; the new JWKS has only the new kid. This does not assert existing production verifiers automatically evict a cached old key.
7. Restart target with scheduling enabled using the same post-restore key/epoch. The preserved outbox task completes; the near-due deletion fixture executes. The ordinary account retains its UUID/password, freshly logs in from both browser contexts, then global logout clears both through real signed Back-channel delivery.

The final manifest-hashed bootJARs repeated the full drill after the last build. A first restart attempt reused a closed Node HTTP pool socket; a single transport-only retry/Connection-close corrected the harness, not the SSO protocol. No token/code/cookie/key/password value was printed. After stopping web processes, actual noninteractive/cancelled/repeated-old-epoch commands returned nonzero, left security-state snapshots unchanged and supplied no bypass around console confirmation. Twelve process logs passed targeted fixture password/key-password/pepper/client-secret and Token/recovery/cookie checks; this is not a complete secret scan.

Independent local native.module/POM-only consumers compile old/new public starter APIs and resolve all Security7.1.1. Only explicit local file publication occurred; no Maven CLI, hosted release or deployment is implied.

## Provenance

Base8cee3ed62fd7c53664e8026b99664c73fae5f19d on codex/fix-sso-quality-review **plus uncommitted worktree**.284 source/build/test/Gradle/script files; fingerprint `29dcfbc13ef97b6128c7590993fcb80d209fc3d85cc5a90658beb4886efc7c57`. Ten artifact/script/migration hashes and three unchanged lock hashes in the manifest, paths relative to las-sso-server; docs/build/external configuration excluded from source fingerprint.

| Artifact | SHA-256 |
| --- | --- |
| Server bootJAR | 277faab68d6d5df0fb45a337202ec19dde132302d04306d805da6b11237dee9f |
| Reference BFF bootJAR | 77e5f1f210d83bd1506e99a6dcec5d598075e00d12f8ed1862a2a5039fe50941 |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

Default versions remainSNAPSHOT. Hashes identify uncommitted local content, not release signatures or committed reproducibility. No production backup/data, commit/push, hosted release or deployment was used. Disposable processes/database/backup/key/config cleanup is recorded with this delivery.

Final cleanup stopped identity-checked SSO/BFF processes, removed the dedicated PostgreSQL container/volume (including its temporary dump), deleted external credential/pepper configuration and the two generated binary PKCS#12 fixture stores. No production key or database was involved.

## Still open

Production backup storage/RPO/RTO/PITR/timing/key custody, exact issuer/TLS/proxy drain, actual LASLOG/other BFF cache and session barriers, verifying rejected old kids with previously cached production JWKS, full scans/capacity/monitoring, administrator first-password-change/reset drill and full UI acceptance remain separate. B1 pre-submit crash gap/persistent Session-index-replay/custom multiinstance and referencequeue dead-letter/key rotation remain open. Independent eligibility events stay deferred. This closes the SSO-side executable barrier and isolated real backup/restore/key-replacement drill at the documented layer, not all disaster recovery or consumer implementation.
