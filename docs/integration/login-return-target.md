# Login return target and local authorization transaction

- Status: B5 implemented and controlled verification completed on 2026-10-04; real consumer/deployment acceptance pending.
- Chinese counterpart: [登录返回目标与本地授权事务](../zh-CN/integration/login-return-target.md).
- Decision: [ADR-0004](../adr/0004-bind-local-return-target-to-login-transaction.md), user-confirmed explicit application integration.

## 1. Input and URI policy

`GET /api/auth/login?returnTo=<application-local-path>` accepts at most one optional returnTo value. Missing value retains existing login behavior. An empty, repeated, malformed, unsafe or >2048-character value returns no-store **400**, without a Location header or new Session. The configured direct `/oauth2/authorization/{registrationId}` entry applies the same rejection before Spring starts authorization. Successful login-entry redirection is 302/no-store.

Targets are paths relative to the application's context, starting with exactly one `/`. Examples:

```text
/projects/42
/projects/42?tab=members&filter=active
/search?q=中文
/项目/42
```

The implementation parses URIs with the JDK and converts accepted Unicode to an ASCII URI. It rejects scheme/authority, leading `//`, fragments, backslashes, control or whitespace characters (raw or decoded), malformed percent encoding, dot path segments, semicolons and encoded path separators or delimiters. Residual `%` after one URI decoding is rejected in both path and query, excluding nested encoding. Ordinary encoded query text such as `a%2Bb` is permitted; encoded whitespace is not part of this initial policy. This is deliberately conservative; applications needing a broader syntax should agree it separately instead of decoding/redirecting unchecked values.

The login entry encodes the complete target as one parameter while forwarding it to the local authorization entry, preserving embedded `?`, `&`, percent encoding and non-ASCII text. The final redirect prefixes `request.getContextPath()`, so `/projects/42` under context `/bff` redirects to `/bff/projects/42`. It does not use Host/forwarding headers to choose a target origin. Local target endpoints still own their authorization and any secondary redirect logic.

## 2. State/session/expiry binding

`LasSsoSecurityConfigurer` composes the standard PKCE resolver with an internal resolver/repository. Validated target and creation timestamp are stored in OAuth2AuthorizationRequest **attributes**, never OP query/additional parameters; state, nonce, PKCE and exact callback validation remain standard Spring behavior.

- One outstanding authorization per local Session, preserving Spring's existing behavior. A new authorization replaces that Session's old request/target; separate Sessions remain independent.
- Callback requires exactly one matching state in the initiating Session. Wrong browser/state or duplicate state is rejected without consuming the correct pending request.
- Matched authorization is removed once, before Code exchange. It must have non-future creation time and be strictly younger than ten minutes; the exact ten-minute boundary is rejected. Expired matched requests are removed and must start again.
- The consumed target is request-local but not yet trusted by success code. Only after Code/nonce/signature validation and successful Session registration does `LasSsoAuthenticationSuccessHandler` expose it through public `LasSsoReturnTo.target(request)`.
- Callback returnTo cannot override it. OAuth errors, failed nonce/signature, expired/replayed transactions and registration failure do not produce a target redirect; subsequent no-target login does not inherit a prior target.

Consumption synchronizes the standard HttpSession-backed repository on the local Servlet Session object. It verifies only the initial one-JVM contract, not distributed atomic consumption for shared stores or per-request Session wrappers. The transaction is removed before remote authentication finishes; a failure requires new login rather than reusing old state. Old deployment requests lacking the new timestamp also require restart of the login flow. Manually replacing authorization request storage/resolution must preserve these requirements; the configurer does not provision a durable adapter.

## 3. Application-owned success handling

The default chain uses `LasSsoReturnToSuccessHandler` to select a verified target, otherwise delegates to its existing fixed `/` fallback. Verified target redirects are 302/no-store; saved default Spring request/error state is cleared. This is navigation, not automatic replay of a POST or other dangerous operation.

An application-owned success delegate is **not bypassed or overwritten** when returnTo exists. It may inspect `LasSsoReturnTo.target(request)` and choose its own response, or explicitly use the public redirect handler after its business checks:

