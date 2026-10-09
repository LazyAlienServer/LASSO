# Business website integration

[Index](README.md) · [中文](../zh-CN/external-integration.md) · [SDK](../../sdk/README.md)

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
