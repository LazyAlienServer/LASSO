# Backend/BFF integration

[Index](README.md) · [中文](../zh-CN/integration.md) · [API](api.md) · [Reference BFF](reference-bff.md)

Use a confidential server client with Code+PKCE S256. Browser holds only a local HttpOnly Cookie; passwords stay at SSO and Tokens stay backend-only. Business authorization is separate from identity and service eligibility.

## 1. Register an independent client

Agree trusted issuer, business HTTPS origin, exact callback/post-logout/Back-channel URLs, audience/scopes and owner. One client/environment; no wildcard callbacks or another container's localhost. Administrator A05 supplies the secret privately; current create UI fixes scopes to openid/profile. FIRST_PARTY is LAS-controlled; REVIEWED_PARTNER requires review/consent.

Consumer links a stable `(issuer,sub)` UUID, not login username. Renaming does not change identity. No role is assigned solely because OIDC login succeeded.

## 2. Install the exact Starter

JDK21/Servlet Spring Boot Web, OIDC JSON converters, reviewed final BOM/locks. The package includes a file Maven repository:

```kotlin
repositories {
    maven { url = uri("file:///absolute/package/sdk/maven"); content { includeGroup("org.las") } }
    mavenCentral()
}
dependencies { implementation("org.las:las-sso-spring-boot-starter:{{VERSION}}") }
```

External registration settings:

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

Set the actual Servlet Cookie to the same name, Secure/HttpOnly/Lax/Path=/, no Domain; host edge preserves paths and overwrites proxy metadata. Protect raw secrets outside source/frontend.

## 3. Compose security once

Without an application chain the Starter supplies its default. With your own SecurityFilterChain call `LasSsoSecurityConfigurer.configure(http, existingSuccessHandler)` once, retain business routing/CSRF, and use `LasSsoReturnToSuccessHandler` when adopting returned navigation. Keep the wrapped success handler and avoid independently installing duplicate filters. The public authorization-builder customizer is trusted backend configuration, not arbitrary browser prompt/max_age forwarding.

| Endpoint | Purpose |
| --- | --- |
| GET `/api/auth/login` | One-use state/nonce/verifier, validated local returnTo |
| GET `/api/auth/session` | Safe no-store identity snapshot, not permissions |
| POST `/api/auth/logout` | CSRF local logout, retain SSO |
| POST `/api/auth/logout/global` | Trusted end_session/original ID hint/state/exact registered return |
| POST `/internal/oidc/backchannel-logout` | Signed protocol receiver; only this POST CSRF exception |

Verify code/state/session/nonce/PKCE, signed iss/aud/algorithm/time and UserInfo sub. Store/register the verified local session before successful callback navigation.

## 4. Storage and failure policy

| Public interface | Default / adapter obligation |
| --- | --- |
| LasSsoAuthorizedClientRepository | Session-owned Token state, successful save before protected use |
| LasSsoBackChannelSessionStore | JVM sid/sub index and revoked-sid markers; reject late registration |
| LasSsoLogoutReplayStore | Execution receipts; implement process; legacy accept-only returns503 |
| LasSsoRevocationTaskSink | JVM retries; persistent normal return means committed acceptance, not remote completion |
| LasSsoClientErrorHandler | Safe response hook, cannot revive ended login |

Default repositories/index/replay/refresh are single-JVM; BFF restart ends business login. Lifecycle listener handles destroy/session-ID changes. Optional reference queue preserves accepted tasks only. Consumers choosing durable adapters keep their pre-submit uncertainty/recovery strategy explicit and test their actual beans/worker.

Single-flight per session. Definite invalid_grant ends local login; transient503 denies the request without inventing account ban/password evidence. Lost successor/failed persistence is unknown: local refusal, accepted known-token work retained, explicit residual-grant assessment. Old-token revoke200 is not unknown-family cleanup; do not deliberately reuse it.

## 5. Eligibility and recent password

FIRST_PARTY uses the seven-field [eligibility query](api.md). Implement one-minute observation/activity/cache policy, same-epoch high watermark and verified new-generation reconciliation; reject delayed old replies. SSO does not implement business permission/cache policy.

For high-risk actions bind pending action/session/state/nonce/PKCE, request prompt=login/max_age, validate signed initial auth_time and reuse for strictly less than15minutes only in the initiating local login. Clear on logout/restart/revocation. Refresh/dialog/local callback success cannot advance it. Callback executes no dangerous command; user resubmits and authorization/resource state is rechecked.

## Consumer acceptance

Test two clients/browsers, rename, same-session refresh concurrency, local/global/duplicate-late logout, restart, storage/provider failures, client disable, freeze/deletion/hold, restore epoch/in-flight rejection and cross-browser/exact-expiry password evidence. Keep actual result/version/owner; sample tests do not prove LASLOG integration.
