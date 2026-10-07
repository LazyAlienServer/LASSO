# Reference BFF Durable Revocation

[中文](../zh-CN/integration/reference-durable-revocation.md) · [ADR-0009](../adr/0009-reference-bff-durable-token-revocation.md) · [P03 ADR-0013](../adr/0013-reference-revocation-operator-recovery.md)

Reference-only sample; its PostgreSQL schema/migrations are not a Starter database API or a LASLOG migration. Default business Session/authorized clients/index/replay/sid markers/refresh coordination remain single-JVM. Restart ends business login; top-level SSO reentry creates a new one.

## Enable And Configure

Use `durable-revocation`, optionally with `https` and `application-security`. Supply protected external settings, never raw keys/passwords in command line/logs/Git:

| Property | Requirement |
| --- | --- |
| reference.revocation.jdbc-url, username, password | Independent PostgreSQL DB; no SSO identity DB. Flyway requires reference schema/migration privileges |
| reference.revocation.key-id | Stable1–64 letters/digits/period/underscore/hyphen; new unique ID on each rotation |
| reference.revocation.key-base64 | Independent random32-byte AES key, standard Base64; retain across restarts |
| reference.revocation.allow-loopback-http | false default; true only for explicit isolated literal127.0.0.1/IPv6 loopback fixtures |
| reference.revocation.worker-enabled | true default; false pauses both delivery and automatic cleanup, not acceptance |
| reference.revocation.completed-retention | P30D default,1–365days; only completed receipts, never pending/leased/dead tasks |
| reference.revocation.cleanup-batch-size |100 default,1–1000; one batch hourly after first hour |

Startup applies embedded reference `revocation-db` V1–V2, not SSO V1–V15. Missing DB/key/schema fails; no memory fallback. V2 adds replay generations, key registry and operator audit. On first upgrade, outstanding key IDs must match and retained encrypted material must authenticate before registry initialization. Registry verifies key possession even with an empty queue; same-ID/different-key and unapproved new-key restart fail.

## Acceptance, Failure And Delivery

`LasSsoRevocationTaskSink.submit` normal return means accepted work, not remote revocation. Durable sample commits REQUIRES_NEW before return; Token AES-256-GCM ciphertext binds task/registration/issuer/client/endpoint/hint/key-id, no persisted Client Secret. Current confidential registration supplies secret; snapshot identity/endpoint must still match. Successful receipt erases ciphertext/nonce. OnlyHTTP200 acknowledges RFC7009; redirects are never followed.

Local logout ends before submit. `LasSsoRevocationSubmission.REQUEST_ATTRIBUTE` stores ACCEPTED/FAILED/NO_TOKEN for the logout request only; applications can observe/report failures through their success handler or trusted monitoring without Tokens. Default memory sink now throws on invalid/unadvertised endpoint or invalid confidential input instead of silently returning. It still does not persist accepted work. A submit exception or absent registration is FAILED, never remote-success; local login is not restored.

**Pre-submit crash gap:** the queue cannot restore a Token it never committed. Consumers adopting reliable logout must document compensation: independently persist a non-secret correlation/recovery intent tied to their session/authorization in consumer-owned storage before the destructive boundary, then reconcile uncertain submission or use supported OP authorization/session revocation. If they require a retained Token for automated retry, encrypt and expire it in their own adapter/store and test those boundaries. This sample does not atomically combine business Session removal with queue commit. Failure logs alone do not compensate a lost pre-submit Token. Operator account-wide revocation affects other sessions and requires deliberate target review, not blind escalation or routine global logout.

Claim one due task at a time; PostgreSQL clock,2-minute lease, unique fencing token, at most8 claims per campaign, randomized bounded retry. Expired eighth claims become LEASE_EXHAUSTED. HTTP200 followed by failed receipt commit may replay idempotent RFC7009 after lease expiry. Database writes/claim and key operations serialize on one short advisory lock (5-second lock timeout,15-second transaction timeout); no network request holds it. These concurrency guards do not prove complete distributed BFF behavior.

## Offline Operator Commands

Stop/drain all queue-producing BFF processes and workers, identify the exact reference DB/JAR/hash and supply its protected external config through `SPRING_CONFIG_ADDITIONAL_LOCATION`. Do not reuse SSO maintenance JAR/config. The wrapper selects the exact **reference BFF** executable and launches a small non-Web context with no OIDC Discovery/registration/worker dependency. A real console must type YES; actor/reason are audit metadata, not authentication. Host/DB access and trusted artifact selection are the authority.

Metadata-only investigation:

```sql
SELECT id, registration_id, key_id, replay_count, attempt_count,
       available_at, lease_until, completed_at, dead_lettered_at, last_error_code
FROM reference_revocation.tasks ORDER BY created_at;
SELECT key_id, changed_at FROM reference_revocation.key_state WHERE id = 1;
SELECT occurred_at, action, task_id, actor, reason, replay_count, previous_attempts,
       affected_count, old_key_id, new_key_id
FROM reference_revocation.operator_audit ORDER BY occurred_at;
```

After repairing endpoint/client/DB and confirming authenticated payload is recoverable, replay exactly one dead task with its current generation:

```powershell
./scripts/reference-revocation-maintenance.ps1 -Command replay -JarPath $ReferenceJar -ExpectedSha256 $ReferenceSha256 -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'receiver repaired' -TaskId $TaskId -ExpectedReplayCount $ReplayCount
```

Replay is atomic/audited, increments replay_count and resets campaign attempts to0. Pending/completed/stale-generation/unreadable payload rejects. Duplicate/concurrent old-generation commands cannot reset twice. It never changes endpoint/client/issuer; worker rechecks them. At most8 new attempts, no automatic dead reset. Keep the target drained until reviewed, then resume worker/monitor actual completion. Do not manually set completed flags or export/decrypt Tokens. `purge-completed` invokes one configured bounded batch; automatic retention audits only nonempty batches. Operator audit is retained; pending/dead tasks require handling rather than expiry deletion.

## Drain And Rotate Key

1. Stop all producers/workers, including pre-V2 binaries. Inspect pending/dead/leases and resolve them under the current key; any `completed_at IS NULL` blocks rotation. Unrecoverable payload is an incident requiring supported OP compensation and a separately designed disposition, not SQL deletion to bypass the guard.
2. Generate new independent AES key and unique never-used ID. Keep current external key configured; supply next material via protected `reference.revocation.next-key-base64`, never option/log. Back up registry/queue and both keys with access-controlled custody.
3. Invoke `-Command rotate-key` with exact artifact/hash, ExpectedKeyId, NextKeyId, Actor/Reason. Compare old proof, check fully drained, update registry and audit atomically. Same material/new ID and retired-ID reuse reject. Failed audit/commit leaves old key authoritative.
4. Install new `key-id/key-base64`, remove transient next-key setting, restart BFF and test new submission/delivery. After committed rotation old processes cannot submit/claim/ack using the old proof. Old binaries bypassing V2 guards must remain stopped. Retain old key for approved backup recovery; queue/key restore can repeat old revocations and lose later work, so disaster recovery needs a separate exercise.

## Evidence And Limits

Original37 tests/actual Edge+SSO committed-task restart are historical [delivery .4](development-delivery-2026-10-05-4.md). P03 adds actualPostgreSQL/HTTP V1→V2, replay/audit rollback, retention/key and concurrent race tests plus offline context/console fixtures; implementation results are recorded in [P03](../implementation/revocation-recovery-2026-10-07.md). TargetTLS/pool/capacity/key custody/on-call/actual consumer adoption and cross-storage atomicity remain separate. SSO Back-channel outbox is another database/workflow: these commands must never operate on it.
