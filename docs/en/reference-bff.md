# Reference BFF and optional queue

[Index](README.md) · [中文](../zh-CN/reference-bff.md) · [Integration](integration.md)

The sample is not LASLOG. In a source checkout run `:las-sso-reference-bff:bootRun` after local SSO. Package deployment registers its own confidential client, supplies external HTTPS settings and places loopback8088 behind a trusted proxy:

```sh
java -jar examples/reference-bff/las-sso-reference-bff-{{VERSION}}.jar --spring.profiles.active=https --spring.config.additional-location=file:/etc/las-reference/bff.properties
```

Default chain or `application-security` demonstrates application composition. Initial business login/index/replay is one JVM and ends on restart. Browser endpoints and safety rules are in [integration](integration.md).

## Durable-revocation profile

Use `https,durable-revocation` only when selected. `config/reference-bff/durable-revocation.properties.example` supplies an independent PostgreSQL database,32byte AES key/base64 and key ID. The sample migrates reference V1/V2, never the SSO identity DB. Generate/store AES material privately, e.g. OpenSSL random32bytes encoded base64; do not substitute signing/TLS keys.

Committed encrypted tasks recover across worker restart, leases and retries. Eight failed attempts become dead; completed receipts default30days/batch100/hour. Pending/dead/leased work is never auto-purged. Pre-submit crash uncertainty remains consumer-owned; accepted task is not remote acknowledgement or persistent login.

## Offline inspection and recovery

Stop every producer/worker, including older processes. Use a protected maintenance copy with worker-enabled=false and current key/registry agreement. Read only bounded metadata, not ciphertext/nonce/Token:

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout='10s';
SET LOCAL lock_timeout='500ms';
SELECT id,registration_id,key_id,replay_count,attempt_count,available_at,lease_until,completed_at,dead_lettered_at,last_error_code
FROM reference_revocation.tasks ORDER BY created_at,id LIMIT 100;
SELECT count(*) AS incomplete FROM reference_revocation.tasks WHERE completed_at IS NULL;
SELECT key_id,changed_at FROM reference_revocation.key_state WHERE id=1;
SELECT occurred_at,action,task_id,actor,reason,replay_count FROM reference_revocation.operator_audit ORDER BY occurred_at DESC,id LIMIT 100;
COMMIT;
```

At package root in a real PowerShell terminal:

```powershell
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.'reference-bff'.path
$h = $m.artifacts.'reference-bff'.sha256
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-reference/queue-maintenance.properties'
./scripts/reference-revocation-maintenance.ps1 -Command replay -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'receiver repaired' -TaskId $TaskId -ExpectedReplayCount $ReplayCount
```

1. Repair actual TLS/endpoint/client/key cause, fill exact task/current generation/key/actor and confirm YES. Exit0 increments replay count/new campaign with audit. Stale/pending/completed refusal is not bypassed by SQL.
2. Resume appropriate producers/workers, observe actual delivery/receipt. After eight failures keep dead for investigation; no infinite replay. Receipt failures may cause idempotent delivery, not exactly-once.
3. `-Command purge-completed` with the same artifact/key/actor/reason/YES removes one eligible finite batch; pending/dead remain. Receipt deletion is not family-cleanup proof.
4. Rotation: stop all processes, **recheck incomplete=0**, back up queue/current key separately, supply private `reference.revocation.next-key-base64` and unused new ID. Run `-Command rotate-key ... -NextKeyId $NextKeyId` and YES. After exit0 install new active config/remove next input, start new process and verify registry/acceptance; old-key processes are fenced.

Keep operator audit/backup readability. Failure after key-registry commit is repaired against that new registry, not rewound. Maintenance accepts key material only from external settings and performs no Token network request or OIDC discovery.
