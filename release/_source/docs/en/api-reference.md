# API reference — {{VERSION}}

[Index](README.md) · [中文](../zh-CN/api-reference.md) · **[OpenAPI 3.1](../../api/openapi.json)** · [Integration tutorial](external-integration.md)

**How to call each operation:** [API F01–F10](api-operations.md) covers every operationId; browser/operator steps are in the [workflow index](operation-index.md).

## 1. Scope and reading order

This is the implemented server reference. The packaged OpenAPI describes JSON request/response fields, required headers, status codes, protocol endpoints and authentication. Import the JSON into a local OpenAPI viewer or client tool; configure the trusted issuer yourself. The server does not install Swagger UI or `/v3/api-docs`.

1. Read authentication/errors below.
2. Select an endpoint group; inspect its named schema in OpenAPI.
3. For business-site integration, use Code + PKCE and the [integration tutorial](external-integration.md). Account/admin APIs are for the SSO's own browser session, not a BFF's user Access Token.

The inventory covers implemented account, administrator and internal eligibility routes. OAuth endpoints are framework-owned: Discovery is authoritative for URLs. This is not a claim of OpenID certification or every optional flow. Browser HTML pages, static assets and offline maintenance are described separately, not invented JSON endpoints.

## 2. Authentication, CSRF and identity

| Surface | Authentication | Additional conditions |
| --- | --- | --- |
| Registration, CAPTCHA, recovery start | Anonymous permitted | CSRF and issuer-origin write |
| `/api/v1/me/**` | SSO browser cookie | Current credentials/session, no applicable restriction |
| `/api/v1/admin/**` reads | SSO administrator browser login | `ROLE_SSO_ADMIN` |
| Administrator writes | Same administrator | Database-backed current admin session, password evidence less than 15 minutes old, CSRF, origin and reason |
| `/internal/v1/principals/{subject}/service-access` | Active FIRST_PARTY client Basic | Independent machine query; neither cookie nor user Bearer substitutes |
| Token/revocation endpoint | Confidential OIDC client Basic | Exact owning client, registered grant |
| `/userinfo` | Active Access Token, `openid` | Browser cookie and ID Token are insufficient |
| Prometheus | Independent collector Basic | Private management listener; monitoring profile |

Production SSO Cookie is `__Host-las_sso` (`Secure`, `HttpOnly`, `Lax`, `Path=/`, no Domain). Recovery Cookie is `__Host-las_recovery` (`Secure`, `HttpOnly`, `Strict`, ten minutes). Do not assume a separate CSRF cookie exists: the current implementation uses Spring's session-based CSRF state. Obtain `headerName`, `parameterName`, `token` from `GET /api/v1/csrf`; keep the same cookie jar. Use the returned token/name on writes. After login/session changes, obtain fresh CSRF state.

Application writes require canonical issuer `Origin`, or same-origin `Referer` when Origin is absent. CSRF is a separate check. `/oauth2/reauthenticate` is an application password form and is **not** CSRF exempt. Browser/API CORS is not opened for arbitrary sites.

Use `(issuer, sub)` as identity; `sub` is immutable UUID, not mutable username. SSO does not grant LASLOG roles, membership, invitations, Minecraft links or bans.

## 3. Formats, errors and retry

- JSON success bodies are the returned DTO/list directly; no universal envelope. Timestamps are offset/RFC3339 strings; display labels such as `firstAuthorizedLabel` are not timestamps.
- Unknown JSON request fields are rejected. UUID path/header binding and validation can produce framework errors before controller logic.
- `X-Request-Id` is supplied by the server; a parseable short UUID request header may be retained. Do not use it as authorization/idempotency proof.
- Sensitive responses generally use `no-store`; do not infer every error/cache header from that convention.

| Source | Actual response |
| --- | --- |
| MVC application failures | `application/problem+json`: `type`, `title`, `status`, `detail`, `code`, `requestId`, optional `instance`/`availableAt` |
| Expired account session filter | `401 application/json` with `{"code":"SESSION_EXPIRED"}` |
| Required password-change filter | `403 application/json` with `{"code":"PASSWORD_CHANGE_REQUIRED"}` |
| Deletion restriction filter | `403 application/json` with `{"code":"DELETION_PENDING"}` even for HELD |
| Password-change local errors | `400/401 application/json {code}`; wrong current password is 401 |
| Anonymous browser authentication | Can redirect to `/login`; Accept/status alone does not make every failure Problem JSON |
| Internal Basic rejected | Empty 401 and `WWW-Authenticate`; budget 429/storage 503 are safe Problem JSON |
| Login password/CAPTCHA failure | Redirect to `/login?error` or `/login?captcha`; limited requests are 429 text with `Retry-After` |
| OAuth/OIDC | Protocol errors/redirects/Bearer challenges; never wrap them in application JSON |

