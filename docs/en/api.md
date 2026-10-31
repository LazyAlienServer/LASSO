# Implemented API

[Index](README.md) · [中文](../zh-CN/api.md) · [OpenAPI3.1](../../api/openapi.json)

OpenAPI is the route/field/status reference for the implemented server. Import it into a trusted local viewer/tool and supply the canonical issuer. There is no runtime Swagger UI or `/v3/api-docs`. Discovery is authoritative for OAuth/OIDC URLs.

## Authentication

| Surface | Identity / conditions |
| --- | --- |
| Registration/CAPTCHA/recovery start | Anonymous; CSRF and same-origin writes |
| `/api/v1/me/**` | Current SSO browser Cookie; account/session restrictions apply |
| `/api/v1/admin/**` | SSO admin Cookie; writes require current DB role/session, recent password, CSRF/origin/reason |
| Internal service-access | ACTIVE FIRST_PARTY Basic; no human Cookie/Bearer substitute |
| Token/revoke/introspect | Confidential-client authentication and protocol parameters |
| UserInfo | Valid openid Access Token; neither ID Token nor Cookie |
| Metrics | Independent collector Basic on private management listener |

SSO uses session-based CSRF, not an assumed separate CSRF Cookie. GET `/api/v1/csrf` with the same Cookie jar, retain headerName/parameterName/token and send its header on writes. Match canonical Origin or same-origin Referer. Reauthenticate via POST `/api/v1/me/reauthentication`; after204 retain the new session cookie, refresh CSRF and submit the intended action separately.

## Request and result handling

- JSON successes are direct DTOs/lists; 204 is empty. Application JSON uses UTF-8: invalid bytes, UTF-16/32 and an explicitly different charset are rejected. Unknown fields, number/boolean-to-string coercion and null elements in client configuration lists are rejected with 400.
- Servlet response encoding is set before security filters write: `spring.servlet.encoding.charset=UTF-8` and `spring.servlet.encoding.force-response=true` (Boot 4 property names). This also covers the framework JWKS endpoint; JSON Content-Type has no charset or declares UTF-8, never ISO-8859-1. OAuth/OIDC response bodies remain unwrapped.
- Time uses RFC3339 offset strings. Labels such as lastUsedLabel are display text, not timestamps.
- `X-Request-Id` covers application processing and early security refusals; the same value is in application error bodies. It is correlation, not authorization/idempotency. Requests rejected by the HTTP server before Servlet processing may have only the container response. Do not log authentication bodies/headers.
- Registration uses UUID Idempotency-Key. Other covered writes use a nonblank key≤128 characters, actor+operation/body scope,24hours. Same-key retries still require authentication/CSRF; replay never redisplays secrets.
- Covered writes: logout-all, deletion request/cancel, admin freeze/unfreeze/revoke-sessions/manual recovery, client-secret rotation. Client create/edit, code rotation and deletion hold/release have no such guarantee.
- After timeout query state/audit before retrying a write. One-time output loss requires an explicit new generation/reissue/rotation.

| Surface | Response contract |
| --- | --- |
| Application/internal API errors | `application/problem+json`, required type/title/status/code/requestId; optional detail/instance/availableAt; no-store |
| Session/change-required/deletion/CSRF/role checks | Same Problem contract; API 401/403 never redirects to HTML login |
| Password-change errors | Same Problem contract; wrong current password remains 401 |
| Internal Basic | Same Problem contract, 401 retains WWW-Authenticate with `charset="UTF-8"`; 429 retains Retry-After |
| Browser pages and password login | HTML, redirects or text 429/Retry-After |
| OAuth/OIDC, health and metrics | Their native protocol/media formats; never business-wrapped |

Clients branch on HTTP status and stable code, not localized title/detail. Unhandled application failures return a safe generic Problem without exception text. Servlet 404/405/415 use the same contract and preserve headers such as Allow. Basic credentials on the internal query are Base64 of UTF-8 `clientId:secret`, with malformed UTF-8 rejected; OAuth client_secret_basic retains its own form-encoding rules. ASCII-only IDs/codes and binary signatures keep their defined byte formats.

## Application groups

| Group | Procedure / important detail |
| --- | --- |
| Register/recover |[Users](users.md); recovery password uses **PUT**, credential consumed before ten-minute step2 |
| Profile/username/password/codes |[Users](users.md); stable subject, case-sensitive historical reservation, code output once |
| Devices/authorizations/logout |[Users](users.md); current-sid revocation is user-wide SSO logout |
| Deletion |[Users](users.md); DELETE cancellation has JSON password body; HELD is restricted too |
| User/admin/client/audit operations |[Administrators](administrators.md); client creation200, PATCH full settings, replayed secret null |
| Protocol integration |[Integration](integration.md); backend Tokens and separate business authorization |
| Offline bootstrap/reset/epoch |[Operations](operations.md), [recovery](recovery.md); not HTTP admin endpoints |

Registration Location does not establish a GET `/api/v1/users/{id}`. There is no generic `/api/v1/me` summary or avatar upload. Display name is trimmed1–64 code points; avatar is null or default-alien-01. Login form field is **rememberDevice**.

## Internal eligibility

GET `/internal/v1/principals/{UUID}/service-access`, FIRST_PARTY Basic, no-store. Exact seven fields: issuer/subject/decision/reason/stateEpoch/stateVersion/changedAt. Unknown subject returns200 DENIED/PRINCIPAL_NOT_FOUND, version0, null changedAt. Epoch/storage failure is503; auth/rate/network faults are unavailable, not an invented account ban or ALLOWED.

Compare versions only in one issuer/subject/epoch. Consumers enforce one-minute observation and local generation/cache barriers; the endpoint does not create business roles or an event stream.

## Paging and protocol limits

List limits default50, clamped1–200; user cursor is UUID, other admin cursors are returned time/ID encodings, not signed capabilities. Keep filters; restart without cursor for a new filter. Current client status default is ACTIVE.

AT defaults15minutes, family14days fixed; refresh never advances auth_time. Revocation200 includes unknown tokens and does not prove unknown successors are gone. Introspection describes stored-token status, not eligibility or absolute-family-deadline proof. SSO `/logout` and `/connect/logout` are user-wide; BFF local logout is separate.

API drift tests compare implemented MVC routes and marked DTOs to OpenAPI. This is not whole-protocol certification or consumer acceptance.

Discovery advertises openid/profile, authorization_code/refresh_token and OIDC Back-Channel Logout with sid support. Root/non-root Servlet HTTP regressions check actual security/MVC error paths, UTF-8 and invalid input, in addition to schema/route drift checks.
