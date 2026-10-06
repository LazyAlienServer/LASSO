# Recovery credential entropy and persisted-code compatibility

- Status: S6 repaired and JVM/PostgreSQL/MockMvc verified on 2026-10-04; live browser/operations acceptance pending.
- Chinese counterpart: [恢复凭据熵与已签发旧码兼容](../zh-CN/implementation/recovery-credential-entropy.md).
- Tracking: [status map §3.12](status-issues-and-plan-2026-10-02.md).

## 1. New generation

The existing alphabet `23456789ABCDEFGHJKLMNPQRSTUVWXYZ` has 32 symbols. `SecureRandom.nextInt(32)` independently selects every random symbol. Generation previously produced three groups of four symbols: `12 × log2(32) = 60` bits. Both generation methods now produce four groups of four, meeting the existing minimum with **16 × 5 = 80 bits per newly generated credential**. Fixed prefixes and separators contribute no entropy.

| Credential | New display shape | Length including prefix/separators |
| --- | --- | --- |
| Ordinary recovery code | `LAS-XXXX-XXXX-XXXX-XXXX` | 23 |
| Administrator-issued credential | `LAS-ADMIN-XXXX-XXXX-XXXX-XXXX` | 29 |

Ordinary batches remain ten codes. Registration, explicit batch rotation and administrator issuance use the same owning services/generator. HMAC-SHA-256/pepper, 32-byte stored digest, defensive array copies and normalization (remove hyphens, uppercase with Locale.ROOT) remain unchanged. The recovery form placeholder shows four groups; its maxlength and API credential limit remain 64. Client type detection still uses the normalized prefix, not a new exact-length pattern. No schema, dependency, authentication factor, permission, endpoint or public method change is needed.

The entropy conclusion combines inspection of the 32-symbol uniform CSPRNG selection with deterministic output/format tests. It is not a statistical certification of the platform entropy source or a claim that distinct outputs alone establish cryptographic strength.

## 2. Existing stored codes and replacement

No deployment-time credential rewrite, forced mass revocation or pepper change occurs. A previously issued three-group code remains **60-bit** and is accepted only if its existing digest matches the same current username/identity/type and it is unused/unrevoked. Administrator-issued codes retain their original expiry; upgrade does not renew it. Compatibility does not invent an old code's plaintext or strengthen its original entropy.

- Recovery start atomically consumes either format and creates the existing ten-minute restricted session; reusing the code or completed/expired session fails.
- Ordinary recovery completion consumes the submitted code but retains other unused ordinary codes from that batch, including legacy codes, under the existing policy.
- Explicit ordinary rotation revokes the old batch and creates ten four-group codes in one transaction. A response loss still requires a fresh rotation; old plaintext cannot be recovered.
- Administrator reissue revokes previous unused credentials and creates a new four-group, thirty-minute credential; successful recovery, password change and existing maintenance invalidation remain in force.
- Old used, revoked or expired credentials do not regain validity. Wrong user/type, CAPTCHA and user/network/global attempt gates remain on the same paths.

Audit actions/results stay unchanged, using credential IDs, request IDs and result categories rather than plaintext. No format/version migration column is required because verification hashes the presented string with the unchanged normalization. A mixed deployment with an old generator can still issue 60-bit codes; an operational rollout must update every issuing instance before claiming all new issuance is 80-bit. Mixed live deployment was not tested.

## 3. Evidence

Two entropy regressions were run before repair using `RecoveryCodeServiceTest`: both failed because their random-symbol count implied 60 rather than at least 80 bits; the existing one-case generator/HMAC test passed. Final JVM suite has **nine cases (eight added)** for both entropy gates, both four-group formats, three independently computed fixed HMAC vectors across old/new forms, normalization, prefix distinction and array-copy protection. The existing ten-code assertion now expects four groups.

