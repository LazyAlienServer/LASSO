# Spring starter security-chain composition

- Status: public composition/lifecycle/error hooks implemented and tested on 2026-10-03; deployment acceptance is separate.
- Chinese counterpart: [Starter 安全链组合与生命周期](../zh-CN/integration/starter-security-composition.md).
- Source: `las-sso-server/las-sso-spring-boot-starter`; no released artifact is implied by this worktree contract.

## 1. Application-owned SecurityFilterChain

Optional reference-BFF [durable revocation profile](reference-durable-revocation.md) replaces only the existing work sink with encrypted PostgreSQL committed acceptance/restart recovery. It does not move Token/Session/index/replay into a shared store or close the local-logout→submit crash gap. Default starter behavior and public API remain unchanged; see [ADR-0009](../adr/0009-reference-bff-durable-token-revocation.md).

P03 adds request-scoped `LasSsoRevocationSubmission` outcomes and changes invalid/unadvertised default-sink input from a silent return to rejection. ReferenceV2 provides operator recovery/retention/drain-first key rotation; [current guide](reference-durable-revocation.md) defines compensation and compatibility limits.

Defining a `SecurityFilterChain` makes the starter's default chain back off. Inject `LasSsoSecurityConfigurer` and call `configure(http, applicationSuccessHandler)` once on the chain that handles LAS OAuth2 endpoints, the local session and logout. It installs the same protocol/lifecycle wiring used by the default chain. Application routing and business authorization remain application-owned.

```java
@Bean
SecurityFilterChain applicationSecurity(HttpSecurity http, LasSsoSecurityConfigurer las) throws Exception {
    http.authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/", "/error", "/assets/**", "/api/auth/login", "/api/auth/session",
                    "/logged-out", "/internal/oidc/backchannel-logout").permitAll()
            .anyRequest().authenticated());

    var success = new SimpleUrlAuthenticationSuccessHandler("/protected");
    success.setAlwaysUseDefaultTargetUrl(true);
    las.configure(http, success);
    return http.build();
}
```

