# Independent pre-hash budget for internal Basic authentication

Status: explicitly confirmed by the user on 2026-10-04; implemented and PostgreSQL/MockMvc-tested, production capacity/transport acceptance pending.

Chinese counterpart: [内部 Basic 独立哈希前失败预算](../zh-CN/adr/0005-independent-internal-basic-failure-budget.md).

## Context

A3's internal service-access query verified client secrets without a pre-hash limiter. The existing OAuth endpoint limiter observes failures before authentication and records failures afterward; the internal path did not participate. Merely applying the 10/20 per fifteen-minute values to all queries would quickly reject valid routine eligibility queries. Merely checking failure counters before hashing allows many concurrent bad requests to pass while no failure has been recorded yet.

## Decision

The user selected independent internal client/network budgets, rather than sharing OAuth failure scopes:

- Use existing `rate_limit_buckets`, new internal scopes and the existing HMAC/network-prefix infrastructure. Default client/network budgets are 10/20 in a fifteen-minute fixed window.
- Atomically reserve network then client in one short `REQUIRES_NEW` transaction before client lookup and password hashing. If either budget is full, roll back all of this request's acquisition and return 429/Retry-After.
- Verify active first-party Basic eligibility and secret outside the reservation transaction, holding no budget row lock while hashing. Successful authentication refunds only this request's reservation in a separate short transaction. Failed, incomplete or crashed requests retain their reservation until window expiry.
- A counter therefore represents retained failures plus current-window in-flight attempts, not a successful-query quota. Successful authentication does not reset earlier failures. A full budget blocks even correct credentials before hashing; fresh windows and other unexhausted keys remain independently usable.
- Internal scopes are independent from OAuth Token/revocation authentication failure scopes. This repair does not redesign OAuth's existing limiter or couple internal traffic to Token refresh availability.
- Database/transaction failure before reservation, authentication or successful refund returns no-store503 and does not continue as authenticated. Original invalid-credential Basic401 challenge remains; limit errors are no-store429/RATE_LIMITED with Retry-After and request ID.
- Bound internal Authorization input to4096 characters and parsed client ID to128; invalid/missing/oversized/multiple headers share the anonymous internal client and network budgets, without client lookup or hash.

## Alternatives

- Count all internal queries: rejected as the initial default; the existing failure thresholds are too small for successful machine-to-machine querying.
- Reuse the OAuth failure scopes: user did not select this option; internal attack/misconfiguration should not independently consume Token-refresh failure allowance. A shared policy needs a separate protocol-endpoint concurrency review.
- Observe failures, then add only after hashing: not selected for this path because concurrent requests can overshoot the pre-hash boundary.
- Keep locks while hashing or add one in-memory cache: not selected; hashing should not extend database lock duration, and process-local state cannot represent the shared PostgreSQL budget.

## Consequences

There are two short write transactions for valid authentication; normal successful queries remain uncached and still verify the secret. Budget denial is atomic and counters are capped; refund is idempotent for the opaque request-local receipt and targets its original window. The schema's positive-count constraint means refund of the last reservation deletes that bucket rather than writing zero. Expired-window cleanup does not recreate a bucket or mutate the next window.

This bounds admitted hashing within each window/key, not total system capacity, an all-request throughput ceiling or a global cross-window concurrency limit. Budget exhaustion can temporarily reject a legitimate client or shared network; fixed-window boundaries, client/network diversity and resource-heavy successful traffic still need capacity/operations review. A crashed request's cost remains until expiry as the fail-closed tradeoff. Configuration changes during a window/mixed-version rollout require operator review, not a claimed automatic recovery protocol.

Tests use actual PostgreSQL/MockMvc and real password verification spies, controlled parallel hash entry, explicit clock and isolated SQL-failure fixtures. No production schema migration, dependency replacement, identity permission or deployment/proxy rule changed. Real Servlet/TLS/proxy addressing, restart/multi-instance deployment, metrics/throughput/alerting remain separately accepted. See the [internal Basic contract](../integration/internal-basic-authentication-budget.md).
