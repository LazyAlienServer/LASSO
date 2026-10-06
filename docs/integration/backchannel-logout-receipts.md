# Back-channel Logout validation and execution receipts

- Status: B4 implemented/controlled verification completed on 2026-10-04; deployment/durable acceptance pending.
- Chinese counterpart: [Back-channel 退出验证与执行回执](../zh-CN/integration/backchannel-logout-receipts.md).
- Decision: [ADR-0003](../adr/0003-logout-replay-execution-receipts.md), explicitly approved additive SPI with fail-closed legacy operation.

## 1. Validate before replay or execution

The default named `lasSsoLogoutTokenDecoder` uses Spring/Nimbus with the fixed registration issuer, RSA JWKS and RS256 only. `typ` must be `logout+jwt`; invalid signature, unpublished kid, wrong algorithm, issuer or audience are rejected. Auto-configuration now requires a nonblank fixed issuer even with a custom decoder.

The receiver requires:

- nonblank string issuer/jti/sid; optional sub must be a nonblank string;
- audience containing this client's ID; no nonce;
- an events object whose logout-event value is an **empty object**;
- required iat/exp, `exp > iat`, lifetime at most 120 seconds;
- iat no later than `now + 60s` and no earlier than `now - 180s`;
- `now < exp + 60s` (exact expiry-skew boundary is rejected); the mature decoder retains nbf validation when present.

First-party sid is mandatory: sub-only Logout cannot be used to broaden invalidation. Subject need not be present when sid is present. The receiver validates every retry before looking up its receipt.

Nimbus converts a numeric sub to text before Spring claim conversion. The default decoder therefore checks original identity-claim types using Nimbus's verified SignedJWT payload API **after** its mature decoder validates the signature/issuer/type/time. It does not use homemade JWT splitting, JSON parsing or signature code. A consumer replacing the decoder must retain fixed cryptographic/raw-claim checks; receiver checks cannot recover types already coerced by a custom decoder. Its configured issuer/audience/semantic checks still apply.

## 2. Replay SPI upgrade

`LasSsoLogoutReplayStore.accept(...)` remains the existing abstract method, but it binds a digest only. The receiver calls the added method:

```java
Result process(String issuer, String tokenId, String contentDigest,
               Instant retainUntil, Runnable action);
// Result: SUCCESS, CONFLICT, IN_PROGRESS
```

The default interface method throws. **An old accept-only adapter remains source compatible but yields 503 until upgraded**, without executing sessions. This deliberate behavior is user-approved; it prevents a digest record being reported as execution success. Update the implementation rather than allowing Bean overrides or reverting to accept. `DefaultLasSsoLogoutReplayStore` implements both methods; calling accept does not create a successful receipt.

Required process semantics:

1. Key by issuer/jti, bind exact raw Token SHA-256 digest before execution; differing content returns CONFLICT even after a failure.
2. Atomically reserve one action. An identical in-progress call returns IN_PROGRESS, not SUCCESS. Different keys need not wait on this action.
3. Execute action, mark successful receipt only if it returns normally. Identical completed replay returns SUCCESS without running again.
4. On failure release the reservation but retain digest so identical retry can execute and conflicting content remains denied.
5. Retain through supplied `retainUntil = exp + 60s`; the default does not evict an active execution solely because time passes. Once inactive and expired, entries can be removed.

The default uses per-key ConcurrentHashMap atomic state and runs the action outside that map boundary. It is single-process, without a distributed transaction, lease, persistence or crash/restart guarantee. A durable adapter must implement its own correct reservation/completion/recovery transaction and idempotent session effects; successful invalidation followed by failed receipt persistence must remain safely retryable. No consumer schema/lease policy is supplied here.

## 3. Execution and HTTP response

| Outcome | Response |
| --- | --- |
| Invalid Token or conflicting content | 400 |
| Completed action or identical cached successful replay | 204 |
| Identical action still in progress | 503 |
| Replay storage, session execution, legacy unsupported method or decoder infrastructure failure | 503 |