The example uses standard Spring imports plus `org.las.sso.client.LasSsoSecurityConfigurer`. The reference BFF includes a compiled equivalent under the optional `application-security` profile:

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun --args="--spring.profiles.active=application-security"
```

This is an available launch command, not a recorded live-browser verification. Start SSO and keep the configured issuer/callback host consistent, as described in the server README.

The identified 2026-10-05 development starter also provides `configure(http, success, Consumer<OAuth2AuthorizationRequest.Builder>)`. Applications may explicitly add trusted standard prompt/max_age parameters; the existing overload remains unchanged. Preserve client/redirect/state/nonce/PKCE; local returnTo attributes stay local. Verified ID Token auth_time is accessible through OidcUser after callback success, but a relying party must bind it to its pending local session/operation and enforce expiry/revocation itself. The default does not forward browser-supplied protocol parameters. [Development delivery](development-delivery-2026-10-05.md) / [Chinese](../zh-CN/integration/development-delivery-2026-10-05.md) records the public signature, signed fixture tests, artifact compile/resolution and limits.

The configurer installs:

- Spring OAuth2 Login using a PKCE customizer and the shared `LasSsoAuthorizedClientRepository`;
- an authentication-success wrapper that registers the verified local session before invoking the supplied success handler;
- one refresh filter and one request-time registration filter, retaining the same repository/session ownership;
- current-app logout at `/api/auth/logout`, cookie cleanup and local invalidation before revocation submission;
- a CSRF exception for **POST only** `/internal/oidc/backchannel-logout`; ordinary application writes/logout retain CSRF protection.

It retains the standard `/oauth2/authorization/{registrationId}` and `/login/oauth2/code/{registrationId}` routes. Applications include these and the receiver in the matching chain and permit the receiver. The configurer does not decide business roles/public routes/CORS/entry points. B5 now provides validated transaction-bound returnTo; application success handlers use it explicitly as documented below. Duplicate application fails. Do not later replace the repository/unwrapped success handler; multiple chains/registrations/custom paths require acceptance.

## 2. Callback registration and failure semantics

Spring validates the authorization response, exchanges the Code with PKCE/client authentication, validates the ID Token, saves the authorized client, performs session-fixation protection and saves authentication. `LasSsoAuthenticationSuccessHandler` then registers the configured LAS registration's `sid`, subject and resulting local `HttpSession` before redirecting. Only an authenticated OAuth2 result for `las.sso.client.registration-id` participates. LAS login requires a nonblank verified OIDC subject and `sid`.

The wrapper removes any index for the current local session before registering the new identity. This prevents a later login, including fixation handling that saw the old principal, from leaving an earlier sid mapping for the same local session. A request-time filter remains for established sessions; applications compose it through the configurer rather than instantiating the package-private filter.

`LasSsoBackChannelSessionStore.register` must be repeat-safe and return only when `invalidate` can use the index. Throw on failure. Callback/request registration failure triggers index cleanup, local authorized-client removal, authentication/session invalidation and revocation submission using the existing logout handler, followed by `SESSION_REGISTRATION_FAILED`. The application success delegate is not invoked. Remote submission is subject to the sink's acceptance guarantees; it is not proof of OP revocation.

The earlier wrapper removes the original B2 window **after callback success and before the next request**. On2026-10-05 the default registry also gained user-approved sid revocation markers, including no-match Logout, retained for configured sessionAbsoluteLifetime (default14days). Marker/check and index snapshot/publication share a short state lock; Session/lifecycle calls run outside it. Late registration throws and existing failure cleanup ends local login before application success. Different sids for one subject are unaffected. [ADR-0008](../adr/0008-retain-sid-revocations-before-local-registration.md) specifies exact expiry and compatibility. Custom adapters must provide their own equivalent protection. Token/index are still separate operations, not a distributed transaction; in-flight business requests/crash/restart/shared stores and durable multi-instance coordination remain separate.

## 3. Session lifecycle

Auto-configuration registers public `LasSsoSessionLifecycleListener` using a `ServletListenerRegistrationBean` named `lasSsoSessionLifecycleListener` even when the application owns its security chain.

- Destruction: idempotently unregister the local session ID.
- Session ID change: unregister the old ID, then register the current verified LAS identity under the new ID if a stored Spring SecurityContext exists. Initial login may not yet have stored authentication, so the success wrapper registers next.
- Reindex failure: invalidate the local session, log only a non-secret failure category, and raise a generic failure without propagating adapter exception text.

Applications replacing that named bean must wire both `HttpSessionListener` and `HttpSessionIdListener` behavior themselves. A non-Servlet/shared-session store needs a corresponding lifecycle adapter; this listener alone does not prove Spring Session/custom persistence compatibility or cross-instance coordination.

Spring may save the authorized client before rotating the Session ID. A custom Token adapter keyed by the literal Servlet Session ID must correlate or migrate that ownership during rotation; the starter's index listener does not move the adapter's Token rows. Default/recording test repositories delegate to the same HttpSession object, so their passing callbacks are not evidence that a persistent ID-keyed adapter already handles this transition.

## 4. Error response extension

Provide a `LasSsoClientErrorHandler` bean to replace starter refresh/registration error rendering:

```java
@Bean
LasSsoClientErrorHandler ssoErrors() {
    return (request, response, failure) -> {
        response.setStatus(failure == LasSsoClientErrorHandler.Failure.SESSION_EXPIRED ? 401 : 503);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + failure.name() + "\"}");
    };
}
```

Failures are `SESSION_EXPIRED`, `SSO_UNAVAILABLE` and `SESSION_REGISTRATION_FAILED`. This hook renders a response only; terminal local cleanup happens first and the protected chain does not continue. A temporary refresh failure retains the session/token but stops the current request. The hook must not report success, restore authentication, continue the chain or log credentials/token values. This is not a replacement for general Spring authentication/authorization failure handlers or B4 receiver errors.

`DefaultLasSsoClientErrorHandler` preserves existing refresh behavior: no-store 401 problem for API expiry, browser redirect to `/api/auth/login` for non-API expiry, and no-store 503 problems for transient SSO or registration failure. Existing three-argument public refresh-filter constructor remains compatible; a four-argument overload accepts this hook. `LasSsoAuthenticationSuccessHandler` is public for manual standard Spring wiring; the preferred supported composition is the configurer using the same LAS repository/refresh/logout components.

## 5. Executed evidence and remaining work

`LasSsoOidcCallbackIntegrationTest`: **14 cases**, including default signed callback → immediate signed logout with no intervening request, two sessions of one subject, external-package application-owned composition, custom repository/index/error handlers, failed registration, missing sid, invalid RSA signature/nonce, ID change/destruction hooks, repeat login, duplicate configuration rejection, CSRF logout and real HTTP refresh failure routing. The OP fixture verifies client Basic auth, exact redirect and S256 verifier, serves real RSA JWKS, and signs ID/Logout Tokens. The original B2 regression failed before the fix.

The actual Spring FilterChainProxy, Login provider/repository/manager and HTTP/JWT clients are exercised. Inbound Servlet is mocked, ID events are explicitly dispatched, and Back-channel routing calls the actual controller from a fixture terminal servlet; MVC binding, a real container/browser/SSO, persistent adapters, deployment, and logout-before-registration races are not verified. Two other new tests cover the response hook at the refresh filter seam.

Full forced rerun on 2026-10-03: **158 server + 53 starter tests**, zero failures/errors/skips; reference BFF compiled again, `test NO-SOURCE`. No dependencies/locks or schema changed in this step. B3/B4/B5, durable acceptance, client reauthentication resolver/consumption and identifiable release delivery remain separately tracked in the [status map](../implementation/status-issues-and-plan-2026-10-02.md).

Subsequent B3 sender acknowledgement/retry repair was verified on 2026-10-04; latest full suite is 178 server / 71 starter. See the separate [delivery acknowledgement contract](logout-delivery-acknowledgements.md). This later sender evidence does not enlarge the callback/lifecycle acceptance described here.

The subsequent B4 stage ran178server/117starter. Replay adapters must implement process; accept-only fail503 as approved in ADR-0003. See the [receiver upgrade contract](backchannel-logout-receipts.md); this does not accept real lifecycle/durable adapters, and B5's newer run is recorded below.

## 6. B5 return target update — 2026-10-04

The configurer now composes standard authorization request storage with ten-minute creation time, one-JVM/session single-consumption and internal return target attributes; one pre-authorization validation filter rejects unsafe targets with 400. Default login returns to a verified target. The application-owned success delegate still always executes: inspect `LasSsoReturnTo.target(request)` or call `LasSsoReturnToSuccessHandler` only as the final redirect after application checks. Do not make business checks the redirect handler's fallback, since fallback is only called when there is no target.

Reference BFF profile explicitly adopts the target redirect and its home login link asks for `/protected`. Public API signatures remain; pending old requests without a timestamp must restart login, and custom authorization resolver/storage must retain the state/expiry/target contract. Details/URI policy/limits: [English](login-return-target.md) / [Chinese](../zh-CN/integration/login-return-target.md), ADR-0004. Latest full suite 178 server / 176 starter, zero failures/errors/skips; callback suite now 28 cases plus 45 target/transaction cases. No real MVC/browser/shared-store or consumer acceptance is inferred.
