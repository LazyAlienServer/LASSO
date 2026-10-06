# Internal first-party Basic authentication budget

- Status: A3 implemented/controlled verification completed on2026-10-04; production transport/capacity acceptance pending.
- Chinese counterpart: [内部第一方 Basic 认证预算](../zh-CN/integration/internal-basic-authentication-budget.md).
- Decision: [ADR-0005](../adr/0005-independent-internal-basic-failure-budget.md), independent internal failure/in-flight budget approved by the user.

## 1. Scope and configuration

The existing internal route is:

```text
GET /internal/v1/principals/{subject}/service-access
Authorization: Basic <registered first-party credentials>
```

Only an active FIRST_PARTY client with client_secret_basic and matching secret obtains the service identity. This does not enable Client Credentials Grant or accept a human identity token/Cookie in place of client authentication. A1's normalized path matcher/explicit service-role authorization remains.

Configuration defaults:

```yaml
las:
  sso:
    first-party-authentication:
      client-failures: 10
      network-failures: 20
      window: PT15M
```

Limits must be positive; window must be a positive whole-second Duration. Properties are scanned/validated by the server. They define a fixed epoch-aligned window, not a sliding window. Budget cost is per client ID and existing IPv4 /24 or IPv6 /56 network key from Servlet remote address. The database stores HMAC digests in existing rate_limit_buckets under FIRST_PARTY_AUTH_CLIENT / FIRST_PARTY_AUTH_NETWORK, not raw credentials or full IP. Production trusted-proxy/address handling is not altered by this step and still needs deployment verification.

These scopes are independent of OAuth CLIENT_SECRET_FAILURE_CLIENT/IP. An exhausted OAuth failure counter does not by itself exhaust internal authentication; internal costs do not change OAuth failure counters. This is not evidence that every OAuth limit/concurrency behavior has been repaired.

## 2. Admission and refund

The filter bounds/decodes the header, then the budget reserves network and client in **one short new transaction**. The conditional UPSERT increases a count only when below its limit, so a full bucket cannot let additional simultaneous attempts through. A denied second bucket rolls back the first bucket's increment. Budget counters remain at the limit rather than incrementing on every denied request.

After admission, the transaction commits. Client lookup, active/first-party eligibility and PasswordEncoder.matches happen outside the budget locks. Unknown/ineligible clients do not reach the expensive hash, but retain failure cost. If credentials authenticate successfully, a second short transaction refunds exactly one reservation from each original-window bucket before service authentication continues. Success never deletes earlier failure cost. The last count is deleted (the schema forbids zero); other counts are decremented. Request-local receipt refund is idempotent, and expired-window cleanup is tolerated without recreating data or affecting a new window.

Failures and interrupted/crashed attempts retain their cost until the window expires. Therefore the count is **failed attempts plus currently admitted attempts**, not a quota on completed valid queries. Twenty-five sequential valid queries are tested to continue and leave no budget cost. Current-window concurrent valid authentication may still temporarily hit the in-flight limit; successful throughput is not otherwise capped or cached.

An exhausted budget rejects even correct credentials before hashing until time expiry or an outstanding success refunds its own cost. The retry window is a maximum wait hint, not proof of exact service recovery. Known client IDs/anonymous inputs/shared networks can be temporarily blocked by hostile failure traffic; this is the accepted tradeoff, not a permanent account ban. Epoch window rollover resets admission and can permit overlapping hash work across windows. There is no separate global concurrency limit, request-rate shield, successful-secret cache or durable reservation lease.

## 3. Input and responses

Internal input must contain exactly one Authorization header, no more than4096 characters, a valid Basic payload with a nonblank client ID at most128 characters (no controls), and a nonempty secret. Existing Basic decoding remains unchanged. Missing/invalid/oversized/multiple headers use the `<unknown>` internal client bucket and network bucket without client lookup/hash. Rotating syntactically valid unknown IDs still share network cost. No client_id query parameter is used as fallback for internal Basic.

| Outcome | Response |
| --- | --- |
| Invalid/missing/not-active-first-party credential, budget admitted | Existing401 Basic realm challenge, no-store |
| Client or network budget exhausted |429, Retry-After integer seconds, no-store problem+json code RATE_LIMITED |
| Reservation/client DB query/refund transaction unavailable |503, no-store problem+json code IDENTITY_SERVICE_UNAVAILABLE; do not continue authenticated |
| Valid credential and successful refund | Existing service-access response; no subject/status/field change |

