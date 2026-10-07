# External Integration

[Index](README.md) · [中文](../zh-CN/external-integration.md) · [SDK](../../sdk/README.md)

## Generic OIDC

Use a maintained OIDC library and a confidential server/BFF per environment. Register `client_id`, client name/type (`FIRST_PARTY` for LAS controlled deployments, `REVIEWED_PARTNER` for approved partners), exact HTTPS callback/post-logout/Back-channel URIs, `openid profile` and resource audience via `/admin/clients`. Preserve the one-time secret privately. Partners receive consent; first-party consent suppression does not remove protocol/security checks. No public/browser client secret or implicit/password grant.

Discover endpoints from the exact issuer, never mix localhost/127.0.0.1 or alternate public hostnames. Use Code+PKCE S256, `client_secret_basic`, one-use expiring state/nonce/login transaction and an allowlisted local `returnTo`. Verify signature/algorithm/kid/iss/aud/azp/time/nonce before mapping `(issuer, sub)` uniquely. `sub` is immutable UUID; username is mutable and case-sensitive. `LasUserPrincipal.getName()/getUsername()` return UUID, while `loginUsername()` is the login name. New business identities receive no privileged role automatically.

Tokens/Secret stay on the server; the callback may transport the code to the server but must not expose it to frontend storage/analytics/logging. Browser only holds a random Secure/HttpOnly/Lax host-only business Session Cookie. Business roles and Minecraft links/admission remain consumer-owned. ID Token is not API Bearer; resource servers validate AT audience/scope/issuer/signature/time.

AT defaults15 minutes; RT family has an absolute14-day lifetime, rotating without extending it. Honor actual Token expiry. Merge concurrent refreshes for one local Session. Definite refresh rejection clears login; transient SSO failure yields a distinct503 rather than a ban. Current logout ends local login first and attempts RFC7009 without ending the SSO main Session. Global logout uses discovered end_session endpoint, retained ID hint, exact registered post-logout URI and single-use state. The server retains HMAC hint associations across refresh for live authorizations, but cannot reconstruct already-lost pre-upgrade hints.

Back-channel accepts signed form `logout_token` without browser Cookie. Verify `typ=logout+jwt`, RS256/issuer/audience/time/jti/events/sid or sub, reject nonce/malformed claims and retain replay/sid revocation markers for the relevant local absolute lifetime. Success means local execution committed, not merely parsing the request. Idempotently invalidate sessions by `(issuer,sid)` and prevent late callback/refresh registration from reviving them.

## Spring Boot Starter

Consume the pinned file repository coordinate as in [SDK](../../sdk/README.md). Application needs servlet Web, OIDC JSON converters, Boot4.1/JDK21 and its own final dependency/lock review. Minimal external config:

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

Set the actual servlet Cookie to that same name/Secure/HttpOnly/Lax/Path=/; no Domain. Endpoints: GET `/api/auth/login`, `/api/auth/session`, `/logged-out`; POST `/api/auth/logout`, `/api/auth/logout/global`, `/internal/oidc/backchannel-logout`. Cookie mutations require CSRF; only the signed Back-channel protocol POST is exempt. Do not exempt browser API groups. `/api/auth/session` represents local identity, not a business-authorization response.

If you own a `SecurityFilterChain`, the Starter does not replace it. Apply `LasSsoSecurityConfigurer.configure(http, existingSuccessHandler)` once to wire repositories, PKCE, callback/register/refresh/logout filters; keep application route/CSRF/authorization policy and do not overwrite its wrapped success handler afterward. Both default and application-owned modes are in the [sample](../../examples/reference-bff/README.md). Default authorized clients/refresh coalescing/session index/replay/revocation sink are single-JVM, with default HttpSession; restart loses business login, followed by top-level SSO reentry. Cross-restart retained login/multi-instance requires equivalent coordinated adapters. Keep sid markers at least local absolute lifetime (default14 days).

Optional reference `https,durable-revocation` applies embedded reference V1–V2 to independent PostgreSQL schema reference_revocation. See [templates](../../config/README.md). Committed encrypted tasks/leases/8-attempt campaigns survive restart; V2 adds exact-generation offline dead replay, completed retention and authenticated drain-first key rotation. Submission outcome is available through `LasSsoRevocationSubmission.REQUEST_ATTRIBUTE`, not remote acknowledgement. Before-submit crash compensation and distributed business sessions remain consumer-owned. See [operations](operations.md).

## LAS First-Party Eligibility And Recent Password

Only ACTIVE FIRST_PARTY Basic clients may GET `/internal/v1/principals/{UUID}/service-access`. No browser Cookie/Bearer substitute. Response is no-store with exactly `issuer, subject, decision, reason, stateEpoch, stateVersion, changedAt`. Unknown UUID is200 DENIED/PRINCIPAL_NOT_FOUND/version0/null changedAt. Missing global epoch yields503; authentication/budget failures must not become ALLOWED. Freeze/deletion/held/password-required/disabled credentials deny; valid ACTIVE allows. This is identity service eligibility, not business roles.

Order versions only within `(issuer,subject,stateEpoch)`. Epoch is opaque/non-sortable. Consumers retain monotonic same-epoch facts, fail closed on unexpected epoch, reconcile authoritatively under a new local generation, clear old evidence and reject delayed prior-generation responses. Do not flip epoch per arriving response. Poll/active refresh compensation must make denial observable within one minute; the package does not implement consumer timers/cache, and `SERVICE_ACCESS_CHANGED` remains deferred. Logout is not eligibility synchronization.

For high-risk consumer operations, initiate OIDC `prompt=login` and appropriate `max_age` through the standard authorization customizer, correlate pending operation/local browser-login Session/state/nonce/PKCE, validate the signed initial ID Token `auth_time`, and consume password evidence within15 minutes only in that originating Session. OP browser/request challenges last5 minutes and use atomic JDBC completion/consumption. Refresh/Token issue time never advances password evidence. Starter `LasSsoSecurityConfigurer` exposes the authorization customizer; read signed evidence through Spring `OidcUser.getIdToken().getAuthenticatedAt()` only after validated login. These do not implement the consumer's pending-operation/expiry gate. A dialog alone is not evidence. No MFA release gate is added.

Acceptance: two clients, concurrent refresh, username rename preserving identity, local/global logout, repeated/late Back-channel, client disable, provider/network outage, backend restart, one-minute eligibility denial, stale epoch in-flight refusal and originating-session15-minute/password-refresh-negative cases. Record real consumer results separately from sample/unit evidence.
