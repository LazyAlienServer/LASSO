# Require execution receipts from Logout replay adapters

Status: accepted and explicitly confirmed by the user on 2026-10-04; default single-JVM implementation verified, durable adapter acceptance pending.

Chinese counterpart: [退出重放 adapter 必须提供执行回执](../zh-CN/adr/0003-logout-replay-execution-receipts.md).

## Context

`LasSsoLogoutReplayStore.accept` only bound an issuer/jti to a digest. It could not distinguish pending execution from successful execution. Identical retries re-ran session invalidation, failures could be mistaken for malformed Tokens, and removing a session index before successful invalidation could make a failed operation appear complete on retry. The LAS contract requires a cached success for identical completed replays, rejection of conflicting content, and retryable incomplete execution.

## Decision

Add default method `process(issuer, tokenId, contentDigest, retainUntil, action)` returning `SUCCESS`, `CONFLICT` or `IN_PROGRESS`. Keep `accept` unchanged for source compatibility; it is not an execution receipt and the receiver no longer calls it.

- Default `process` fails closed with `UnsupportedOperationException`. An accept-only custom adapter remains compilable but reception returns no-store **503**, without invoking session invalidation, until its adapter implements the new contract.
- Reserve each issuer/jti and digest atomically before execution. Identical in-progress requests return retryable 503; conflicts return 400. Mark success only after the action completes. Completed identical replays return cached 204 without executing again.
- Failed execution releases the active reservation, retains its digest, and allows retry of identical content. Session adapters must retain indexes for still-live sessions after failure and make retries idempotent, including partial success.
- Keep digest and successful receipt through `exp + 60 seconds`, matching the accepted clock-skew window; still validate the Token before consulting a receipt. Expired Tokens do not revive because a receipt exists.
- The default implementation is process-local. Do not evict a running entry solely because its retention time passed. No new shared schema, lease duration or crash-recovery policy is prescribed by this change.

## Considered options

- Wrap legacy accept adapters in a separate in-memory completion layer: not selected; this would preserve operational compatibility only within one JVM and add a second state owner while durable completion was still unresolved.
- Silently treat accept as success: rejected; binding a digest does not establish that sessions were invalidated.
- Remove accept or replace the interface entirely: not selected; the additive method gives consumers a clear compile-compatible upgrade path and explicit fail-closed runtime behavior.

## Consequences and acceptance

Subsequent B2 default sid-marker ordering is defined by [ADR-0008](0008-retain-sid-revocations-before-local-registration.md); it is separate from the short issuer/jti execution receipt and does not add durability to this ADR.

This is an intentional runtime compatibility change approved by the user. Consumers upgrade custom adapters using the [current integration contract](../en/integration.md). Fixed issuer configuration, first-party sid and strict short Token lifetime remain required; custom decoders are responsible for cryptographic/raw-claim validation.

Default atomic reservation/receipt and receiver behavior have deterministic failure/concurrency tests and real signed Token/JWKS negative tests. Durable adapters own transaction boundaries, leases, independent session-store effects and restart/multi-instance acceptance; `process` alone does not provide a distributed exactly-once transaction. Logout-before-registration ordering remains separately tracked under B2. No business database, release or deployment is authorized by this ADR.
