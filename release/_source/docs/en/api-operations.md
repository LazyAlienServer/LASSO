# API workflows: authentication to result verification

[Index](README.md) · [中文](../zh-CN/api-operations.md) · [Fields/responses](api-reference.md) · [OpenAPI](../../api/openapi.json) · [User](user-guide.md) · [Administrator](administrator-guide.md)

## F01 Select an API and trusted client tool

**Location: trusted local API tool or backend development/test environment.** This explains call order; construct bodies from packaged schemas, never paste real passwords/Tokens into command arguments. First mutations use isolated accounts/clients, not an unconfirmed production target.

1. Import `api/openapi.json`, configure canonical issuer/base URL and verify CA/hostname. Fixture addresses are local only.
2. Select operation, identify SSO/recovery Cookie, machine Basic, AT Bearer or collector Basic; these are not interchangeable.
3. Put material in restricted private tool settings/cookie jar, disable full logs/cloud sync/sharing; saved non-secret templates contain no password.
4. Read prerequisites/status/idempotency in [reference](api-reference.md). Directory below maps every operation to its procedure; OpenAPI supplies exact path/fields.
5. Record UTC/method/path/status/safe code/requestId/target/non-secret version only.204 has no body; do not force JSON parsing.

**Expected:** Target/actor/scope/success criterion understood. **Failure:** TLS/discovery mismatch fixes configuration rather than disabling verification.

## F02 SSO Cookie, CSRF and mutation workflow

**Expected:** An authenticated, authorized request meets the operation's CSRF/origin/password conditions, returns its documented status and has a verified read-back or audit result.

**Prerequisites:** Cookie mode serves SSO browser APIs; user AT/client secret does not obtain administrator identity.

1. Log in at SSO and verify name/UUID. A separate API cookie jar must complete its supported browser form flow; do not export another site's Cookie casually.
2. Same jar calls getCsrf (`GET /api/v1/csrf`), retains cookies and headerName/parameterName/token. This is not login.
3. Mutations use returned token/header, JSON Content-Type/Accept and canonical Origin or same-origin Referer; origin is not CSRF substitution.
4. Generate one key when required; uncertain same-key retry preserves actor/operation/body, explicit new action selects a new key. Registration key is UUID.
5. Recent-password action first calls reauthenticateAccount with private password body.204 rotates HTTP session ID; refresh cookies/CSRF then send original mutation, not assume authentication performed it.
6. Send once, inspect operation-specific200/201/204/schema/Location, read target/audit for outcome.

**Failure:**401 renews login;403 distinguishes role/CSRF/origin/freshness/restriction;429 waits;400 checks fields/enums without broadening scopes. Timeout reads outcome. Filters/password change may return `{code}`/empty or anonymous redirect, not one universal Problem envelope.

**Non-secret minimal example:** Read profile in the logged-in SSO same-origin browser, not another origin. Do not print CSRF/Cookie or save production authentication captures.

```javascript
const response = await fetch('/api/v1/me/profile', {
  credentials: 'same-origin', headers: { Accept: 'application/json' }
});
if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) {
  throw new Error('Inspect status and safe error before parsing');
}
const profile = await response.json(); // Keep private; not authentication proof for another site.
```

## F03 Registration and two-step recovery

1. Obtain CSRF state, then call `issueCaptcha` with REGISTER. Pass the returned parameters/signature unchanged to ALTCHA v2; do not manufacture a proof payload.
2. `register` sends RegistrationRequest and a UUID key. On 201 privately save the one-time codes/user ID; replay 200 acknowledges the existing account without codes. Log in separately via U02.
3. `startRecovery` sends the current name, credential and RECOVERY_CODE or ADMIN_ISSUED. Obtain a RECOVERY_RISK challenge if required; on 204 retain the recovery Cookie.
4. Within ten minutes, `completeRecovery` sends PUT newPassword using the same Cookie jar/CSRF. Its 204 clears the recovery Cookie. Step-one validation already consumed the credential; it is not generic retry material.
5. Uncertain results follow U01/U08, not plaintext-retrieval requests.

**Expected:** Outcome known, codes/password private. **Failure:** Fix new-password400, expired-session401 needs another credential/new start, risk CAPTCHA obtains a fresh challenge without unbounded retries.

## F04 Account read/mutate/read-back directory

**Expected:** The selected U procedure's status and read-back agree; one-time output is privately retained and session-ending actions require fresh login.

