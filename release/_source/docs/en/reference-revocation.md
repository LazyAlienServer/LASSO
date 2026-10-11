# Reference BFF durable revocation maintenance

[Index](README.md) · [中文](../zh-CN/reference-revocation.md) · [Sample](../../examples/reference-bff/README.md)

**Only for the durable-revocation reference profile.** Separate from SSO outbox; not persistent business login.

| Item | Rule |
| --- | --- |
| Database | Independent PG/reference_revocation/V1–V2 |
| Key | Independent 32-byte AES material in protected configuration |
| Durability | Committed tasks only; consumer owns pre-submit uncertainty and its chosen recovery policy |
| Login | Single-JVM restart loses it |
| Completed retention | 30 days, batch 100 hourly |
| Pending/dead/lease | Never auto-purged |

## 1. Prepare

1. Stop every producer/worker, including old V1 binaries.
2. Supply independent queue config/key, not SSO config.
3. Real PowerShell terminal at package root:

```powershell
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.'reference-bff'.path
$h = $m.artifacts.'reference-bff'.sha256
```

Point additional configuration to the queue settings. The maintenance context has no Web listener, discovery or worker.

## 2. Replay exact dead task

Verify TaskId/replay_count/key ID/operator/non-secret reason.

```powershell
./scripts/reference-revocation-maintenance.ps1 -Command replay -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'receiver repaired' -TaskId $TaskId -ExpectedReplayCount $ReplayCount
```

**Expected:** After YES, generation/authenticated-payload/audit checks commit and a new 8-attempt campaign starts. The worker still verifies the current endpoint/client/issuer.

## 3. Purge completed receipts

```powershell
./scripts/reference-revocation-maintenance.ps1 -Command purge-completed -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'completed receipt retention'
```

**Expected:** Only eligible completed receipts are removed. Purging does not repair dead tasks.

## 4. Drained key rotation

| Order | Action |
| --- | --- |
| 1 | Stop all processes, no incomplete task |
| 2 | Protected temporary `reference.revocation.next-key-base64` |
| 3 | Run rotate-key with a real YES confirmation |
| 4 | After commit switch active config/remove next input |
| 5 | Start fresh process/check registry and retired-key fencing |

```powershell
./scripts/reference-revocation-maintenance.ps1 -Command rotate-key -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'queue drained key rotation' -NextKeyId $NextKeyId
```

Rejects incomplete tasks/same or reused IDs/material. No multikey/cross-storage atomic login/logout.

## Evidence

Inspect task ID/key_id/replay/attempt/timestamps/categories only, never ciphertext/Token.

Keep unresolved dead tasks, investigate/compensate at OP when needed. Separate DB/key backup and retained operator audit/growth monitoring.

## R01 Confirm queue target and prepare maintenance

**Location: package root, real PowerShell terminal.** Only the reference durable queue; not the SSO identity DB/outbox.

1. Identify independent queue database, backup, current AES key/ID. Pause every producer/worker, including older binaries. This does not automatically stop SSO/LASLOG.
2. Copy `config/reference-bff/durable-revocation.properties.example` externally, supply that database/key privately and set maintenance worker-enabled=false; never edit packaged example.
3. Set this terminal's external config location and read exact reference artifact/hash; replace non-secret path as appropriate.

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-reference/queue-maintenance.properties'
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.'reference-bff'.path
$h = $m.artifacts.'reference-bff'.sha256
$CurrentKeyId = '<current-key-id>'
$Operator = '<operator-id>'
```

4. In a controlled read-only DB session inspect bounded metadata and the total incomplete count. Verify key configuration/registry agree; do not retrieve Token/nonce/ciphertext values. The first 100 rows are a sample, not the entire queue.

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '500ms';
SELECT id, registration_id, key_id, replay_count, attempt_count,
       available_at, lease_until, completed_at, dead_lettered_at, last_error_code
FROM reference_revocation.tasks ORDER BY created_at, id LIMIT 100;
SELECT count(*) AS incomplete_tasks FROM reference_revocation.tasks WHERE completed_at IS NULL;
SELECT key_id, changed_at FROM reference_revocation.key_state WHERE id=1;
SELECT occurred_at, action, task_id, actor, reason, replay_count
FROM reference_revocation.operator_audit ORDER BY occurred_at DESC, id LIMIT 100;
COMMIT;
```

**Expected:** Correct target/current key/no competing writers. **Failure:** Missing/mismatched key stops the workflow, not replacement by SSO signing key or random AES.

## R02 Replay an exact dead task after repair

1. Use R01 to identify the exact task ID, current replay count and error. Repair the TLS/endpoint/registration/database/key cause before retrying.
2. Set `$TaskId` and `$ReplayCount` to those actual values. Run the section 2 command, verify the target/current key and enter YES in a real terminal.
3. After exit 0, read the incremented generation, new campaign and audit. Pending/completed/stale-generation refusals must not be bypassed by SQL.
4. Resume the producer/worker and observe lease, attempts and completion. A receipt failure after HTTP 200 can cause idempotent redelivery; there is no exactly-once or unknown-family guarantee.
5. After eight unsuccessful attempts the task remains dead for investigation. Do not reset it indefinitely or export decrypted Tokens.

**Expected:** Accepted task continuation and honest outcome. **Failure:** Unrepaired configuration/payload remains visible; response secrets are not case attachments.

## R03 Purge completed receipts

**Expected:** Only one eligible finite batch of completed receipts is removed, with an operator audit; incomplete work remains.

1. Verify the actual retention/batch settings, eligible completion times and retained backup/audit.
2. In the R01 offline environment, run the section 3 command with the correct key, actor and reason; confirm YES.
3. After exit 0, compare eligible completed rows and the operator audit. Zero or one finite batch is normal; pending/dead/leased tasks remain.
4. Resume the worker and verify hourly retention and audit growth. Receipt deletion is not remote authorization proof or dead-task repair.

**Failure:** Transaction/audit failure retains state; never erase incomplete work to claim drainage.

## R04 Rotate AES after complete drainage

**Expected:** The drained queue's new active key ID and audit commit together; a fresh process accepts new-key work and rejects old-key producers.

1. Resolve suitable tasks online, stop every producer/worker, then verify the R01 incomplete count is zero, including dead/leased tasks. Recheck after stopping so no producer can refill the queue.
2. Privately generate independent 32-byte material and an unused ID. Supply temporary next-key-base64 while retaining the current key; back up the queue/registry/old key separately.
3. Run the section 4 command with the expected current and new IDs. YES followed by exit 0 means registry and audit committed.
4. Install the new active ID/material in normal process configuration and remove the next-key input. Start a new process, check the registry and test accepted work. Old-key producers are fenced; do not restart an old binary.
5. Record IDs, audit references and time, never key material. Preserve old-backup readability according to custody policy rather than immediately destroying the old key.

**Failure:** Drain-required resolves work, same/reused ID/material refused. After commit repair config to match registry, not rewinding registry to bypass fencing.
