# Service-access state epoch, lifecycle wire contract and load observation

- Status: SSO-04 server facts/epoch contract implemented and controlled tests complete on 2026-10-05; real restore/consumer reconciliation/production capacity pending.
- Update2026-10-06: [real isolated restoration](../integration/development-delivery-2026-10-06-1.md) and [executable barrier](../operations/restore-epoch-barrier.md) verify restored lower versions/new epoch/old token refusal using pg_dump/restore and persistent keys. Consumer generation acceptance is a harness fixture, not business runtime; production capacity/restore/consumer acceptance remains separate.
- Chinese counterpart: [服务资格代次、生命周期响应与负载记录](../zh-CN/implementation/service-access-epoch-contract.md).
- Decision: [ADR-0007](../adr/0007-scope-service-access-versions-to-security-epoch.md), user selected reuse of the global security epoch.

## Wire and authentication

`GET /internal/v1/principals/{UUID}/service-access` uses the existing ACTIVE FIRST_PARTY `client_secret_basic` boundary/A3 budget, with no-store. The identified development server returns seven fields:

```json
{
  "issuer": "http://127.0.0.1:9000",
  "subject": "019fb42f-5f16-7c31-8ce0-9e54f7441ff0",
  "decision": "DENIED",
  "reason": "DELETION_PENDING",
  "stateEpoch": "opaque-current-security-epoch",
  "stateVersion": 4,
  "changedAt": "2026-10-05T00:00:00Z"
}
```

`stateEpoch` is the existing global `security_epoch` identifier, read in the same SQL statement snapshot as user/credential/deletion facts. It is not a credential/grant, timestamp or sortable generation number. Every result, including unknown UUID, includes it; missing global row/blank epoch fails 503/IDENTITY_SERVICE_UNAVAILABLE/no-store, rather than inventing an epoch or returning allowed. Unknown UUID is 200 DENIED/PRINCIPAL_NOT_FOUND/version 0/changedAt null. No username/admin/business-permission/secret field is exposed. Strict six-field consumers must update; no database migration is needed.

## Reasons, versions and recovery

Priority is: DELETED → ACCOUNT_DELETED; FROZEN → ACCOUNT_FROZEN; effective PENDING/HELD → DELETION_PENDING/HELD; required password → PASSWORD_CHANGE_REQUIRED; disabled credentials → CREDENTIALS_DISABLED; valid ACTIVE credentials/account → ALLOWED/ACTIVE; otherwise DENIED/ACCOUNT_STATE_INVALID. Some invalid coexistence variants are explicit test SQL, not supported writer flows.

Within one epoch, supported lifecycle transitions update the subject's version and changed time in the owning transaction. Rollback/rejected transitions do not publish another version. Regular password changes/recovery from ACTIVE to ACTIVE advance credential version and invalidate sessions, but leave the eligibility decision/version/time unchanged. Leaving PASSWORD_CHANGE_REQUIRED advances eligibility. Read queries never advance the counter; Back-channel Logout/session freshness and access eligibility remain different mechanisms.

Ordering is only within `(issuer, subject, stateEpoch)`. Restoring old PostgreSQL may return a lower subject version; after the mandatory new `security_epoch`, it belongs to another namespace. `changedAt` cannot establish order across restore, and equal numeric versions in different epochs are unrelated. Consumer cache acceptance must:

1. correlate requests/results to its current local generation;
2. accept same-epoch versions without allowing lower versions to replace newer facts;
3. fail closed when an unexpected epoch is encountered, invalidate old-generation qualification/local evidence and establish the current authoritative epoch through a fresh reconciliation/recovery barrier;
4. reject delayed responses from the retired epoch after that barrier; never choose a newer epoch by lexicographic/UUID/time sorting or flip generations per arriving response.

This server delivery does not implement a consumer cache or guarantee one-minute observation. The business system's timers/activity refresh/restart behavior, and a coordinated restoration maintenance window, remain consumer/operations responsibilities. A restore that reuses its old epoch is unsupported and undetectable through this pair. Independent eligibility events remain deferred under ADR-0001.

## Verification and small load observation

`ServiceAccessLifecycleContractIntegrationTest`: **17 new** actual PostgreSQL/Spring/MockMvc cases. It verifies exact seven-field shape/no-store, complete ACTIVE/freeze/unfreeze/PENDING/HELD/release/cancel/new request/execution history and versions, tombstone without credentials, unknown result, restriction precedence, missing/disabled/required credential states, mutation rollback/rejection, normal recovery/password distinction, required-state exit, concurrent read before freeze commit, restoration-shape lower version/new epoch, epoch rollback and missing/blank epoch 503. Restoration is a disposable SQL fixture, not pg_dump/pg_restore or live recovery; consumer rejection of in-flight old responses is a contract obligation, not implemented/tested business behavior.

`ServiceAccessQueryLoadIntegrationTest`: **one new** real loopback TCP/Tomcat/PostgreSQL/Argon2 observation. Four warmups, then eight workers each make ten legitimate queries (80 total), with original A3 client10/network20 failure/in-flight budgets. Every result is 200/ALLOWED/version1/nonblank epoch/no-store; successful reservation counters are zero afterward. The full-run measurement was:

| Requests/workers | Total | Throughput | p50 | p95 | p99 |
| --- | --- | --- | --- | --- | --- |
| 80 / 8 | 1343.0 ms | 59.57 requests/s | 112.9 ms | 203.5 ms | 232.1 ms |

These are workstation-specific measured values in XML, not thresholds/production SLA, saturation proof, a ten-minute sustained test, proxy/TLS capacity or support for a particular consumer count. Increasing valid concurrency to exceed current budget limits can still temporarily yield 429; this test uses eight workers and shows successful totals are not a fixed failed-attempt quota. No raw secret/Token is printed by this measurement. Complete production sizing/metrics/DB pool/Argon2/security scans remain pending.

Commands in `las-sso-server/`, Java 21/Docker:

```powershell
.\gradlew.bat :test --tests "org.las.sso.config.ServiceAccessLifecycleContractIntegrationTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --no-daemon --console=plain
.\gradlew.bat :test --tests "org.las.sso.config.ServiceAccessQueryLoadIntegrationTest" --no-daemon --console=plain
```

Epoch/lifecycle plus foundation/budget: **48 = 17 + 6 + 25**, zero failures/errors/skips. Combined client/query/persistence run: **110**, zero failures/errors/skips. Common forced development-tuple run: **460 server / 178 starter**, zero failures/errors/skips, 18 tasks/boot JARs/publication metadata; contract XML17 at `2026-10-04T17:47:04.060Z`, load XML1 at `17:47:10.736Z` with the observation above. Build/provenance in [development delivery](../integration/development-delivery-2026-10-05.md).

No schema/dependency/authentication-factor/permission/event migration was introduced. Query Java Decision gains the epoch component; cache/DTO compatibility is an explicit integration change. Work is uncommitted; no hosted release/deployment or production restore occurred.