Application codes include: `VALIDATION_FAILED`400, `CAPTCHA_INVALID`400, `RECOVERY_CREDENTIAL_INVALID`400, `CAPTCHA_REQUIRED`403, `REAUTHENTICATION_FAILED`401, `RECENT_AUTHENTICATION_REQUIRED`403, `REAUTHENTICATION_CHALLENGE_EXPIRED`401, `CSRF_INVALID`403, `USERNAME_UNAVAILABLE`409, `USERNAME_CHANGE_COOLDOWN`409 (with `availableAt`), `IDEMPOTENCY_KEY_REUSED`409, `IDENTITY_SERVICE_UNAVAILABLE`503, `RATE_LIMITED`429. Deleted/admin/self targets and client settings have their own 400/403/404/409 codes; see OpenAPI and the implementation's guard results. `type` currently uses a fixed documentation URI base; it is not the configured issuer.

Idempotency: registration uses a UUID key; logout-all, deletion request/cancel, admin freeze/unfreeze/revoke-sessions/recovery issuance and client-secret rotation use a nonblank key up to 128 characters. Scope is actor + operation, 24 hours, and differing request material conflicts. Replay still requires valid authentication/CSRF. One-time secrets are never replayed. Client creation/update, recovery-code rotation and deletion hold/release do not have this idempotency guarantee.

A timeout means result uncertainty. Do not blindly repeat secret creation/rotation, password operations or refresh. Never log passwords, recovery codes, tokens, Cookies, client secrets or full request/response captures.

## 4. Account endpoint inventory

All bodies below are JSON except the login/form routes in §7. “Key” means `Idempotency-Key`; all application mutations also need CSRF/origin.

| Method/path | Body / parameters | Success schema / effect |
| --- | --- | --- |
| GET `/api/v1/csrf` | None | 200 `CsrfResponse` |
| POST `/api/v1/captcha/challenges` | `ChallengeRequest`: purpose REGISTER/LOGIN_RISK/RECOVERY_RISK | 201 `IssuedChallenge`, ALTCHA v2 payload |
| POST `/api/v1/registrations` | `RegistrationRequest`: username/displayName/password/captcha; UUID Key | 201 `RegistrationResponse`; replay200, no codes; no login |
| POST `/api/v1/recovery/sessions` | username/credential/credentialType (`RECOVERY_CODE` or `ADMIN_ISSUED`), optional captcha | 204, sets restricted recovery cookie |
| PUT `/api/v1/recovery/password` | `newPassword`, recovery cookie | 204, clears cookie; invalidates old credentials/login/grants |
| GET `/api/v1/me/profile` | None | 200 `Profile` |
| PATCH `/api/v1/me/profile` | displayName, optional avatarResourceId | 200 `Profile` |
| POST `/api/v1/me/username-change` | newUsername/currentPassword | 200 `Profile`; UUID unchanged, 90-day cooldown |
| POST `/api/v1/me/password-change` | currentPassword **or** temporaryPassword, newPassword | 204, ends sessions/grants; log in again |
| POST `/api/v1/me/reauthentication` | password | 204, current-session password evidence/session-ID rotation |
| GET `/api/v1/me/recovery-codes/status` | None | 200 `RecoveryCodeStatus`, counts only |
| POST `/api/v1/me/recovery-codes/rotate` | No body; recent password | 200 `RecoveryCodeRotation`, new one-time batch |
| GET `/api/v1/me/sessions` | None | 200 array of `SessionView` |
| DELETE `/api/v1/me/sessions/{sid}` | sid | 204/empty404; **current sid triggers all-user-session logout** |
| POST `/api/v1/me/logout-all` | Key, no body | 204, user-wide sessions/grants ended |
| GET `/api/v1/me/authorizations` | None | 200 array of `AuthorizationView`, grouped by client |
| DELETE `/api/v1/me/authorizations/{clientId}` | clientId | 204, all this user's grants/consent for that client |
| GET `/api/v1/me/deletion-request` | None | 200 `DeletionStatus` |
| POST `/api/v1/me/deletion-request` | password + Key | 200 `DeletionStatus`, pending30days |
| DELETE `/api/v1/me/deletion-request` | JSON password + Key | 204, cancel pending/held |

Username: case-sensitive `[A-Za-z0-9_]{3,24}`, current/historical reservations. Password:12–128 Unicode code points, ≤512 UTF-8 bytes; not equal to username or bundled common-password entries; preserve spaces. Profile displayName is trimmed/nonblank, ≤64 code points; current avatar choices are null or `default-alien-01`. Registration Bean Validation separately bounds displayName to64 UTF-16 units. Do not invent upload/avatar-management routes.

