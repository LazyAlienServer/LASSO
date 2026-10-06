# Servlet HTTP verification of internal authentication and login risk

- Status: A1/A2 existing repairs verified through real loopback TCP/Tomcat/PostgreSQL on 2026-10-04; proxy/TLS/browser and deployment acceptance remain separate.
- Chinese counterpart: [Servlet HTTP内部认证与登录风控验证](../zh-CN/implementation/servlet-path-security.md).
- Tracking: [status map §3.14](status-issues-and-plan-2026-10-02.md).

## 1. Executed layer and scope

`ServletPathSecurityIntegrationTest` starts the real SSO application on a random HTTP port bound to `127.0.0.1`. Java HttpClient sends actual encoded request targets over TCP, without automatic redirects, MockMvc, injected authentication or a permit-chain/controller probe. Tomcat 11.0.22, Spring Security 7.1.1, application filters/MVC, Argon2 and Spring Session JDBC run against disposable PostgreSQL 18.4. `ServletContextPathSecurityIntegrationTest` inherits the same matrix and starts a separate instance under `/passport`. Both classes have **48 cases**, **96 new cases total** (28 login/risk + 20 internal authentication per instance).

Shared test properties explicitly set loopback binding, `server.forward-headers-strategy=none`, scheduling/JDBC cleanup disabled, and login-risk windows to 240 hours. Test-profile thresholds are CAPTCHA after two IP failures/block after three, with zero jitter. These are fixtures to keep matrix execution away from short-window boundaries, not production policy changes or validation of production fifteen/thirty-minute rollover. Before each case, only the relevant budget scopes are cleared in the disposable DB; tests do not claim simultaneous load/capacity or multi-instance coordination.

Accounts and clients are registered through owning services/repositories; client type/status metadata is explicit SQL fixture. Human login uses real CSRF acquisition, form submission, password authentication, Session-ID change, Cookie transport and subsequent JDBC-loaded authentication. No Mock principal/session is used. The HTTP helper explicitly forwards received **Secure** session cookies over loopback HTTP, because it is a transport fixture, not a browser. It does not establish browser Secure/SameSite/`__Host-` enforcement or TLS security. CAPTCHA POST supplies the fixed test issuer Origin (`http://127.0.0.1:9000`), rather than the random listening port; origin handling is explicit fixture input, not a live host/proxy-origin acceptance claim. The suite does not render/test all context-prefixed UI links.

## 2. Matrix and outcomes

### A1: dedicated first-party internal authentication

Plain and three single-encoded literal paths are exercised: `principals` as `%70rincipals`, `internal` as `%69nternal`, and `service-access` as `service-%61ccess`. For each configured context:

- Valid ACTIVE FIRST_PARTY Basic authenticates known/unknown UUIDs, returning ALLOWED/ACTIVE or DENIED/PRINCIPAL_NOT_FOUND, minimal fields and no-store. Successful budget reservations are refunded.
- Anonymous requests return 401/`Basic realm="LAS SSO internal"`/no-store without eligibility information.
- A real ordinary user's JDBC session Cookie cannot replace dedicated Basic; the user can still read their own profile afterward.
- Wrong secret, unknown client, REVIEWED_PARTNER, disabled client, absent metadata, unsupported client authentication method, malformed Basic and unrelated Bearer are denied on the encoded path.
- Correct service Basic supplied alongside a real human Cookie returns the query without overwriting the human's persisted authentication.
- Ten actual incorrect requests exhaust the existing **client** budget; correct Basic then receives 429/Retry-After/no-store on both plain and encoded routes. This is not a new network-throughput test.
- Double encoding, semicolon path parameters, encoded slash, NUL and backslash internal targets return 400 even with valid Basic and a human Cookie; no decision/subject/stateVersion response is returned. Boot's generic error body may echo the caller-supplied path/UUID, which is not a subject-state lookup.

### A2: login processing and risk gates

`/login`, `/log%69n` and `/%6cogin` are exercised under both contexts:

- Unrestricted correct login succeeds, creates exactly one user-session record and one subject-indexed Spring JDBC session, and accesses the existing `/api/v1/me/profile` endpoint on a later HTTP request.
- Missing CSRF returns 403 without authentication or failure-budget/user-counter changes.
- Wrong password advances the same user/IP counters and redirects to the context-local login error.
- A blocked network returns 429/Retry-After with no authenticated user session.
- Required but missing/malformed CAPTCHA redirects to CAPTCHA without password authentication/user failure changes.
- A challenge issued through the real HTTP API and solved with ALTCHA allows one login; another browser helper cannot reuse the proof.
- Correct proof with a wrong password is consumed once, records the password failure, and cannot be reused with the correct password.
- CSRF refusal happens before proof consumption; the same proof remains usable in a later valid request.
- Wrong-purpose, wrong-network and expired proofs remain unconsumed/rejected. These variants use explicit service/time/network fixtures; they do not substitute for the successful HTTP-issued positive control.
- Actual HTTP failures across plain/encoded paths reach CAPTCHA, then a solved-proof password failure reaches IP blocking.
- IP blocking precedes proof consumption. Supplied `Forwarded`/`X-Forwarded-For` cannot bypass it when forwarded-header handling is explicitly disabled; this does not approve a production trusted-proxy configuration.
- Double-encoded login, trailing encoded slash, semicolon parameters, NUL and backslash cannot authenticate or change user password-failure state (accepted test verdict is a rejecting 400/403/404).

These checks support the existing matchers/authorization/risk gates at the direct Servlet layer. No additional production repair was needed; only two test files and documentation were added. No schema, dependency, permission, credential policy or proxy configuration was changed.

## 3. Fixture corrections and verification evidence

The first three network-block cases passed. Expanded root-context 40-case testing had twelve fixture failures: the profile helper called unimplemented `/api/v1/me`, and CAPTCHA Origin used the random listening port instead of the fixed test issuer. Using existing `/api/v1/me/profile` and explicit issuer Origin produced two passing 40-case suites. These were not product vulnerabilities.

The next expanded 48-case run exposed a configuration-inheritance error: the child `@SpringBootTest` replaced the parent's property array. Explicit startup assertions failed in all 48 child cases; root had two overstrict error-body assertions because generic 400 responses echoed the supplied path. Shared settings moved to inherited `@TestPropertySource`; error assertions now check absence of eligibility fields rather than absence of the caller's own UUID. Final **48 + 48** passed, including assertions of loopback/forwarding/window settings. No failure in these phases is claimed as new A1/A2 defect evidence, and no old-code red run was fabricated; this task verifies existing repairs, preserving earlier issue history.

Commands in `las-sso-server/`, Java 21/Docker:

```powershell
.\gradlew.bat :test --tests "org.las.sso.security.ServletPathSecurityIntegrationTest" --tests "org.las.sso.security.ServletContextPathSecurityIntegrationTest" --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.security.ServletPathSecurityIntegrationTest" --tests "org.las.sso.security.ServletContextPathSecurityIntegrationTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.security.LoginFailureResponseJitterTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

Final related run at about 23:27–23:28 Asia/Shanghai: **181 = 48 + 48 + 6 + 25 + 53 + 1**, zero failures/errors/skips. Full forced run at about **23:30–23:34** on 2026-10-04: **419 server / 176 starter**, zero failures/errors/skips; 12 actionable tasks executed, reference BFF recompiled and `test NO-SOURCE`. Root 48-case XML timestamp `2026-10-04T15:32:45.005Z`; context 48-case XML `2026-10-04T15:32:33.975Z`; logs confirm actual Tomcat listening ports/context and PostgreSQL containers. The additional 96 cases follow D1's historical 323 server report. Earlier report XML is overwritten by subsequent runs; phase history remains in output/status §3.14.

## 4. Remaining acceptance

A1/A2 are now repaired and direct Servlet/TCP verified, with root/context paths. Production reverse-proxy request normalization/forwarded-header trust, TLS/browser secure-cookie behavior, JavaScript/UI/accessibility and complete release configuration remain unverified. Random malformed URI fuzzing, every servlet container/dispatch/method, concurrent CAPTCHA/browser flows, production thresholds/window rollover, throughput/connection pools/crash/restart/live multi-instance and full log/secret scans are not implied by this matrix.

S2 subsequently gained 23 owning-writer/failure cases in the [client record](client-secret-concurrency.md); SSO-04 gained epoch/lifecycle wire17 and a small TCP observation in the [query record](service-access-epoch-contract.md). The [identified local delivery](../integration/development-delivery-2026-10-05.md) has latest460server178starter, while419/176 above preserves this Servlet phase. Consumer observation/recovery barriers, real restore/production capacity and B1 durable/liveconsumer remain separate. Changes are uncommitted; no publication/deployment/userDB occurred in this Servlet step. Actual request-target/filter/MVC checks complement earlier MockMvc/races.