```java
var fallback = new SimpleUrlAuthenticationSuccessHandler("/home");
fallback.setAlwaysUseDefaultTargetUrl(true);
var finalRedirect = new LasSsoReturnToSuccessHandler(fallback);
las.configure(http, (request, response, authentication) -> {
    // Perform the application's own success checks here; stop on rejection.
    finalRedirect.onAuthenticationSuccess(request, response, authentication);
});
```

For a pure redirect-only delegate, pass `new LasSsoReturnToSuccessHandler(existingRedirectHandler)` directly. Do not wrap business checks as this handler's fallback: fallback only runs when there is no verified target. A consumer stopping with 403 is verified to remain stopped, with no forced starter target redirect. The reference BFF's optional `application-security` profile explicitly uses this handler; its home login link requests `/protected`. The sample compiles, but its UI/profile was not launched for browser acceptance.

All target handling follows callback validation/Session registration, preserving B2 immediate logout indexing, B1 Token ownership and B4 replay behavior. It does not create recent-password evidence or grant access to the target page.

## 4. Executed evidence

Two signed-login target regressions failed before the fix: both `/projects/42` and the same path with query were discarded and login returned `/`. Initial two cases became green after the state-bound implementation. An expanded direct-authorization invalid-target test exposed Spring's generic resolver error returning 500; a dedicated matched validation filter now returns 400/no-store, with existing valid transaction preserved. That was distinct from a fixture assertion initially assuming the 500 response had a redirect. A patch-application compile error was corrected during setup and is not a production defect.

- `LasSsoReturnToTest`: **45** cases covering allowed/invalid URI matrix, query forwarding, context prefix, duplicate/oversized/absent input, untrusted callback parameters, exact expiry/future time, wrong/duplicate state, replacement/browser isolation and deterministic concurrent one-time repository consumption.
- `LasSsoOidcCallbackIntegrationTest`: **28** cases (14 new relative to B4). Actual signed Code/PKCE HTTP/JWKS flow verifies target success/tampering/replay, browser/latest-request isolation, failed nonce, OAuth error, expiry before exchange, duplicate state, concurrent callbacks with one exchange, explicit/preserved custom success, business rejection and registration failure, along with earlier lifecycle/logout/error behavior. Target is confirmed absent from OP authorization parameters.
- `LasSsoSessionChainIntegrationTest`: existing **10** cases continue to pass. Final related focused command ran **83** cases (45+28+10), zero failures/errors/skips.

Commands in `las-sso-server/`:

```powershell
.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest.defaultSignedLoginReturnsToTheValidatedOriginalTarget" --no-daemon --console=plain
# Original two-case red, later green as part of larger runs.

.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoReturnToTest" --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest" --tests "org.las.sso.client.LasSsoSessionChainIntegrationTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

Latest full forced run on 2026-10-04 at approximately 14:23–14:26 Asia/Shanghai: **178 server + 176 starter**, zero failures/errors/skips, 12 actionable tasks executed. Reference BFF compiled/resources processed, `test NO-SOURCE`. B5 added 59 cases; no schema/dependency/lock changed. Earlier red XML files were overwritten by later Gradle results; command output/status map §3.7 retains phase evidence.

## 5. Verification limits

Inbound Servlet, login-controller dispatch and Session ID events in signed-flow tests are fixtures; actual Spring filters/providers/repositories, HTTP Token/JWKS and RSA are exercised. Exact time boundary/repository concurrency use Clock and Session fixtures. This is not live MVC parameter binding, browser, real SSO/container/proxy normalization, sample UI rendering, shared Session persistence, restart/multi-instance acceptance or consumer business policy acceptance. Real consumer routes with secondary redirects need their own review. Pending artifact/source-version delivery, A3/S3/S4/S5/S6 and D1 remain independently tracked. Changes are uncommitted/unpublished/undeployed.

Subsequent A3 internal pre-hash budget repair was verified at15:46–15:49 on2026-10-04; latest full run203server/176starter, zero failures/errors/skips. See the separate [authentication budget contract](internal-basic-authentication-budget.md). That later server evidence does not enlarge this return-target/browser/shared-store acceptance scope.