Example response (non-secret):

```json
{"username":"ExampleUser","displayName":"Example","avatarUrl":null,"usernameChangeAvailableAt":null}
```

Required-change login is restricted to password-change/logout and designated pages; deletion pending/held login is restricted to status/cancel/logout. Do not assume all `/me` routes are accessible merely because a Cookie exists. Creating an administrator, resetting the sole administrator and restore-epoch rotation are [offline maintenance](operations.md), not HTTP admin APIs.

## 5. Administrator endpoint inventory

Read guards require administrator browser login; all writes use the stronger guard in §2. `reason` is nonblank, ≤500 characters. User actions cannot target self/administrator/deleted identities where the owning operation forbids it.

| Method/path | Request | Success |
| --- | --- | --- |
| GET `/api/v1/admin/users` | query/status/cursor(UUID)/limit | 200 `UserPage`: items/nextCursor/previousCursor |
| GET `/api/v1/admin/users/{userId}` | UUID | 200 `UserDetails`; credential fields null on tombstones |
| POST `/api/v1/admin/users/{userId}/freeze` | reason + Key | 204; revokes sessions/grants |
| POST `/api/v1/admin/users/{userId}/unfreeze` | reason + Key | 204; old grants not resurrected |
| POST `/api/v1/admin/users/{userId}/revoke-sessions` | reason + Key | 204; target-wide logout |
| POST `/api/v1/admin/users/{userId}/recovery-credentials` | reason + Key | 200 `IssuedCredential`, 30-minute one-time credential |
| GET `/api/v1/admin/clients` | query/status/cursor/limit | 200 `ClientPage`, no secret |
| GET `/api/v1/admin/clients/{clientId}` | clientId | 200 `ClientView`, no secret |
| POST `/api/v1/admin/clients` | `CreateClient` | **200** `CreatedClient`, secret once |
| PATCH `/api/v1/admin/clients/{clientId}` | `PatchClient`, full editable settings | 200 `PatchedClient`, revocationTriggered/count/version |
| POST `/api/v1/admin/clients/{clientId}/disable` | reason | 204; client-wide revocation |
| POST `/api/v1/admin/clients/{clientId}/enable` | reason | 204; no resurrection |
| POST `/api/v1/admin/clients/{clientId}/secret-rotation` | reason + Key | 200 `RotatedClient`; old secret/grants invalidated |
| GET `/api/v1/admin/deletion-requests` | status/cursor/limit | 200 `DeletionPage` |
| POST `/api/v1/admin/deletion-requests/{requestId}/hold` | reason/publicSummary/holdUntil | 204; future hold ≤90days |
| POST `/api/v1/admin/deletion-requests/{requestId}/release` | reason | 204 |
| GET `/api/v1/admin/audit-events` | action/targetId/from/to/cursor/limit | 200 `AuditPage`, whitelisted details |
| GET `/api/v1/admin/security-events` | type/severity/userId/from/to/cursor/limit | 200 `SecurityPage` |

Pagination defaults50, clamps1–200. User cursor is a UUID; the others are returned Base64url positions containing timestamp/ID. They are not signed capabilities or snapshot isolation. Keep filters stable and pass returned cursors unchanged. Time filters are RFC3339 with offsets. Status rules: clients default ACTIVE (ACTIVE/DISABLED/ALL); users ACTIVE/FROZEN/DELETED or absent all; deletions PENDING/HELD/CANCELLED/EXECUTED or absent all.

Client settings: `clientName`, `clientType` FIRST_PARTY/REVIEWED_PARTNER, `redirectUris`1–10, optional `postLogoutRedirectUris`≤10, `scopes` containing openid and optionally profile, `resourceAudiences`1–10, optional `backchannelLogoutUri`, `reason`; create optionally supplies clientId. PATCH replaces this settings set; it is not JSON Patch. Production requires reviewed HTTPS/exact URIs; no wildcards, fragment or userinfo. The HTTP local-issuer profile allows local HTTP callbacks only. A client has one active secret; rotate in a coordinated window, and preserve one-time output privately.

## 6. Internal eligibility API

`GET /internal/v1/principals/{subject}/service-access`, Basic from an active FIRST_PARTY registration. There is no OAuth client-credentials grant requirement and no business permission in this result.

```json
{"issuer":"https://auth.las.test:18443","subject":"019fb42f-5f16-7c31-8ce0-9e54f7441ff0","decision":"ALLOWED","reason":"ACTIVE","stateEpoch":"opaque-epoch","stateVersion":1,"changedAt":"2026-10-09T00:00:00Z"}
```

