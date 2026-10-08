# ADR-0015: Runtime History Retention And Bounded Cleanup

- Status: user-approved P05 policy, 2026-10-08
- [中文](../zh-CN/adr/0015-runtime-history-retention.md)

## Decision

Keep immutable identity tombstones, reserved usernames, deletion facts, consent, administrator audit and security events. No automatic deletion/archival of those facts in P05; online growth and a verified archive remain operator work.

Default terminal session and used/revoked ordinary/manual credential digest retention is90 days, with a minimum90-day configuration. Sessions expire independently of whether the browser returns; purging never makes an old sid register as new. Valid credentials are not deleted. Manual credential expiry audit remains the existing job's responsibility, and inactive manual rows are purged only after its revocation/use and expiry are old enough.

Authorization is reclaimed only after all stored Token deadlines and any recorded absolute RT-family deadline have been past for30 days (minimum30). Missing/unknown Token expiry or family deadline fails closed for reclamation. No-Token initial authorizations without a safe deadline remain protected for investigation. Deletion uses the existing RT revocation trigger and hint/security cascade. REVOKED RT hashes can be removed only without a surviving authorization and when both Token expiry and revocation are older than30 days. CURRENT/ROTATED histories are not standalone expiry-cleaned; live reuse detection remains authoritative.

Keep every issued ID Token hint association for a surviving authorization; no independent TTL/count cap. Successful supported SSO outbox rows can be deleted after30 days only with no claim/dead state. Pending, leased, dead and unsupported event records are never discarded by this routine. P03 reference queue retention is separate and reused.

Cleanup is finite-batch, transaction/statement/lock-time bounded, skips busy candidates and rechecks expiry predicates. Authorization child fanout is measured and protected by timeouts rather than claimed to be a fixed row bound; more than10000 RT hashes in one authorization is skipped/reported, not loaded unboundedly. Lock RT history before authorization to respect refresh ordering, and release partial candidate locks if any child/parent is busy. Failed phases roll back; no success counter before commit. Short-lived state retains existing expiry/retry policy but now avoids waiting on busy rows. Fixed low-cardinality cleanup metrics expose completed/skipped runs, changed rows, duration and failures without identity or Token labels. Monitoring outbox snapshots exclude completed rows rather than scanning the accumulated success archive. Live hint/RT histories, unknown-expiry records and oversized families remain deliberately retained and require measured capacity/operator follow-up.

## Consequences

V16 adds measured cleanup/query indexes; existing migrations remain unchanged. Physical table shrinking is PostgreSQL vacuum/maintenance, not DELETE alone. Timeout/repeated fanout failures are operator signals; do not enlarge batches blindly or remove history from live authorizations. Independent JWT acceptance and consumer expiry/reconciliation remain separate. Pepper replacement must invalidate/reconcile affected authorizations/challenges rather than continue unrecognized old HMAC history; same-key restart is not key rotation. Target scale/retention throughput and audit archive are still capacity/operations inputs.
