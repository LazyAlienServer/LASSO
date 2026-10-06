# Deletion transaction and concurrency contract

- Status: issue S3 implemented and controlled PostgreSQL verification completed on2026-10-04; real deployment acceptance pending.
- Chinese counterpart: [注销事务与并发契约](../zh-CN/implementation/deletion-concurrency.md).
- Decision: [ADR-0006](../adr/0006-user-first-deletion-batch-locking.md), user-approved retention of batch atomicity.

## 1. Modules and original cycle

`DeletionRequestService` owns user request/cancel and credential/tombstone execution; `AdminDeletionRequestService` owns hold/release and audit; `DeletionExecutionJob` coordinates expiry/execution in a scheduled transaction with advisory-lock ownership. They use the same PostgreSQL user/request state and eligibility version.

The original cancellation held user→request while admin hold/release and expiry held request→user. Tests first established actual PostgreSQL blocking with `pg_blocking_pids`: while a user was locked, the waiting hold/release already owned its request, and expiry processing waited instead of skipping before mutation. The three original cases failed. They establish the inverse lock dependency; no forced deadlock victim/production incident was fabricated as evidence.

## 2. Interactive mutations

Request and cancel continue to serialize on the target user. Request timing is taken after that user lock so the thirty-day delay is not consumed during an arbitrary wait. Cancel updates only the current PENDING/HELD request and clears hold fields; its eligibility version and security event stay transactional.

Admin hold/release performs:

1. Nonlocking lookup of a candidate owner by the specific request ID.
2. Lock target user.
3. Lock/recheck the same request ID and owner.
4. Check the current required status(PENDING for hold, HELD for release); for hold also recheck the deadline after waiting.
5. Update request, eligibility version, security event and admin audit in the same transaction.

If cancellation committed first, the admin update rejects with ACCOUNT_STATE_CONFLICT and writes no false success audit. If an admin mutation committed first, a later valid cancellation may cancel its resulting state. An old cancelled request cannot change a replacement request. Changed/disappeared candidate ownership is rejected, not applied to an unowned request.

Direct `execute(requestId,userId)` first locks the user then the exact owned request and checks PENDING and execute_after<=now. Wrong pairing, not-due, HELD and cancelled requests are no-ops, with credentials/version unaffected. Existing administrator protection still cancels a due pending request rather than deleting that administrator. This remains a defensive fallback for historical/inconsistent state; the subsequently implemented [S4 promotion gate](admin-promotion-deletion.md) prevents supported promotion with an effective request.

## 3. Atomic batch selection and scheduled phases

`executeDue(limit)` requires a positive limit, reads a limited pending candidate snapshot, locks its distinct users in PostgreSQL UUID order with SKIP LOCKED, then locks/rechecks requests and executes actual due matches. PostgreSQL UUID order is deliberately used rather than Java UUID's different signed comparison. Lock acquisition order differs from processing priority: requests keep due-time/id processing order after user locks are established.

Scheduled `run` retains one advisory transaction lock and batch size100. Its new coordinator `processDueBatch`:

- selects at most100 expired HELD and100 due PENDING candidates without request locks;
- locks the whole preselected user union once in stable DB order;
- releases selected, owned, unlocked holds only if they are still HELD with hold_until strictly before the expiry cutoff;
- reselects due PENDING only from the same preselected requests and already locked users (including just-released holds), at most100 executions;
- never acquires an additional user after phase-one request mutations begin.

The explicit expired-hold entry delegates to the same user-first service. Batch user/request lock contention is skipped, not waited out, and the candidate remains available for a later invocation. State/deadline/owner changes are rechecked before effects. Continued contention can delay candidates; no starvation/latency bound is claimed. Selection limits apply before lock skipping, so a run can process fewer than its nominal limit and does not fill from arbitrary new users after selection.

