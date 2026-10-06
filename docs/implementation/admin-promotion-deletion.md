# Administrator promotion and effective deletion requests

- Status: issue S4 implemented and controlled PostgreSQL/command verification completed on 2026-10-04; live maintenance/deployment acceptance pending.
- Chinese counterpart: [管理员晋升与有效注销申请](../zh-CN/implementation/admin-promotion-deletion.md).
- Decision: [ADR-0002](../adr/0002-require-deletion-cancellation-before-admin-promotion.md), originally confirmed on 2026-10-02; no new product rule in this repair.

## 1. Promotion gate

`AdminBootstrapService.promoteExistingUser` is the existing server-side maintenance entry. It acquires the existing maintenance advisory lock and requires that no administrator exists, then locks the target ACTIVE user. Before username/password validation or any role/security mutation, it locks/checks that user's effective `PENDING/HELD` deletion requests.

| Target state | Outcome |
| --- | --- |
| ACTIVE with PENDING or HELD request | Reject `DELETION_CANCELLATION_REQUIRED`; owner cancels, operator retries |
| ACTIVE with overdue PENDING or expired-but-still-HELD request | Same rejection; a deadline does not make the request terminal |
| Hold released to PENDING | Still reject; release is not cancellation |
| ACTIVE with only cancelled history or no effective request | May promote if the other existing requirements are satisfied |
| Cancelled history plus a newer effective request | Reject |
| Frozen, deleted or unknown target | Existing `USER_NOT_FOUND` rejection |
| An administrator already exists | Existing `ADMIN_ALREADY_EXISTS` rejection |

The user row serializes supported deletion writers under S3's user-first rule. Request locking/checking occurs after waiting for that row, not as a command-start preflight. There is no new schema, isolation mode, permission claim, authentication factor or Web promotion endpoint. Empty-database administrator creation and maintenance reset retain their existing paths.

Refusal occurs before temporary-password policy/hash, role change, credential/eligibility version change, recovery revocation, session/authorization revocation, outbox or success audit. It does not implicitly cancel or release the request. Full row snapshots verify these states remain unchanged relative to immediately before the attempt. After the owner cancels, promotion does not rewrite cancelled request history; it retains existing temporary password, version, security invalidation and successful maintenance audit behavior.

## 2. Concurrent outcomes

Controlled PostgreSQL tests verify:

- deletion application commits while promotion waits on the user: promotion sees the new request and rejects, even though it already acquired its maintenance lock;
- promotion commits first: a waiting application sees administrator identity and rejects without creating a request;
- cancellation commits first: waiting promotion becomes eligible and cannot change credentials before cancellation commits;
- deletion execution commits first: waiting promotion rejects the deleted target; no administrator is created;
- request becomes HELD or account becomes FROZEN while promotion waits: current state is rechecked and rejected (the frozen-state update is an explicit isolated SQL fixture, not acceptance of the freeze HTTP flow);
- while waiting for user, promotion does not already hold the deletion-request row;
- concurrent eligible bootstrap attempts still produce one success and one existing-admin rejection.

Failed eligible promotion rolls back role, hash, versions, security state, audit and outbox. One test injects a real PostgreSQL audit trigger failure after earlier changes; a separate explicit outer transaction failure proves rollback and retry. These tests do not establish that arbitrary manual SQL or caller transactions with pre-owned conflicting locks obey the invariant. Live multi-instance/crash/capacity acceptance remains separate.

## 3. Maintenance command

The existing script/command remains UUID-based with hidden interactive password input. On the new refusal, `AdminBootstrapCommand` outputs:

```text
Administrator promotion refused (DELETION_CANCELLATION_REQUIRED).
The account owner must cancel the pending/held deletion request first; then retry administrator bootstrap.
```

It prints no success result and rethrows `AdminBootstrapException`, preserving ApplicationRunner/startup failure. The existing PowerShell wrapper still propagates the Java exit code; it was read, not run or changed. Tests replace Console acquisition through a package-private supplier seam; the production public constructor still uses `System.console()` and retains non-Web/interactive requirements. Tests verify refusal/failure/success output does not contain the password and both password arrays are cleared. Actual JVM terminal hiding, packaging, shell process exit code and production maintenance rehearsal were not run.

Safe next steps for an operator are owner cancellation through the existing authenticated flow, then retry of the same intended target after checking lifecycle/admin state. Do not directly edit the role or cancel a user's request in SQL. A password dialog or token issue time is not recent-password evidence; this maintenance gate adds no such evidence.

## 4. Evidence and historical fixture correction

`AdminPromotionDeletionIntegrationTest` adds **25** cases using PostgreSQL 18.4 Testcontainers and Spring-proxied services. Recovery codes/sessions, administrator-issued credentials and registered clients are created through owning services. Post-request session/authorization rows are explicit fixtures to verify rejection does not erase even existing restricted/security state; their presence is not evidence that unrestricted OIDC authorization is normally allowed during pending deletion.

The original PENDING/HELD test ran before the fix. The first HELD attempt failed in setup because the fixture reused a RegisteredClient secret; unique fixture secrets corrected that error. The rerun then had **two expected-regression failures**, both because no rejection was raised. After the gate, both passed with full-row state comparison. Red XML was overwritten by later Gradle reports; command output/status map §3.10 preserves the phase evidence.

`AdminBootstrapCommandTest` adds **three** fixture cases for cancellation guidance/failure propagation/password-array cleanup and existing unrelated failure/success behavior. The existing deletion test was renamed to `executionProtectsAdministratorInLegacyInconsistentDeletionState`: it now directly creates a historical/corrupt role/request combination in the isolated DB to verify the execution fallback. It no longer obtains that combination through supported promotion. This preserves the defensive fallback without treating it as an allowed workflow.

Commands in `las-sso-server/`, Java 21:

```powershell
.\gradlew.bat :test --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --tests "org.las.sso.maintenance.AdminBootstrapCommandTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --tests "org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

Final focused run: **90** cases (25 + 3 + 10 + 24 + 28), zero failures/errors/skips, at about 19:23 Asia/Shanghai. Full forced run on 2026-10-04 at about 19:25–19:28: **255 server / 176 starter**, zero failures/errors/skips, 12 actionable tasks executed; reference BFF recompiled and `test NO-SOURCE`. Server promotion XML is 25 cases at 11:26:48 UTC, command XML three at 11:26:46 UTC. This repair adds 28 cases relative to 227 server tests.

## 5. Not verified and remaining work

No actual production database, deployment, commit or release was performed. The SQL failure trigger is installed/removed only in the disposable test database. Latches/`pg_blocking_pids` observe actual user-lock waits; no random sleep is used as proof of ordering. Mock Console tests do not prove real interactive process behavior; the existing fresh-database test is service-level maintenance evidence.

Live browser cancellation/forced-password-change flow, terminal/script/JAR exit behavior, restart/crash recovery, high contention/throughput and deployed multi-instance maintenance were not accepted. Existing S1/S3 behavior and database rollback are verified within their test scope. S5 tombstone views were subsequently repaired and PostgreSQL/MockMvc/template verified; see the [tombstone contract](admin-tombstone-views.md) and status map §3.11. S6 generation/legacy compatibility was subsequently repaired/verified; see the [recovery contract](recovery-credential-entropy.md) and §3.12. D1's named patch was subsequently upgraded/verified; see the [Security record](spring-security-consent-patch.md) and §3.13. Full scanning and complete B1/consumer/version delivery remain open. The 255/176 counts above preserve S4; latest execution is recorded in the [status map](status-issues-and-plan-2026-10-02.md).
