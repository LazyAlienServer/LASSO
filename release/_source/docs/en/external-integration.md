# Business website integration

[Index](README.md) · [中文](../zh-CN/external-integration.md) · [SDK](../../sdk/README.md) · [API reference](api-reference.md)

**Scope:** Confidential server/BFF client. Browser holds only the business Session Cookie.

## Workflow

| Step | Pass when |
| --- | --- |
| 1. Registration | Independent site/environment, exact HTTPS URIs |
| 2. Code + PKCE | State/nonce/signature validation, stable UUID mapping |
| 3. Starter or maintained library | Local-session isolation, CSRF and Cookie settings |
| 4. Refresh/logout | Coalesced refresh, definite rejection ends login, execution receipt |
| 5. Eligibility/recent password | One-minute denial, 15-minute evidence bound to the originating session |
| 6. Consumer tests | Matrix below passes |

## 1. Client registration

Administrator at SSO `/admin/clients`:

| Field | Value |
| --- | --- |
| client_id/name/environment | Independent lifecycle |
| Type | LAS-controlled FIRST_PARTY, approved REVIEWED_PARTNER |
| Callback/post-logout/Back-channel | Exact reachable HTTPS URI |
| Scope/audience | `openid profile`, intended resource audience |
| Secret | One-time private server delivery |

Partner consent applies. First-party consent suppression does not weaken protocol checks. No browser Secret or implicit/password grant.

## 2. OIDC and mapping

| Check | Requirement |
| --- | --- |
| Discovery | Exact issuer, consistent hostname |
| Authorization | Code + PKCE S256, client_secret_basic |
| Transaction | One-use expiring state/nonce, local allowlisted returnTo |
| ID Token | Signature/algorithm/kid/iss/aud/azp/time/nonce |
| Subject | Unique `(issuer,sub)`, immutable UUID |
| Login name | Mutable/case-sensitive, not business key |

`getName()/getUsername()` return UUID; `loginUsername()` returns login name. No privileged role automatically assigned.

Token/Secret/code stay out of frontend storage/analytics/logs. ID Token is not API Bearer; validate AT audience/scope/issuer/signature/time at resource server.

## 3. Starter configuration

Use [pinned SDK](../../sdk/README.md), JDK21/Servlet Web/OIDC JSON converters and review final locks.

```properties
spring.security.oauth2.client.registration.las-sso.provider=las-sso
spring.security.oauth2.client.registration.las-sso.client-id=${LAS_SSO_CLIENT_ID}
spring.security.oauth2.client.registration.las-sso.client-secret=${LAS_SSO_CLIENT_SECRET}
spring.security.oauth2.client.registration.las-sso.client-authentication-method=client_secret_basic
spring.security.oauth2.client.registration.las-sso.authorization-grant-type=authorization_code
spring.security.oauth2.client.registration.las-sso.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}
spring.security.oauth2.client.registration.las-sso.scope=openid,profile
spring.security.oauth2.client.provider.las-sso.issuer-uri=${LAS_SSO_ISSUER}
las.sso.client.registration-id=las-sso
las.sso.client.refresh-before-expiry=PT60S
las.sso.client.post-logout-redirect-uri={baseUrl}/logged-out
las.sso.client.session-cookie-name=__Host-my_business
las.sso.client.session-absolute-lifetime=P14D
```

Actual servlet Cookie uses the same name, Secure/HttpOnly/Lax/Path=/, no Domain.

| Route | Method / purpose |
| --- | --- |
| Login/session/logged-out | GET `/api/auth/login`, `/api/auth/session`, `/logged-out`; identity, not business authorization |
| Local/global logout | POST `/api/auth/logout`, `/api/auth/logout/global`, CSRF protected |
| Back-channel | Signed POST `/internal/oidc/backchannel-logout`; only this protocol exemption |

### Application-owned SecurityFilterChain

1. Starter does not replace your chain.
2. Apply `LasSsoSecurityConfigurer.configure(http, existingSuccessHandler)` once.
3. Keep route/CSRF/business policy; do not overwrite wrapped success handler afterward.
4. See [both sample modes](../../examples/reference-bff/README.md).

## 4. Refresh and logout