All processed candidates and both scheduled phases retain the same outer transaction/whole-batch rollback. There is no separate per-user commit, claim table, lease or automatic transaction retry. Public method/constructor signatures remain callable; the existing scheduled Clock/cutoff seam and service Clock remain separate. Release cutoff is explicit; pending execution still uses current service time. Public positive-limit APIs are not newly capped at100, while the scheduled job itself remains100.

Counters now report **actual transitions**, not candidate-list length. `executeDue` includes processed administrator protective cancellations; `run` sums released holds and processed pending transitions, so one released-then-executed request counts two. Busy/changed candidates count zero; zero does not attest that all due work is finished. Batch exceptions still roll back all affected state and propagate to the existing caller/scheduler rather than reporting partial success.

## 4. Executed tests

`DeletionConcurrencyIntegrationTest`: **24** new cases with real PostgreSQL18.4/Testcontainers and Spring services/transactions. Tests include:

- hold/release waiting on a user without already owning its request, using a separate SKIP LOCKED probe;
- cancellation/admin mutation in both commit orders and deletion/cancellation in both orders, exact events/audit/version/credentials outcomes;
- busy user and request skipped/retried, overdue hold refresh/cancellation preventing stale expiry, uncommitted hold preventing deletion, request replacement/owner corruption recheck;
- direct owner/status/due checks, limits/actual counts and repeated execution idempotency;
- union-user prelocking observed before the first eligibility write, advisory-owner skip, concurrent batches without duplicate events/outbox;
- real SQL-trigger failure after a first deletion rolling back both targets; scheduled second-phase injected failure rolling back expiry release too; interactive rollback;
- explicit Clock advanced during a real lock wait proving a now-expired admin hold is rejected without version/audit effects.

Latch and `pg_blocking_pids` drive actual interleavings; bounded Future waits test SKIP behavior, not random sleeps as success evidence. Eligibility spy delegates to real methods except explicitly injected/observed paths; the manual Clock service is wrapped in a real TransactionTemplate. Corrupt-owner changes and SQL function/trigger injection are confined to the disposable database and cleaned up, not a production mutation or migration. One compile error from moving a time declaration was corrected during repair and is not original defect evidence.

Commands from las-sso-server(Java21):

```powershell
.\gradlew.bat :test --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --no-daemon --console=plain
# Original3/3failed, then green in expanded suites.

.\gradlew.bat :test --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

Last focused run at about17:49–17:50 Asia/Shanghai: **40cases(24+10+6)**, zero failures/errors/skips. Full forced run at about17:54–17:57 on2026-10-04: **227server/176starter**, zero failures/errors/skips,12 actionable tasks executed; reference BFF recompiled/test NO-SOURCE. Server deletion XML shows24cases at09:54:51UTC, original deletion10 at09:55:16UTC. Early red XML was overwritten by subsequent Gradle results; phase evidence remains in command output/status map§3.9.

## 5. Accepted limits and remaining work

No schema/dependency/lock-file/authentication/permission/retention policy changed. Existing30-day delay,90-day hold limit, scoped cancellation, audit/security/version, administrator fallback and transactional logout outbox remain. Real browsers/HTTP/TLS/proxy, live multi-instance scheduler, crash/restore, lock contention capacity/starvation and all other application lock graphs were not accepted. Real PostgreSQL races are not production concurrency stress evidence.

This closes the identified S3 order at the tested layer. Subsequent scoped evidence is recorded for [S4 promotion](admin-promotion-deletion.md)/§3.10 (255/176), [S5 tombstones](admin-tombstone-views.md)/§3.11 (273/176), [S6 recovery](recovery-credential-entropy.md)/§3.12 (314/176), [D1 Security](spring-security-consent-patch.md)/§3.13 (323/176) and [A1/A2 Servlet HTTP](servlet-path-security.md)/§3.14 (latest 419/176). The 227/176 counts above preserve S3. Caller transactions with pre-owned different user/request locks must maintain their own order. Full scanning, B1 durable/consumer/version, B2 registration ordering and production proxy/TLS/browser remain separate. Uncommitted; no hosted release/deployment occurred.