`RecoveryCredentialCompatibilityIntegrationTest` adds **33** actual PostgreSQL 18.4/Spring/MockMvc cases. Legacy plaintext is a fixed test value; its digest is independently computed with JCA HMAC using the test profile pepper, then replaces an owning-service-created row's digest in the disposable database. It is never produced by the new generator. Current codes are issued through registration/admin owning services. Administrator issuer identity in service-level fixtures is a registered actor, not acceptance of its browser role/reauthentication; the existing admin suite separately exercises the protected issuance API with its explicit role/session fixtures.

Coverage includes:

- old/new ordinary/admin full recovery, code replay before/after completion and session replay; existing ALREADY_USED/success request-linked admin audits;
- old normalization, revoked/expired rejection and unchanged credential row; expiration rejection audit omits the fixed test plaintext;
- ordinary rotation and admin reissue/new-format digest/lifetime behavior; explicit outer-transaction rollback restores old validity and removes new writes/success audit;
- ordinary completion preserving other unused legacy batch codes;
- password change revoking unused legacy codes and consuming already-created sessions; ten-minute session expiry does not permit a new password;
- wrong identity/type rejected, unchanged usage; user CAPTCHA/attempt budget still applies even to a correct legacy code;
- four old/new HTTP flows through the existing recovery controller, 204/no-store, token only in Secure/HttpOnly/Strict ten-minute cookie, password completion;
- concurrent old-code consumption allowing one success/one invalid result and one session; same-user PostgreSQL locking remains the implementation, a barrier/Future fixture coordinates simultaneous calls;
- actual recovery-page Thymeleaf rendering of the new placeholder and unchanged permissive length.

The first compatibility expansion had two fixture failures because the helper asked JdbcClient for a null scalar `used_at`; wrapping the nullable value in a row record fixed the helper. This is not a production regression. Final recovery-generation/admin subset passed, then the expanded **164 related cases** passed. Earlier XML is overwritten by subsequent Gradle reports; phase evidence is in command output/status §3.12.

Commands in `las-sso-server/`, Java 21/Docker:

```powershell
.\gradlew.bat :test --tests "org.las.sso.account.RecoveryCodeServiceTest" --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.account.RecoveryCodeServiceTest" --tests "org.las.sso.account.RecoveryCredentialCompatibilityIntegrationTest" --tests "org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest" --tests "org.las.sso.admin.AdminRecoveryCredentialServiceTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

Final focused run at about 21:25–21:26 Asia/Shanghai: **164 = 9 + 33 + 28 + 6 + 53 + 25 + 10**, zero failures/errors/skips. Full forced run at about 21:27–21:30: **314 server / 176 starter**, zero failures/errors/skips; 12 actionable tasks executed, reference BFF recompiled and `test NO-SOURCE`. Final JVM nine-case XML: `2026-10-04T13:27:56.325Z`; compatibility 33-case XML: `2026-10-04T13:27:56.343Z`. S6 adds 41 cases over S5's 273 server tests.

## 4. Limits and remaining delivery

MockMvc/controller/template, real PostgreSQL and JCA verification are not live browser/TCP/TLS/proxy or frontend JavaScript usability/accessibility/printing/downloading/clipboard acceptance. Outer-transaction failures are explicit rollback fixtures, not crash/kill or a production migration drill. The targeted rejection-audit plaintext check is not a full application/proxy/telemetry secret scan. Existing S1 concurrency/maintenance and registration/rotation/one-time API cases are rerun at their documented layer, not broadened to a live multi-instance guarantee.

Dependency D1's named patch was subsequently upgraded/verified, with that stage's 323/176 report in the [Security record](spring-security-consent-patch.md)/§3.13. A1/A2 subsequently gained direct Servlet evidence and latest 419/176 in the [HTTP record](servlet-path-security.md)/§3.14. The 314/176 counts above preserve S6. Full scanning, production proxy/TLS/browser, S2 wider concurrency and full B1 consumer/version delivery remain separate. No commit/release/deployment/user DB operation occurred in S6. The entropy-budget assertion now guards both generator entries.
