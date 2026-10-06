# Sid registration ordering and maintenance delivery — 2026-10-05

Historical .3 snapshot. [Delivery .4](development-delivery-2026-10-05-4.md) adds optional reference-BFF durable revocation and its37 tests; this manifest no longer matches current source/locks.

- ID: **0.1.0-dev.20261005.3**, identified local development tuple; .1/.2 contents remain historical.
- [Chinese](../zh-CN/integration/development-delivery-2026-10-05-3.md), [manifest](development-delivery-2026-10-05-3.json), [ADR-0008](../adr/0008-retain-sid-revocations-before-local-registration.md).

## Changes and compatibility

User explicitly selected default sid revocation markers retained for configured local sessionAbsoluteLifetime (default14days). `LasSsoBackChannelSessionRegistry` coordinates marker/check and index publication/snapshot under a short state lock. Session/container operations stay outside it. Valid Logout records even no-match or failed invalidation; a later registration throws and existing cleanup emits503 SESSION_REGISTRATION_FAILED, removes owning Token, ends local Session and submits revocation rather than calling application success. Different sids of one subject are independent; unregister never erases the marker. Repeated invalidation extends it, exact expiry admits again. Expired marker scans are traffic-triggered at most once/minute. Existing no-arg constructor/SPI methods remain; optional Duration constructor is new and auto-configuration passes current properties.

Markers belong to one configured issuer/client registry and are process-local, not a durable queue/shared store. Time retention is not a count quota; unique revoked sid volume determines memory. Custom adapters retain their replacement hooks and must supply equivalent register/invalidate ordering themselves. In-flight authorized business requests, process restart, cross-instance/storage and permanent protection after marker expiry are not provided. Sid non-reuse/local callback expiry and supported wall-clock behavior remain assumptions. The legacy direct subject invalidate remains current-snapshot only; wire Logout still requires verified sid.

Administrator scripts add exact `-JarPath` and optional `-ExpectedSha256`, preserve fixed SNAPSHOT default and never auto-select latest. Shared helper rejects missing/non-JAR/hash mismatch before launch, prints only artifact path/hash; entrypoints preserve hidden-console password input and Java exit. Transfer all three scripts together; they are external to boot JAR and are hashed in the manifest. [Maintenance usage](../operations/maintenance-artifact-selection.md) specifies commands/limits. No migration, dependencies, locks, authentication factors or consumer business tables changed.

## Executed evidence

```powershell
.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest.signedLogoutBeforeCallbackRejectsLateSessionWithoutCallingApplicationSuccess" --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoBackChannelSessionRegistryTest" --tests "org.las.sso.client.LasSsoBackChannelLogoutControllerTest" --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest" --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261005.3" "-PlasSsoStarterVersion=0.1.0-dev.20261005.3" --rerun-tasks --no-daemon --console=plain
```

- Two default/application-owned signed callback tests first failed: a valid earlier Logout204 was followed by successful late login. After repair both pass.
- New registry10 cases cover no-match/other sid, duplicate/unregister, failed invalidation retry, exact configured/default14day boundary, repeated extension, two deterministic concurrent orderings, outside-lock reentrant lifecycle/other sid progress, legacy subject behavior and invalid Duration.
- Callback35 (five additions) includes before-callback and during actual HTTP Code exchange Logout in both chains, cleanup/owning-token submission/no success delegate, fresh other sid login and invalid-signature Logout not poisoning valid registration. Signed RSA/JWKS/PKCE are real; inbound Servlet/listener/receiver dispatch and OP are fixtures.
- Intermediate focused59=registry10+receiver15+callback34 passed before final invalid-signature addition. Final full reports confirm callback35/registry10 and all existing receiver/replay suites.
- Forced full build about **17:33–17:39 Asia/Shanghai**, **461server+193starter=654**, zero failures/errors/skips,18 tasks executed; BFF compile/bootJAR, test NO-SOURCE. Database tests actually ran. Shutdown logs include pools probing already-stopped Testcontainers fixtures; report failures/errors/skips remain zero.
- External PowerShell9 launch-boundary checks pass with exact spaced path, username/UUID/reset, digest guards and exit propagation; Java is a launch fixture, not interactive administrator/DB acceptance.
- Both actual scripts also launched the final .3 JAR/hash against only the disposable PostgreSQL database with no console: nonzero INTERACTIVE_CONSOLE_REQUIRED and unchanged user/credential-version/audit/session/authorization/epoch snapshots. Successful hidden-password/bootstrap/reset remains unverified.
- Final .3 boot JARs passed actual local SSO/reference-BFF Edge/Playwright1.55.1 flows in default and application-security modes: ALTCHA registration, two independent same-subject browser logins, browser-bound password re-entry/signed auth_time, current logout isolation/password-free return, global logout and actual signed Back-channel clearing other browser. This reuses the [.2 harness scope](development-delivery-2026-10-05-2.md), not productionTLS/RP fifteen-minute gate or browser-injected ordering race.
- Local file-publication .3 was consumed by independent native.module and POM-only Gradle projects: public old/new API compiled, all Security7.1.1, no composite/inherited locks.
- Final four process logs passed targeted password/client-secret canary and Bearer/JWT/recovery/cookie value-pattern checks. Acceptance processes and disposable DB container are cleaned up; this is not a complete production secret scan.

## Provenance

Base8cee3ed62fd7c53664e8026b99664c73fae5f19d / branch codex/fix-sso-quality-review **plus uncommitted worktree**. Manifest267-file fingerprint `d489f9fbed5619fcb51c3f5b3e96a319cac9c78061ef52bcb056b204789c0515` now includes scripts (three maintenance files) and new registry test; it is not directly comparable to older263-file scope. Relative paths use las-sso-server; docs/build outputs/external configs excluded.

| Artifact | SHA-256 |
| --- | --- |
| Server bootJAR | 1543ab08c9fb055065615ee95b3cb353615c76cf0d7dd6a972890165d581d09d |
| Reference BFF bootJAR | 28b784d7ec3fae981f2eb9e7e7c1e6d775df13e245b3ceb215bfb23c94efcd0f |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

POM/module, three scripts and three locks are also hashed. Default build remains SNAPSHOT, versions overridden only by explicit properties. Scripts do not enforce an expected digest unless supplied. Hashes are equality/provenance, not release signatures. No commit/push/remote release/production DB/deployment occurred.

## Still open

Durable revocation task acceptance/replay/index, notification coordination for custom/multi-instance stores, crash/restart/old in-flight callbacks, productionTLS/proxy/SecureCookie, restore/epoch reconciliation barriers, sustained capacity/full scans, actual hidden-console bootstrap/reset, external exact client URIs, consumer pending/fifteen-minute gates and committed hosted release remain separate. This completes default running-JVM sid ordering and explicit maintenance artifact selection at their verified layers.
