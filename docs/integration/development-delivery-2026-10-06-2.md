# Administrator maintenance and revoked-cookie OIDC delivery — 2026-10-06

Historical .20261006.2 snapshot. [Delivery .20261006.3](development-delivery-2026-10-06-3.md) repairs deployment forwarding inputs and adds the explicit reference HTTPS profile/real TLS acceptance; this manifest no longer matches current source/configuration scope.

- ID **0.1.0-dev.20261006.2**, local identified development combination. Previous versions remain historical; .20261006.1 hashes do not match current source.
- [Chinese](../zh-CN/integration/development-delivery-2026-10-06-2.md), [manifest](development-delivery-2026-10-06-2.json), [maintenance acceptance](../operations/administrator-maintenance-acceptance.md).

## Discovered defect and repair

Actual administrator reset/browser acceptance found that a retained old SSO Cookie could interrupt a new OIDC authorization: old-session validation invalidated authentication and redirected to login before Spring validated/saved the initiating request. After correct password entry the browser reached SSO account center, not the BFF. The reset itself had successfully revoked credentials/sessions/Token state. Two initial PostgreSQL/MockMvc regression cases (ordinary and promptlogin) failed at the missing fresh anonymous pending session.

`AccountSessionValidationFilter` now clears the old SecurityContext and invalidates the old Session, then continues anonymously **only for GET /oauth2/authorize matched through Spring PathPattern**. Normal Spring validates client/redirect/scopes/PKCE/prompt/max_age; the existing OIDC password filter creates a new browser-bound transaction and saves the validated current request. Old saved requests/password challenges/auth_time are not copied. No unvalidated redirect is automatically remembered and no authenticated identity is preserved. Non-OIDC API/browser expiry responses stay denied/redirected. Wrong old passwords, promptnone, malformedmax_age and unsafe redirects retain their rejection semantics.

This changes one server filter and extends its real OIDC integration suite. No dependency/locks/migrations/SPI/permission/authentication-factor changes, and no production UI/scripts were rewritten. This is a bug repair to existing login continuation, not a new relying-party password-evidence gate. Starter bytehash remains unchanged intentionally.

## Regression execution

Java21/Docker, from las-sso-server/:

```powershell
.\gradlew.bat :test --tests "org.las.sso.config.OidcPasswordReauthenticationIntegrationTest.revokedBrowserAuthorizationKeepsValidatedRequestThroughFreshPasswordLogin" --no-daemon --console=plain
.\gradlew.bat :test --tests "org.las.sso.config.OidcPasswordReauthenticationIntegrationTest" --tests "org.las.sso.security.AccountSessionValidationFilterTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.2" "-PlasSsoStarterVersion=0.1.0-dev.20261006.2" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.2" --no-daemon --console=plain
```

Two original new cases red before fix, both green after. The expanded related71 stage passed (reauth17+filter1+persistence53) before the last malformed-age case; final reauth suite18 includes **seven additions**:

- ordinary/forced login with revoked sid creates new anonymous transaction, then real password/Code/PKCE/signed subject/state/nonce/new sid/auth_time;
- ordinary/forced credential-version transition rejects old password and returns to the exact registered callback after new password;
- promptnone returns login_required/state/no code;
- unsafe redirect rejected and a retired browser's password challenge cannot be reused;
- malformedmax_age rejects before creating a fresh password transaction.

A compile attempt used String rather than HttpMethod for matcher configuration and was corrected. A wrong-password test network reached the configured fixture CAPTCHA threshold and returned login UI200; isolated network fixtures corrected accumulation without relaxing production limits. The first browser assertion incorrectly expected ordinary failed form login401; the actual existing contract is302/login?error, corrected before classifying the OIDC-continuation defect. These setup/compile issues are not additional product vulnerabilities.

Final forced build about **03:46–03:51 Asia/Shanghai**: **486server+193starter+37referenceBFF=716**, zero failures/errors/skips,20tasks executed, both bootJARs/metadata. All PostgreSQL tests ran. Existing shutdown-pool warnings refer to stopped Testcontainers fixtures, not hidden failed/skipped cases. The final manifest-hashed JARs were then used for the full acceptance again.

## Real administrator/console/browser acceptance

