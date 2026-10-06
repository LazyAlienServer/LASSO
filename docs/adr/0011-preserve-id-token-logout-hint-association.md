# ADR-0011: Preserve Issued ID Token Logout Hint Associations

- Status: accepted implementation correction, 2026-10-07
- [Chinese](../zh-CN/adr/0011-preserve-id-token-logout-hint-association.md)

## Context

Existing RP-Initiated Logout accepts a current/recent issued ID Token hint. Refresh replaces the server's stored ID Token while the default BFF retains its initial OidcUser ID Token. Actual TLS mixed-load and a failing PostgreSQL regression established400/logout failure after successful refresh. The existing policy is global logout; no new credential or business entitlement is requested.

## Decision

Store HMAC-only issued ID Token-to-authorization associations in V15 with a cascade foreign key. Preserve the previous current token before replacement, including the first post-upgrade save, and record the new one atomically with authorization saving. Only explicit ID-token lookup may consult this association; AT/RT/code/general lookup remain original. Keep mature logout client/audience/principal/session/exact-return validation and authorization revocation authoritative. Do not retain raw historical token strings or resurrect removed authorizations.

## Consequences

Schema migration and production history sizing/authorization cleanup are required. Older hints already discarded before upgrade cannot be reconstructed. Pepper rotation may require fresh login/reset of historical associations. This fixes protocol continuity without rewriting the BFF principal or manufacturing new password auth_time evidence. Regression red/green and final real two-mode mixed-load verification are in [implementation record](../implementation/refresh-logout-hint-history.md).
