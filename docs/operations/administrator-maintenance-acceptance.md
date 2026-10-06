# Administrator maintenance and credential-transition acceptance

- Verified scope: isolated Windows PowerShell/Java21 hidden-console and Edge browser drill on2026-10-06.
- [Chinese](../zh-CN/operations/administrator-maintenance-acceptance.md), [delivery .20261006.2](../integration/development-delivery-2026-10-06-2.md), [artifact selection](maintenance-artifact-selection.md).

## Procedure and semantics

Use a maintenance window, quiesce the SSO web process and select the exact server JAR/hash with the existing scripts. Passwords stay in the interactive JVM console; they are not arguments, environment variables, script parameters or protocol values supplied to the business BFF.

```powershell
.\scripts\bootstrap-admin.ps1 -Username InitialAdmin -Profile production -JarPath $ServerJar -ExpectedSha256 $ServerJarSha256
.\scripts\reset-admin-password.ps1 -Profile production -JarPath $ServerJar -ExpectedSha256 $ServerJarSha256
```

Bootstrap on an empty identity database creates exactly one administrator with a24-hour temporary password and PASSWORD_CHANGE_REQUIRED. A populated identity database instead uses the existing immutable-user-ID promotion mode; effective deletion requests must be cancelled first under ADR-0002. This drill exercised empty-database creation, not live promotion/cancellation.

The temporary password permits only the forced-change/logout flow, not management, ordinary account pages, recovery-code operations or OIDC grants. The first change verifies the temporary password plus CSRF/Origin, transitions credentials to ACTIVE and ends all old SSO sessions/authorizations. Re-login with the new password is required. Generate recovery codes only after this new login, retain them in an operator-controlled offline store and verify they are not redisplayed on reload.

Offline reset displays the immutable administrator identity, requires YES confirmation and two hidden new-password inputs. Cancelling, mismatching, reusing or supplying an invalid password must return nonzero without changing credential/audit/security/outbox state. Success updates the current credential to ACTIVE (not another temporary credential), advances its version, revokes recovery material and SSO sessions/authorizations, records audit/security events and queues logout work. Restart SSO with the same configured signing key. The previous password/Cookie/Token cannot authenticate; the new password can establish a fresh login. Do not assume previously saved recovery codes remain usable: their revocation requires generating a new batch.

## Executed evidence

The final .20261006.2 JAR/hash was used by both actual PowerShell entry scripts under a Windows PTY, with only newly generated fixture passwords. The database was a dedicated disposable PostgreSQL18.4, persistent RSA3072 key external to the database, fixed loopbackHTTP issuer and default reference BFF. Edge was headless, Playwright1.55.1 drove two independent BrowserContexts. No existing user or production database/key was used.

| Step | Verified outcome |
| --- | --- |
| Bootstrap | Mismatched hidden inputs failed with unchanged snapshot; successful two inputs produced one administrator, version1/temporary24h/required-change; plaintext was absent from console transcript |
| Forced login | Both browser sessions reached password-change-required; management/account/OIDC were redirected to that flow, recovery API403/PASSWORD_CHANGE_REQUIRED |
| First change negatives | MissingCSRF and foreignOrigin403; confirmation mismatch stayed client-side; wrong temporary password401; credential version unchanged |
| First change success | Actual UI POST204, visible success panel, ACTIVE/version2/no temporary expiry; other old SSO session denied, temporary password redirected to/login?error |
| New login and codes | Management page rendered with the new password; recovery UI produced ten four-group80-bit codes, downloaded file content matched the visible batch; file deleted and reload showed no plaintext; only digests persisted |
| OIDC | Two same-subject administrator BFF logins were independent and actual Code/PKCE/Token/UserInfo worked |
| Offline reset negatives | Real confirmation cancellation/password mismatch/reuse/short password all returned nonzero and kept security-state snapshot unchanged |
| Reset success | Hidden new-password inputs completed, one reset audit event, ACTIVE/version3; old recovery rows revoked, all SSO sessions and authorizations revoked/deleted, old family state ended |
| Delivery and old credentials | SSO restarted using the same persistent key; actual Back-channel events ended both BFF sessions; oldCookie denied, oldATUserInfo401, oldRT400invalid_grant, old password/login?error |
| Fresh reset login | New password completed BFF callbacks even with retained staleSSOCookie; management rendered; global logout ended both BFF sessions through real signed Back-channel |

Download contents/passwords/Tokens were checked only inside the disposable harness and not printed; the downloaded recovery file was deleted. This does not verify an operator's real offline storage/copy/print retention. Reset recovery-material revocation was checked in PostgreSQL; using a revoked code in a full recovery browser flow was not part of this drill. Client rollout, successful populated-database promotion, expiry of the24-hour temporary password, non-Windows terminals, productionTLS/proxy/SecureCookie and complete accessibility remain separate.

## Revoked-cookie OIDC continuation repair

The drill exposed a real usability/protocol-completion defect after reset: one browser still held an old SSO Cookie. `AccountSessionValidationFilter` invalidated the old authentication and immediately redirected to login before Spring could validate/save the initiating Authorization request. The password was accepted but the default success route went to account center instead of returning to the BFF.

The filter now ends the stale session/security context and, **only for a canonicalGET authorization request**, continues anonymously into the normal Spring authorization validation. The existing OIDC password filter creates a new browser-bound transaction after client/redirect/scope/PKCE/prompt validation. It does not copy old saved requests, old password challenges or old auth_time, and does not preserve an arbitrary user-supplied redirect. Ordinary API/browser expiry behavior still denies/redirects as before; successful password re-entry and new sid are required.

Two regression cases first failed on old behavior, then passed; seven new PostgreSQL/MockMvc cases cover ordinary/forced login with revoked sid or changed credential version, old-password refusal, newstate/nonce/sid/auth_time, promptnone login_required, invalidredirect/oldchallenge refusal and malformedmax_age. These exercise real login, Code/S256 and signed ID Token, not a consumer's recent-password gate. The full existing foundation/reauth/Servlet suites also passed. A test network budget initially accumulated two wrong-password attempts and triggered its configured CAPTCHA; isolated fixture networks corrected that test without weakening production limits.
