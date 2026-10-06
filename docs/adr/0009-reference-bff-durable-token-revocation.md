# Reference BFF durable encrypted Token revocation

Status: user explicitly selected reference-BFF verification on2026-10-05; implemented and PostgreSQL/HTTP/real-SSO process-restart verified. Consumer production adapters remain separate.

[Chinese](../zh-CN/adr/0009-reference-bff-durable-token-revocation.md).

## Context

Starter `LasSsoRevocationTaskSink` is a public work-submission boundary; the default executor queue loses tasks on process exit. Current-app logout removes local authorization and ends the Session before submitting its owning Token snapshot. This order must remain; a returned submission must not falsely claim OP revocation.

## Decision

1. Add reference-only `durable-revocation` profile implementing the existing sink. Default starter and default BFF remain database-independent. No LASLOG table or reusable JDBC library is added.
2. Use an explicitly configured separate PostgreSQL database, reference_revocation schema, packaged reference migration V1. Profile startup migrates/checks it; missing DB/encryption settings fail startup, never fall back to memory. Production migration privileges/separation remain deployment decisions.
3. Commit task acceptance in REQUIRES_NEW before normal submit return. Encrypt Token with standard JCE AES-256-GCM, random96-bit nonce/128-bit tag; bind length-prefixed taskId/registration/issuer/client/endpoint/hint/keyId as AAD. No Client Secret is persisted. Current trusted registration supplies the secret at delivery; issuer/client/endpoint snapshots must match. Only HTTPS is accepted, except explicit development option for literal loopbackHTTP.
4. Each worker claims one due task with PostgreSQL SKIP LOCKED and unique claim token/two-minute lease, incrementing attempts on claim before HTTP. Current DB time controls due/lease checks. Lease expiry permits a new owner; old/expired owners cannot update completion/retry. Claim at most10 serial tasks per five-second poll, immediately before each HTTP request.
5. POST form+Basic uses three-second connect/five-second read timeouts, no redirect following, exactHTTP200 acknowledgement. Failure persists categorical error and randomized exponential retry; at most8 claims. Exhaustion/dead letter is durable and never automatically retried; an eighth abandoned claim becomes LEASE_EXHAUSTED after expiry.
6. HTTP200 then failed receipt commit may cause a repeated RFC7009 request after lease recovery. Delivery is at-least-once within the retry budget, not exactly-once. Successful completion erases ciphertext/nonce; dead letters retain encrypted material for explicit operator recovery.
7. Keep local-logout-first order. Failed submission is reported by exception and local logout remains complete. Crash before committed submission can still lose work; this reference does not provide cross-store atomic logout/outbox, consumer session persistence or auto-replay of business actions.

## Operational consequences

One explicit keyId/key is supported, not a multi-key rotation service. Keep it across BFF restarts and separately protect/backup it; changing it with pending/dead tasks causes sanitized PAYLOAD_UNAVAILABLE and eventual dead letter. Drain/recover tasks before changing key, or separately design authenticated re-encryption. Keep endpoint/client snapshots compatible; secret rotation alone is supported when current registration has the same identity/endpoint. Snapshot mismatch is CONFIGURATION_CHANGED, with no Token sent elsewhere.

Reference classes are package-local under the sample application, not starter API. JDBC/Flyway/PostgreSQL are added only to the reference module; SSO V1–V14 and starter locks/API are unchanged. Completion/dead-letter retention/monitoring/manual recovery and database privileges/pool/sizing/TLS are operator-owned; automatic record cleanup or dead-letter replay is not provided. Concurrent claim tests prove PostgreSQL ownership, not full multi-instance BFF Session/refresh/index/replay support. Repeated delivery can occur after process pauses or network ambiguity; fencing only prevents stale database acknowledgements, not an already-started external request.

## Evidence

Reference BFF37 tests:34 actual PostgreSQL/AES-GCM/HTTP cases and3 configuration cases. They cover committed acceptance/outer rollback/SQL failures, status matrix/no redirects/retry budget, lease/owner recovery, HTTP200/receipt-failure replay, encrypted metadata/task substitution, wrong/missing key, registration changes/current-secret use, local logout on failed acceptance, default/profile behavior and missing-setting startup failure. Real Edge flows in default/application-owned security modes submit an encrypted task with worker paused, stop the BFF JVM, restart with the same DB/key, verify completion and actual SSO invalidated Token state/refresh invalid_grant, while another sid remains independent. No Token/key/client secret was printed. Scope/provenance: [delivery .4](../integration/development-delivery-2026-10-05-4.md).