Exactly seven fields. Decisions ALLOWED/DENIED; reasons ACTIVE, PRINCIPAL_NOT_FOUND, ACCOUNT_DELETED, ACCOUNT_FROZEN, DELETION_PENDING, DELETION_HELD, PASSWORD_CHANGE_REQUIRED, CREDENTIALS_DISABLED, ACCOUNT_STATE_INVALID. Unknown UUID has version0/changedAt=null; missing epoch/storage is503. 401 credentials,429 budget,503 unavailable are not account-denial evidence. Compare version only within matching issuer/subject/epoch; consumer cache expiry, restart/recovery reconciliation and business enforcement remain consumer responsibilities. No lifecycle event stream or batch endpoint is implied.

## 7. OIDC, browser and management routes

| Surface | Implemented behavior |
| --- | --- |
| `/.well-known/openid-configuration`, `/oauth2/jwks` | Metadata/public keys; trust canonical issuer and verified TLS |
| `/.well-known/oauth-authorization-server` | Framework OAuth server metadata |
| `/oauth2/introspect` | Framework POST token/hint with client authentication; active stored-token metadata or active=false. Default provider is not owner-only and does not implement service eligibility or family-deadline checking |
| `/oauth2/authorize` | GET code flow; framework also handles form requests/consent; application expired-session continuation is specifically GET and must not be generalized as fully tested POST parity |
| `/oauth2/token` | POST code + verifier/exact redirect, or refresh; confidential Basic, server-only response |
| `/oauth2/revoke` | POST token/hint, client Basic;200 also unknown/invalid; **old consumed RT cannot guarantee successor-family cleanup** |
| `/userinfo` | GET/POST valid openid Access Token; verify sub matches ID Token |
| `/connect/logout` | Framework GET/POST RP-Initiated browser flow/confirmation, exact registered return URI; application performs user-wide SSO logout |
| `/login` | GET HTML, POST form username/password/rememberDevice/CSRF, risk CAPTCHA |
| `/logout` | POST CSRF SSO logout, user-wide here; not the BFF's current-app logout |
| `/oauth2/reauthenticate` | GET/POST challenge-bound password form,5min, CSRF/origin/rate limited |
| `/actuator/health/liveness`, `/actuator/health/readiness` | Private management listener in Compose; readiness includes DB, no details |
| `/actuator/prometheus` | Monitoring profile/private listener, independent Basic collector |

HTML pages: `/`, `/login`, `/register`, `/password-recovery`, `/password-change-required`, `/account`, `/account/sessions`, `/account/recovery-codes`, `/account/authorizations`, `/account/delete`, `/account/security`, `/password-change`, `/admin/users`, `/admin/clients`, `/admin/deletion-requests`, `/admin/audit`; framework consent/logout UI is protocol-owned. `/assets/**` and packaged WebJars are static resources. No implemented `/api/v1/me` session summary, avatar upload, dynamic client registration, or `/api/v1/users/{id}` GET is promised.

Defaults: code2min, AT15min, refresh family fixed14days. The family refresh deadline is not database deletion time or immediate revocation of every offline-validated JWT. Initial password auth_time remains fixed through refresh; missing evidence is not invented. Framework/default signing lifetime is authoritative rather than an unverified hardcoded ID-token lifetime from design examples.

Unknown refresh successor after lost response: consumer must fail local access safely, retain accepted known-token work, distinguish protocol200 from family-cleanup proof, and assess bounded residual authorization/AT exposure. Deliberately reusing rotated A is not a routine recovery protocol. Historical-RT revocation is an optional enhancement, not claimed implemented or an automatic V1 gate.

Back-channel is an outgoing POST to each registered consumer, with signed `logout_token` (`typ=logout+jwt`, RS256, audience client, sub and applicable sid, iat/exp/jti/events). It is not an inbound SSO controller. The Starter consumer path is `/internal/oidc/backchannel-logout`; business-site routes belong to that consuming application. Default single-JVM replay/session behavior and optional reference durable queue do not implement LASLOG storage or restart acceptance.

## 8. Release checks and supported boundaries

The source build checks documented MVC routes and Java record field names against this OpenAPI. The release verifier checks the exact API version, references and packaged file hashes. These drift checks do not replace runtime status/security tests, protocol conformance or consumer integration.

The supplied Compose blocks public `/actuator` and publishes neither DB nor application/management ports. Local fixture TLS/browser trust and Windows mounts do not establish public-CA/Linux permissions. See [acceptance](acceptance.md). This reference records existing response inconsistencies rather than silently changing API behavior or granting new scopes.