The429/503 body includes requestId matching X-Request-Id, reusing the existing request filter attribute or generating a server UUID if unavailable at that filter position. Failures clear current SecurityContext; they do not convert a prior human login into a service role. Only non-secret error category/static exhaustion message is logged, no Secret/header/SQL/exception content. HMAC window counts and targeted logs provide bounded evidence, not a full metrics/operations alerting implementation.

The server filter's constructor now requires the budget/network/request-ID collaborators; manual construction must supply them. This is server authentication wiring, not a starter API change. Only this internal path receives the new budget; ordinary login and OAuth endpoint limiters retain their own configuration. No migration/dependency/lock/cache change is introduced.

## 4. Executed verification

`FirstPartyClientRateLimitIntegrationTest` original one-case red ran on2026-10-04 at15:23 Asia/Shanghai: the eleventh wrong Secret still returned401. After wiring the new budget it returns429 and PasswordEncoder.matches is verified to be called only ten times. The spy delegates to the real encoder; the pre-hash signal is a call count, not inferred from response timing.

Final **25** cases use actual PostgreSQL18.4 Testcontainers, real Spring security/MockMvc, registered Argon2 credentials, and encoder/budget spies for instrumentation or explicitly injected faults. Coverage:

- sequential wrong-secret pre-hash cutoff, repeated successful queries/refund and previous failures not erased;
- client cutoff across networks, IPv4/IPv6 network cutoff with rotating unknown IDs;
- malformed/oversized/empty/missing/multiple/control/long-ID credentials, ineligible client and normalized encoded path;
- OAuth/internal scope isolation;
-25 concurrent bad queries paused at hash entry: exactly ten enter verification, fifteen429, with DB locks already released;
- before-reservation/refund exception response/log safety, plus **actual SQL failures** on the second bucket and successful-refund path proving transaction rollback/fail-closed503;
- exact fixed-window boundary, old-window refund/idempotency and expiry-cleanup handling, configurable limits/window rejection, request IDs and targeted log canaries.

SQL constraint/function/trigger faults are confined to the disposable test database and removed in finally blocks; no project migration or user database changed. Clock tests directly construct the service but still use its real TransactionTemplate/transaction manager. Faults/concurrency observation are not live DB outage, kill/restart, load or distributed deployment drills.

Commands from las-sso-server with Java21:

```powershell
.\gradlew.bat :test --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --no-daemon --console=plain
# Original red, then expanded passing runs.

.\gradlew.bat :test --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --tests "org.las.sso.security.ClientAuthenticationRateLimitConverterTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

Final focused run: **32** (25+6+1), zero failures/errors/skips. Full forced run approximately15:46–15:49 Asia/Shanghai on2026-10-04: **203 server /176 starter**, zero failures/errors/skips,12 actionable tasks executed; reference BFF recompiled, test NO-SOURCE. Red XML was overwritten by later Gradle runs; phase evidence remains in command output and status map§3.8.

## 5. Remaining acceptance

2026-10-05 query delivery adds stateEpoch and a separate eight-worker/80-valid-request TCP observation without failure-counter accumulation; lifecycle/restore-shaped tests and exact wire versioning are in the [epoch contract](../implementation/service-access-epoch-contract.md). This extends the tested server query layer, not all A3 concurrent/503/proxy/capacity paths or production SLA. Identified artifacts/compatibility are in the [development delivery](development-delivery-2026-10-05.md).

The later [A1/A2 Servlet HTTP matrix](../implementation/servlet-path-security.md) verifies direct Tomcat/TCP Basic admission/refund/client-budget denial under root and /passport; it does not accept every A3 failure/concurrency/capacity path over transport. TLS/reverse proxy network keys, production Argon2/DB/connection-pool capacity, valid-client throughput, window-boundary saturation, metrics/alerting and restart/multi-instance deployment remain pending. Shared PostgreSQL atomicity is tested, not a live multi-instance topology. Mixed-version/limit/window reconfiguration needs operations planning; client config versus in-flight secret verification is not newly serialized. SSO-04 restore/version/throughput remains separate. S3–S6/D1 subsequently gained their own scoped evidence, tracked by the status map. No commit/release/deployment occurred in A3.