Actual bootstrap/reset PowerShell entry scripts and exactJAR/SHA256 under WindowsPTY/Java21, dedicated PostgreSQL18.4, independently generated persistent RSA3072 key and secret settings. Local client seeding happened only once before the browser flow; subsequent commands/web processes used non-local explicit key configuration. Default reference BFF, Edge headless/Playwright1.55.1, two BrowserContexts; no existing identity/production DB/key. Full detail in [operator record](../operations/administrator-maintenance-acceptance.md).

Verified bootstrap mismatch nonzero/no mutation, successful hidden temporary-password entry/version1/24h/oneadmin; both temporary browser sessions forced to change and cannot access management/account/recovery/OIDC. MissingCSRF/foreignOrigin403, confirmation mismatch before HTTP and wrongtemp401 keep version1. Real UI success204 yields ACTIVE2/no temporary expiry and old SSO sessions/temp password denied.

Fresh new-password administrator login renders management; recoveryUI generates ten four-group80-bit codes, real downloaded file equals visible batch then is deleted, reload does not redisplay plaintext and DB holds digests. Both BFF administrator logins use actual Code/PKCE/Token/UserInfo. Stop SSO Web; real resetcancel/mismatch/reuse/invalidshort returnnonzero/unchanged security snapshots; actual hidden-success reset yields ACTIVE3, oneaudit, revoked recovery material/session/Authorization/RTfamily and persisted Back-channel/logout events.

Restart same persistentkey SSO, wait for actual reset Back-channel delivery before querying BFF Session. Both BFF logins end; retainedSSOCookie/oldAT/oldRT/oldpassword denied. Fresh resetpassword succeeds even with stalecookie and returns to BFF, management renders, then real globallogout clears both via signed Back-channel. Secret inputs, console transcript, Token and recovery/download contents remain inside the disposable harness; no plaintext printed or retained. Four process logs passed targeted fixturepassword/keypassword/pepper/clientsecret and JWT/Bearer/recovery/cookie checks. Operator offline-code custody/copy/print and revoked-code full browser recovery were not tested.

Independent native.module/POM-only Gradle consumers recompiled public old/new starter APIs and resolved all Security7.1.1 from isolated local .2 publication. No MavenCLI/hosted release implied.

## Provenance

Base8cee3ed62fd7c53664e8026b99664c73fae5f19d / branchcodex/fix-sso-quality-review **plus uncommitted worktree**;284source/build/test/Gradle/scripts fingerprint `22581a0b9f1aa5b548ac93732d6042dd0a0933b5a8112961972e75ce7b5fd968`. Manifest10artifactscriptmigration and three unchanged locks, paths relative to las-sso-server, excludes docs/build/external configs from source fingerprint.

| Artifact | SHA-256 |
| --- | --- |
| Server bootJAR | e5a178a1cf728a7c59b13313cd5802495781ee5093158e098bc595dfb1de54ce |
| Reference BFF bootJAR | b0f07051f78672c7309e2ac57b231894af80528e7da93677ec2924d84e21535a |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

Versions defaultSNAPSHOT without explicit properties; .2 changed content does not overwrite .1. Source is uncommitted and these hashes are provenance, not signatures/reproducibility guarantees. No commit/push/production data/remote publication/deployment occurred. Disposable services/database/keys/credential config cleanup is recorded with this delivery.

Final cleanup stopped identity-checked SSO/BFF processes, removed the dedicated PostgreSQL container/volume, deleted external credential/pepper configuration and generated binary PKCS#12 fixture key. Downloaded recovery plaintext was deleted by the browser harness. No production resource was involved.

## Remaining

Empty-database maintenance/first-change/reset core flows are now isolated-real verified. Populated-user promotion/deletion cancellation,24h expiry in a live window, additional shells/platforms, operator offline custody, full recovery UI/accessibility and productionTLS/proxy/SecureCookie/environment remain separate. This filter repair does not implement consumer15m gates, generation caches or B1 pre-submit crash/persistent Session/replay/index/multiinstance. Key cached-oldkid transition, productionRPO/RTO/backup custody, queue dead/keyrotation, full scans/capacity/monitoring, external exactURIs and signed committed hosted release remain open. Historical restore/durablequeue evidence remains valid at its own recorded scope, not a new rerun of every earlier process drill.