| Scenario | Requirement |
| --- | --- |
| Expiry | Default AT 15 minutes, RT family absolute 14 days; rotation does not extend it |
| Concurrent refresh | Single-flight per local session |
| Definite rejection | End business login, no old RT retry |
| Transient failure | Distinct503, not ban/password evidence |
| Local logout | End local login first, RFC7009; keep main SSO |
| Global logout | Discovery end_session, retained ID hint, exact URI, one-use state |

Live authorization retains HMAC hint across refresh. Lost pre-upgrade hints cannot be reconstructed.

### Back-channel execution

- Signed form logout_token, no browser Cookie.
- Verify logout+jwt/RS256/issuer/audience/time/jti/events/sid or sub.
- Reject nonce/malformed claims; idempotent `(issuer,sid)` revocation.
- Keep replay/sid markers for at least the local absolute lifetime, default 14 days.
- Success means committed local execution; prevent late callback/registration resurrection.

Default repositories/index/replay/refresh coordination/sink use one JVM/HttpSession; restart ends business login. Reference durable queue preserves committed tasks only. Pre-submit compensation/multi-instance/retained login are consumer-owned; [maintenance](reference-revocation.md). Submission request attribute is not remote acknowledgement.

## 5. First-party eligibility and recent password

### Eligibility

| Item | Contract |
| --- | --- |
| Caller | ACTIVE FIRST_PARTY Basic, no Cookie/Bearer substitute |
| Route | GET `/internal/v1/principals/{UUID}/service-access` |
| Seven fields | issuer/subject/decision/reason/stateEpoch/stateVersion/changedAt, no-store |
| Unknown subject | 200 DENIED/PRINCIPAL_NOT_FOUND, version 0, null changedAt |
| Missing epoch/auth failure | 503/authentication error, never invented ALLOWED |
| Denied states | Freeze/deletion wait-held/password required/disabled credential |

Compare versions only within same issuer/subject/epoch. Epoch is opaque. Unexpected generation fails closed, authoritative reconciliation under new local generation, old cache/inflight rejected. No per-response generation flipping.

Query/active refresh must observe denial within one minute. Consumer timers/cache are not in package; eligibility events deferred, logout not state synchronization.

### Password evidence

1. Standard authorization customizer: prompt=login/appropriate max_age.
2. Bind pending action/originating local session/state/nonce/PKCE.
3. After validated login read signed initial auth_time through getAuthenticatedAt().
4. Reuse only in the originating session for 15 minutes, then require password reentry.

The OP challenge lasts 5 minutes with atomic JDBC consumption. Refresh/issue time cannot advance evidence. Starter exposes hooks, not pending-operation/expiry policy. A dialog alone is not evidence; no MFA gate added.

## 6. Consumer test matrix

| Test | Expected |
| --- | --- |
| Two clients/rename | Independent login, same UUID |
| Concurrent refresh/reuse | Coalescing and rejection |
| Local/global/duplicate-late logout | Exact revocation, no resurrection |
| Client disable/provider outage | Denial vs temporary unavailable |
| Restart | Initial business login ends |
| Freeze/deletion/held | Denial observed within one minute |
| Restore epoch | Old cache/inflight refused |
| Other browser/expiry/refresh evidence | Originating-session15minute boundary |

Record real consumer results; sample/SSO tests cannot substitute them.

## I01 Prepare registration handoff

**Actors/location:** Consumer backend/SSO administrator, isolated config record and SSO browser; business routes closed.

1. Agree issuer/business HTTPS origin/registrationId/exact callback/logout/reachable Back-channel/audience/single-instance restart policy.
2. Containers require reachable verified-TLS addresses, not another container's localhost; preserve callback path, no legacy strip-api rule.
3. Administrator [A05](administrator-guide.md) registers independent client; exchange non-secret settings/version and private backend secret.
4. Match type/scopes/URIs and pin SDK/SSO/hashes. Account-site-only opening needs no invented client.

**Expected:** Precise inputs agree. **Failure:** Resolve URI/reachability, not wildcard or omitted logout acceptance.

## I02 Install fixed SDK and one chain

**Location: consumer project, not SSO package root.** Reference sample does not create/start LASLOG.

