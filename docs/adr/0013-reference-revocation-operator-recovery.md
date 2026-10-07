# ADR-0013: Reference Revocation Operator Recovery And Key Draining

- Status: accepted for P03 implementation, 2026-10-07
- [中文](../zh-CN/adr/0013-reference-revocation-operator-recovery.md)

## Decision

Keep local logout first and committed durable acceptance before submit returns. Expose a safe request-scoped submission outcome (accepted/failed/no-token), never remote-success. The default memory sink must reject invalid submissions rather than silently return success. A pre-submit process crash still requires consumer compensation: independently retain a session/authorization recovery intent in consumer-owned storage, or investigate and revoke the affected OP authorization/session through supported administration. The reference queue cannot reconstruct a Token never committed to it.

Add reference-only V2 queue maintenance state/audit and an offline interactive command. Dead-task replay requires exact task UUID, expected replay generation, operator/reason and decryptable authenticated payload; it grants a new bounded eight-attempt campaign, not automatic unlimited retry. Audit and mutation commit together. Completed receipts are erased after30 days by default in bounded hourly batches (configurable1–365 days); pending/leased/dead payloads are never removed by retention. Operator audit remains retained pending a separately approved audit-retention policy.

Use a singleton active-key registry with a domain-separated HMAC proof of possession. All queue writes/claims serialize briefly on it and verify the configured key. Initialize an upgraded queue only if outstanding key IDs agree and a retained payload authenticates. Rotate only after all incomplete work, including dead tasks/leases, is drained; atomically compare old key/update registry/audit the new key. No payload re-encryption or multi-key ring is introduced. Old processes cannot submit/claim after rotation; same-ID/different-key and unreadable startup are rejected. Actual external key custody and consumer adoption remain operator/consumer responsibilities.

## Consequences

Reference V2 is separate from SSO Flyway V1–V15 and from any consumer schema. Maintenance starts a small non-Web configuration without OIDC discovery or workers. Replay never changes issuer/client/endpoint metadata; delivery rechecks trusted current registration. Cleanup never resolves a failed revocation. An unresolvable dead payload blocks rotation and requires incident handling, not SQL deletion to bypass the gate. Queue growth/dead age and retained audit still need operator monitoring/capacity policy. At-least-once RFC7009 and the pre-submit gap remain explicit.
