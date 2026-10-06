# Retain sid revocations before local session registration

Status: explicitly confirmed by the user on2026-10-05; default single-JVM implementation tested, persistent/multi-instance acceptance remains separate.

[Chinese](../zh-CN/adr/0008-retain-sid-revocations-before-local-registration.md).

## Context

B2 originally registered only after the callback's redirect. Its earlier success-wrapper repair closed that window but a valid Logout arriving before or during Code exchange could find no local session and complete. The later callback then registered the already-revoked sid. Two real signed Code/PKCE/JWKS/filter-chain regressions (default and application-owned chains) failed before this change.

## Decision

- Default `LasSsoBackChannelSessionRegistry` records a sid marker on invalidation, including no-match and failed invalidation.
- Marker retention equals configured `sessionAbsoluteLifetime` (positive, at most14days, default14days) measured from each invalidation. Repeated Logout may extend, never shorten. It is distinct from short issuer/jti replay receipt retention.
- Sid marker/check and index publication/snapshot share a short registry state lock. Session/container/lifecycle code runs outside it. Registration either publishes before Logout's snapshot or is rejected after its marker; no unrelated human-subject ban is added.
- Rejected callback uses existing registration-failure cleanup/503: no application success redirect, local Token removal/session end and owning-token revocation submission. The registry itself rejects rather than invalidating in register, so cleanup can still load the Token snapshot.
- Keep public SPI signatures and the no-arg constructor. Add an optional Duration constructor; auto-configuration supplies current properties. Custom stores must coordinate register/invalidate equivalently themselves; they are not silently wrapped and their schemas are not supplied.
- Wire Logout still requires verified sid. Legacy direct subject invalidation preserves snapshot-only behavior. Scope is one configured issuer/client per default registry, with distinct non-reused SSO sids.

## Consequences

Markers are process-local and cleaned on traffic no more than once per minute, with exact per-sid expiry checks; active retained markers are never evicted to meet a cache quota. Memory grows with unique revoked sids retained within the configured period. Retention/capacity and wall-clock assumptions must be accepted for the workload. Process restart loses markers/index/receipts; the initial supported BFF mode discards local login on restart, but old in-flight callbacks across restart and arbitrary shared stores are not guaranteed by this fix.

This is not a distributed transaction, durable revocation queue, consumer business-authorization barrier or cancellation of requests already authorized. Persistent/restart/multi-instance adapters and crash acceptance remain separate. Default regressions verify before-callback and during-HTTP-exchange Logout, invalid signed input not poisoning registration, stable different-sid behavior, controlled concurrent registry orderings, partial failure/retry, reentrant cleanup and retention expiry.
