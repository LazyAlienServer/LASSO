# Refresh-To-Logout Hint Continuity

- Verified 2026-10-07 in [delivery .20261006.7](../integration/development-delivery-2026-10-06-7.md); [Chinese](../zh-CN/implementation/refresh-logout-hint-history.md), [ADR-0011](../adr/0011-preserve-id-token-logout-hint-association.md).
- Implementation correction for existing RP-Initiated global logout, not a new authentication or authorization protocol.

## Defect And Evidence

The exact .6 server/BFF combination passed short browser flows, but the longer short-AT mixed workload exposed a distinct failure. Spring's refresh endpoint issued a new ID Token and replaced the authorization's stored ID Token. The BFF's authenticated OidcUser retained its initial ID Token, which the global logout controller correctly used as id_token_hint. Spring's logout provider looked up only the current stored value; an unexpired, previously issued hint returned400. Local BFF logout had already ended the initiating session, while the SSO/other BFF session stayed active.

A minimized one-worker real TLS run reproduced400 after refresh with an unexpired hint no longer in the current token column. PostgreSQL/MockMvc regression `originalIdTokenHintStillLogsOutAfterRealRefreshReplacesTheStoredIdToken` then failed before the fix at the expected redirect assertion. The test uses an initial fixture ID Token followed by the actual refresh provider/issued replacement; it is not a browser/signature proof. Final hashed JARs subsequently passed both five-minute TLS browser workloads, including repeated refresh/global logout.

## Persistence And Lookup

Flyway V15 creates `oauth2_id_token_hint_history(token_hash, authorization_id, created_at)`: HMAC-SHA256 using the existing transient-security key pepper and namespace `oidc-id-token-hint`, primary key on hash, indexed authorization foreign key with ON DELETE CASCADE. No raw ID Token, principal, client secret, Cookie or extra Claims are stored there.

The existing authorization-save transaction preserves the previous current ID Token association before replacement, saves the new authorization/security/RT state, and records the new association. Duplicate saving does not append duplicate hashes. This captures the current hint for pre-V15 authorizations on their first subsequent save; already replaced hints lost before upgrade cannot be reconstructed. No attempt is made to recover those tokens from logs or retain removed authorizations.

`findByToken` first uses the original repository. Only an explicitly requested `id_token` type may fall back to HMAC history and load the still-existing owning authorization. Null/general, access-token, refresh-token and authorization-code lookup are not broadened. The original Spring logout provider still validates current authorization/client/token state, audience, principal, session association and exact post-logout URI. The digest is an association for logout, not a reusable login credential or Bearer token.

Removal of the authorization by logout, revocation, recovery/reset or account lifecycle cascades history removal. A hint cannot resurrect a removed authorization; repeat logout with the removed hint is rejected. The new regression verifies two32-byte associations after actual refresh, pre-upgrade-current capture, rejected unregistered return URI with authorization unchanged, no AT/RT lookup, and cascade/repeated-hint refusal after successful logout. RT family expiry/reuse/security-version rules remain unchanged. HMAC pepper rotation can make historical lookup unavailable; current repository lookup remains the original path. Plan rotation alongside existing session/token-state reset procedures.

History grows with distinct ID Tokens issued while the authorization remains. No independent TTL or periodic history compaction is added: removal follows the owning authorization, and production long-lived authorization cleanup/history sizing still needs measurement. The change does not implement a durable cross-instance BFF session index or protect a relying party that ignores token/state validation.

## Delivery And Limits

Final Java21/Docker build 2026-10-07 **00:07:51–00:12:17 Asia/Shanghai**: **505server+193starter+37reference=735**, zero failures/errors/skips,20tasks executed and actual PostgreSQL. Dependencies and all three locks unchanged from .6; SSO schema now V1–V15. Starter public APIs/JAR bytes unchanged; server history migration and wrapper change use a new .7 ID.

The final real acceptance also imported the exact Grafana configuration, validated desktop/mobile rendering/Viewer denial, and ran two five-minute multi-subject TLS workloads. See [Grafana/mixed-load record](../operations/grafana-mixed-load-2026-10-07.md). Earlier missing mobile panels were Grafana lazy mounting in the probe, and a post-revocation401 SESSION_EXPIRED was a permitted terminal result during an in-flight refresh, not proof of successful logout being broken. These tooling corrections remain distinct from the reproduced400 hint-continuity defect.