**Failure:** Follow F02/U error handling; inspect current state before retrying an uncertain write. An ended session cannot verify its own continuation.

**Order:** F02 normal Cookie/write guard; select user procedure below, schema in OpenAPI. Read first, mutate once, read back; one-time secret retained privately.

1. Send the relevant GET first and verify identity/target state, normal session and current version/deadline.
2. Follow the selected U procedure to confirm scope, use only documented fields and F02 CSRF/origin/password/key conditions.
3. Submit once, inspect the specific 200/204 or safe error, preserve one-time output privately and read back the committed state.
4. Session-ending operations use a fresh login or audit to verify the outcome; an ended Cookie or timeout must not trigger blind repetition.

| operationId | Request and procedure | Verification |
| --- | --- | --- |
| getProfile / updateProfile | GET/PATCH profile, U04 | 200 Profile/new display name/stable UUID |
| changeUsername | POST newUsername/currentPassword, U05 | 200 Profile/new name/deadline/unchanged UUID |
| changePassword | POST current or temporary password + newPassword, U03/U06 | 204/new login, old session unusable |
| getRecoveryCodeStatus / rotateRecoveryCodes | GET counts, F02 evidence then POST, U07 | 200 counts/new one-time batch |
| listSessions / revokeSession | GET then sid DELETE, U09 | 204/empty404; current sid global scope |
| logoutAll | POST key, U09 | 204/all-user SSO login ends |
| listAuthorizations / revokeUserClientAuthorizations | GET grouped client then DELETE, U10 | Client removed, other clients independent |
| getDeletionRequest / requestDeletion / cancelDeletion | GET then POST/DELETE password+key, U11 | 200 pending or204 canceled/read-back |

Restricted required-change/deletion Cookie is not permission for all normal GETs. Password/logout verification cannot continue with an ended jar; use a fresh login/audit as appropriate.

## F05 Administrator read/write directory

**Expected:** Exact target/state/configVersion and audit match the selected A action; any one-time output is delivered privately.

**Failure:** A 403 checks the administrator's own session/evidence/CSRF/origin. Read target/audit on uncertainty; do not broaden or duplicate the action to obtain success.

1. Use your own administrator Cookie and F02 password evidence, never the target user's password.
2. Read the exact target/state/version and record the intended scope.
3. Supply the full schema, reason and applicable key, then send once. Do not parse an empty 204; inspect a 200 for version or one-time output.
4. Read back and correlate via A10. An unknown submission does not authorize repeating or broadening the mutation.

| operationId | Procedure | Specific condition |
| --- | --- | --- |
| listUsers / getUser | A01 | UUID cursor, deleted nullable credentials |
| freezeUser / unfreezeUser | A02 | reason+key,204/no resurrection |
| revokeUserSessions | A03 | User-wide scope,204 |
| issueManualRecovery | A04 | Verified claimant, reason+key,200/one-time30min |
| listClients / getClient / createClient | A05 | Create no-key guarantee,200 secret once |
| updateClient | A06 | Full settings PATCH,200 count/version |
| disableClient / enableClient | A07 | 204/no restoration |
| rotateClientSecret | A08 | reason+key,200/replay null secret |
| listDeletionRequests / holdDeletion / releaseDeletion | A09 | future≤90days/no due-time reset |
| listAuditEvents / listSecurityEvents | A10 | Read filters/cursor/offset times |

## F06 Internal eligibility and pagination

**Prerequisites:** Active FIRST_PARTY backend Basic; human Cookie does not substitute. Private credentials supplied through trusted tool/config; GET is not browser-CSRF flow.

1. The `getServiceAccess` subject comes from validated issuer/sub, not a browser-asserted username.
2. Verify a 200 JSON response with exactly seven fields and matching issuer/subject/epoch/version/reason; changedAt may be null.
3. DENIED is authoritative. A 401, 429, 503, network error or malformed body is unavailable, not a fabricated ban or allow.
4. Implement the consumer's one-minute observation and generation/epoch reconciliation rules. The query does not clear business caches itself.
5. `listUsers`, `listClients`, `listDeletionRequests`, `listAuditEvents` and `listSecurityEvents` retain filters and use the returned cursor unchanged. Limit defaults to 50 and is clamped to 1–200; a new filter starts without a cursor.

**Expected:** Eligibility only, no membership/roles. **Failure:** Do not synthesize ALLOWED using Bearer/fixture/missing epoch.

