# Reference BFF durable revocation maintenance

[Index](README.md) · [中文](../zh-CN/reference-revocation.md) · [Sample](../../examples/reference-bff/README.md)

**Only for the durable-revocation reference profile.** Separate from SSO outbox; not persistent business login.

| Item | Rule |
| --- | --- |
| Database | Independent PG/reference_revocation/V1–V2 |
| Key | Independent 32-byte AES material in protected configuration |
| Durability | Committed tasks only; pre-submit intent/OP compensation |
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