1. Verify the package and configure its [file Maven repository](../../sdk/README.md), exact starter version `{{VERSION}}`, final BOM/locks and Java 21 compatibility.
2. Install section 3 settings externally and supply the secret/issuer privately. Configure the matching Servlet Cookie as Secure/HttpOnly/Lax, Path=/, without Domain, and a same-origin proxy.
3. Call the public configurer once, retain business default-denial/CSRF/success delegate, and avoid duplicate Filters or unwrapped overrides. Use the public return-target wrapper where needed.
4. Replace the public repository/session/replay/sink/refresher/manager according to the consumer policy and prove these Beans are called. A database alone does not make defaults persistent; the selected adapter supplies the worker.
5. Start the isolated consumer and verify routes, errors, Cookie, proxy and restricted access. Only SSO collects the password.

**Expected:** Fixed dependency/unique chain/explicit permissions. **Failure:** Repair external settings/composition, no hidden source copying.

## I03 First login and browser isolation

1. Browser A enters through the consumer's `/api/auth/login` with a controlled `returnTo` and completes SSO login and any partner consent.
2. The backend validates one-time state/nonce/PKCE/code and signed Tokens, saves and registers the session before callback success. The browser receives only safe local Cookie/session data.
3. Browser B logs in independently as the same user. Confirm separate Tokens, refreshing A cannot overwrite B, and renaming preserves the subject.
4. Wrong/reused callbacks and unsafe return/client values must not authenticate. A consumer restart refuses old Cookies/pending transactions; a new login follows the independent SSO session.
5. Record actual HTTP/browser results, versions and safe correlation references. Identity alone does not grant eligibility, onboarding or business authority.

**Expected:** Independent valid login, failed transaction no success. **Failure:** Shared Token/automatic privilege is consumer gap, not bypass.

## I04 Refresh failure and two logout types

1. Use an isolated fixture or actual expiry to test single-flight refresh and successful persistent CAS before protected access. Do not shorten production expiry simply to test faster.
2. Test definite refusal, save failure, timeout/lost response, logout races, late results and real process restart. Prove local refusal, durable accepted known work and actual retries separately.
3. CSRF-protected local logout ends the business login/Cookie and retains SSO. A new top-level ordinary login may reuse the SSO session without another password prompt.
4. Global browser flow uses the trusted end-session URL, original hint, state and exact return URI. Test two consumers/browsers and repeated/late Back-channel messages without login revival.
5. Record unknown B honestly; old A's HTTP 200 is not family cleanup. Verify the fixed deadline and residual AT exposure, obtain explicit acceptance or select a separate enhancement; do not deliberately reuse A.

**Expected:** Ended/uncertain login stays ended. **Failure:** Outage not ban; submission/log/ordinary200 not family completion.

## I05 Eligibility/password wiring

1. Validate the seven query fields, issuer/subject/reason and same-epoch high-watermark. Implement the one-minute cache/activity schedule; idle-game and business enforcement remain consumer-owned.
2. A restored epoch needs independent verification, expected local generation and a fresh query. Clear old session/cache/pending/evidence, reject delayed replies, and never sort epoch UUIDs/timestamps.
3. Bind the pending action, state/nonce/verifier to the initiating local session and request prompt=login/max_age=0. Only SSO accepts the password.
4. Match issuer/sub/sid/session/signed auth_time and consume the callback once. Enforce the exact fifteen-minute boundary; logout/restart clears evidence. Local login success, Token refresh and dialogs do not renew it; only validated new password-authentication evidence can establish a new timestamp.
5. Require explicit user resubmission and recheck eligibility/business/resource state. The callback executes no command. Test other browsers, exact expiry, wrong identity and old callbacks.

**Expected:** Independent gates/verifiable evidence. **Failure:** SSO count not consumer acceptance, no addedMFA.

## I06 Complete and maintain

**Expected:** The consumer's own evidence covers supported integration and failure paths, with pinned versions and an assigned operating owner.

**Failure:** Failed or unexecuted consumer checks remain open; repair the owning adapter/configuration and retest before opening the affected routes.

1. Record expected/actual/unexecuted results and limits for section 6 and [API F07–F08](api-operations.md).
2. The consumer owner reviews permissions, recovery, notifications and negative paths, then opens only tested routes. The sample is not their own acceptance evidence.
3. Rerun affected flows after A06–A08 settings/secret changes. SDK upgrades pin the new version/hash/locks and check public compatibility, never follow a moving sibling HEAD.
4. Incidents first invalidate local access and preserve accepted work, inspect safe metadata and use authorized scope rather than automatic global escalation.