## F07 First OIDC login and controlled reauthentication

**Location: supported BFF backend and two isolated browsers, not admin-cookie API jar.** Detailed installation/registration in [integration](external-integration.md).

1. Call `oidcDiscovery` or `oauthMetadata` through the trusted issuer. The library obtains keys through `getJwks`, not an arbitrary Token-provided jku.
2. The BFF creates session-bound one-time state/nonce/verifier and sends the browser through `authorizeCode` GET. `authorizeForm` is framework POST/consent handling; initial integration prefers the verified GET route.
3. `loginPage`/`passwordLogin` collect passwords only at SSO, with risk CAPTCHA as required. Forced reauthentication creates a new BFF transaction with prompt=login/max_age=0; SSO `oidcPasswordPage`/`verifyOidcPassword` handle the five-minute challenge.
4. The callback validates state/session/one-time transaction. `exchangeOrRefresh` with authorization_code supplies code, original redirect, verifier and confidential-client Basic.
5. The library checks ID Token signature/algorithm/iss/aud/time/nonce; `getUserInfo`/`postUserInfo` use the AT and require matching sub. Tokens remain backend-only; the browser gets a local Cookie.
6. Store/register/index before local success. Signed auth_time belongs to the initiating session and its exact fifteen-minute evidence window; the callback does not execute a pending business command.

**Expected:** Local safe session, not implicit business membership. **Failure:** New top-level transaction after invalid callback/code/state; no old-code replay. Wrong password401 gives no fresh evidence; expired challenge restarts from BFF.

## F08 Refresh, revocation, introspection and logout

1. Before `exchangeOrRefresh` with refresh_token, the consumer commits its local intent and single-flights per session. A new verified Token needs successful persistent CAS before protected use under this durable consumer strategy.
2. Clear local login on definite invalid_grant. Lost response/crash before persistence remains unknown: refuse local access, retain accepted known work and do not deliberately replay A.
3. `revokeToken` POST sends the known Token, optional hint and client Basic. Its 200 includes unknown/invalid tokens; record the protocol result, **not old-A successor-family-cleanup proof**.
4. `introspectToken` is an authenticated stored-token query returning active/false, not B recovery, eligibility or fixed family-deadline proof. Inactive A does not imply B is absent.
5. `ssoBrowserLogout` POST /logout has all-user SSO scope. A BFF's current logout is its own /api/auth/logout. `oidcLogout`/`oidcLogoutForm` use trusted end_session, original hint, exact return and state for global browser logout.
6. End local login before remote work; task acceptance, HTTP 200 and family cleanup are distinct outcomes. Explicit risk assessment may accept unknown residual grants; historical compensation is not automatically mandatory.

**Expected:** Ended/uncertain local login never resurrects. **Failure:** Provider outage retains failure/queued work, not old-login restoration or automatic broader user-wide revoke.

## F09 Private health and metrics

**Expected:** Private liveness/readiness and collector authentication have the documented statuses, with a fresh successful snapshot.

**Failure:** Diagnose listener/configuration/credentials/DB through M01/T01; preserve private management boundaries and distinguish liveness from readiness.

1. GET `liveness`/`readiness` on the correct private management listener: Compose 9091, not the public proxy. Readiness includes DB and may wait for its connection timeout before 503; liveness is not database readiness.
2. `metrics` uses independent collector Basic/GET, 401 versus 200. Use protected configuration, not a password URL/argument; see [monitoring](monitoring.md).
3. After scrape 200, check snapshot availability/last_success. Old gauges are not current health evidence.
4. Disabled monitoring or wrong-port 404 calls for configuration repair, not exposing actuator publicly.

## F10 Manual verification record

**Expected:** The record links actual steps/status/read-back to an exact version and target, with unexecuted checks and limits explicit.

**Failure:** Missing evidence remains unverified. Retain the failed category for targeted correction rather than changing the expected outcome.

1. For each action record version/hash/isolated target/actor/prerequisites/steps/expected vs actual/status/code/requestId/read-back/audit references.
2. Separate source/unit/syntax/fixture checks from real HTTP/browser; unexecuted stays unexecuted, OpenAPI alone is not acceptance.
3. No input/output secrets. Cleanup uses explicit account/client operations rather than SQL deletion of permanent facts.
4. Actual failure keeps protected access closed and gets targeted diagnosis/remediation, not disabling protocol/business gates.