All receiver responses use no-store. Known invalid JWT/claim errors are separated from infrastructure/execution errors. Logs contain only non-secret failure categories, no Token, claim text, SQL/adapter exception message. Actual JWKS 503 is tested as retryable 503; the custom decoder must use BadJwtException for invalid input and appropriate JwtException for availability failure.

`LasSsoBackChannelSessionStore.invalidate` must be idempotent, return only after matching indexed sessions are ended (including no-match/already-ended), and throw on incomplete execution. The default registry no longer removes the index before invalidation: successful sessions are removed; still-live failed sessions remain indexed. It attempts all selected sessions, then reports incomplete execution, so partial failures retry remaining live sessions. Already-invalid sessions are cleaned up without treating every IllegalStateException as successful invalidation.

Receipts coordinate a validated Token's execution in one JVM and do not re-execute cached success. B2's default late-registration protection now resides in the session registry's sid marker, retained for configured local-session absolute lifetime independently of jti receipt expiry; see [ADR-0008](../adr/0008-retain-sid-revocations-before-local-registration.md). Custom adapters/durable stores must supply equivalent ordering. Concurrent in-flight business requests, restart/crash and arbitrary multi-instance coordination remain separate.

## 4. Executed evidence

Original focused command ran 18 cases with 13 failures: accepted signed bad claims, array-event exception, repeated execution, adapter exception misclassified as 400 and registry failure returning 204. It ran before the fix. The first repair left only the old custom accept adapter test failing with the intended 503; that adapter test was upgraded and a separate old-adapter-fails-closed regression retained.

Final tests included in the full run:

- `LasSsoLogoutTokenValidationIntegrationTest`: **33** cases. Actual RSA signed payload, loopback HTTP JWKS, auto-configured mature decoder and actual controller. Thirty invalid variants include missing/blank/malformed claims, event structure, short-lifetime bounds, signature/kid/algorithm/issuer/audience/type/nonce/nbf; positive identical receipt and sid-only identity; real JWKS 503. The numeric-sub regression initially remained red because of Nimbus's early coercion; verified raw-type check fixed it.
- `LasSsoBackChannelLogoutControllerTest`: **15** cases. Partial/incomplete session invalidation, storage/decoder failures, conflict after failure, same-token concurrency, cached success, 60-second retention boundary, custom process adapter, accept-only compatibility and fixed issuer with custom decoder. Session/decoder fixtures are explicit at this layer; targeted log canaries are checked.
- `DefaultLasSsoLogoutReplayStoreTest`: **5** cases. Legacy digest behavior, failure/retry/receipt, conflict, active-entry retention and expiry, with deterministic latch/clock checks.
- Existing 14 signed callback and one custom-Bean-replacement test continue to pass. Latest focused execution before the final issuer assertion ran **67** cases, zero failures/errors/skips; the final full run includes a **68-case** related subset.

Full forced command in `las-sso-server/`:

```powershell
.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

On 2026-10-04 at about 01:59–02:02 Asia/Shanghai: **178 server + 117 starter**, zero failures/errors/skips, 12 actionable tasks executed; reference BFF recompiled and `test NO-SOURCE`. B4 adds 46 cases relative to 71 starter tests. No schema/dependency/lock change. Early red XML was overwritten by subsequent Gradle reports; phase evidence remains in command output and status map §3.6.

## 5. Not verified

The signed validation seam calls the actual controller, not live Servlet/MVC binding; callback tests retain their documented request/event fixtures. Real SSO/TLS/proxy/BFF/browser, consumer durable adapter, database receipts, multi-instance/crash recovery, throughput/retention capacity and notification-registration ordering have not been accepted. Existing server suites were rerun with PostgreSQL, but B4 added no persistence implementation. Changes remain uncommitted/unpublished/undeployed. See the [status map](../implementation/status-issues-and-plan-2026-10-02.md) for remaining issues and the separate B3 sender contract.
