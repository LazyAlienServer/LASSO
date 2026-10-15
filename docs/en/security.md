# Security rules

[Index](README.md) · [中文](../zh-CN/security.md)

## Credentials and browser boundaries

- Passwords: 12–128 Unicode code points, at most 512 UTF-8 bytes; allow spaces, reject username/common/reused password as applicable. Default digest is Argon2id.
- Username: case-sensitive ASCII letters/digits/underscore, 3–24 characters.
- New recovery batches contain ten 80-bit codes, shown once. Rotation invalidates the old batch. Self-service recovery preserves other unused ordinary codes; normal change/promotion/reset invalidates applicable old material.
- Manual recovery is for an independently verified ordinary account. At least two independent bases, minimal record, private delivery; administrator recovery uses offline maintenance.
- SSO Cookie: `__Host-las_sso`, Secure/HttpOnly/Lax, Path=/, no Domain. Recovery Cookie: `__Host-las_recovery`, Secure/HttpOnly/Strict.
- Cookie APIs require CSRF and same-origin writes. OIDC password reauthentication is not a CSRF exception. Passwords are entered only at SSO.

## Time boundaries

| Material | Default rule |
| --- | --- |
| Authorization code | 2 minutes |
| Access Token | 15 minutes |
| Refresh family | Absolute 14 days from creation; rotation never extends it |
| Ordinary SSO session | Idle 24 hours / absolute 14 days |
| Remembered ordinary SSO session | Idle 14 days / absolute 90 days |
| Administrator SSO session | Idle 45 minutes / absolute 10 hours; remember-device does not extend it |
| Recent password evidence | Strictly less than 15 minutes, originating login/session only |
| OIDC password challenge | 5 minutes, browser/request-bound atomic completion/consumption |
| Recovery session | 10 minutes after credential consumption |
| Manual recovery credential | 30 minutes, single use |
| Bootstrap temporary password | 24 hours; mandatory first change |
| Deletion request / hold | Original 30-day due time; hold at most 90 days, release does not restart waiting |
| Starter local absolute session | At most 14 days; initial restart ends business login |

Token issue/refresh time, a dialog or successful callback alone is not new password evidence. Signed initial ID Token `auth_time` is fixed for its authorization; consumers implement their own pending-action/session correlation and expiry.

## Production configuration

Non-development requires explicit `production`; local/test cannot mix with it. Startup validates canonical HTTPS issuer/context, persistent RSA pair≥2048, legal kid, independent secrets, PostgreSQL/embedded Flyway protection, Cookie/health and unique usable administrator. Empty client tables are allowed. ACTIVE clients require reviewed URI/scope/audience/PKCE/confidential settings.

Only trusted Nginx reaches application traffic; it overwrites forwarding metadata. Management remains private. [Configuration](configuration.md) gives exact inputs and hash generation; [deployment](deployment.md) gives permissions and order.

## Revocation and recovery

Refresh reuse revokes the owner family and produces critical evidence. Foreign-client RTs must fail without mutating the owner. Lost refresh successor remains unknown: fail local access, retain accepted known-token work and assess residual grants. RFC7009 HTTP200 does not prove unknown successors are gone; deliberate reuse is not a compensation protocol. Historical-RT successor revocation remains optional/unimplemented.

Restore is a new epoch/signing-key barrier, not simply a binary rollback. Preserve permanent identity/audit facts and retire old consumer sessions/cache/evidence; [recovery](recovery.md) is the procedure.

## Data and records

Keep passwords/peppers/client secrets, keys, codes, Tokens, Cookies and queue ciphertext out of Git, release packages and logs. Retain only bounded error categories/request IDs and sanitized evidence. Live RT/hints and pending/dead work are protected from automatic pruning; terminal session/credential retention minimum 90 days, expired authorization/RT/completed outbox minimum 30 days.

V1 uses passwords without MFA/email. SSO role is not business authority. See repository ROADMAP.md for current release limitations, including the RC's inherited-image pebble findings.
