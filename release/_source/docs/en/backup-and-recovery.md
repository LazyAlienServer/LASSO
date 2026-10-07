# Backup And Recovery

[Index](README.md) · [中文](../zh-CN/backup-and-recovery.md)

## Backup Custody

Define RPO/RTO, retention, encryption, restore authority and tested run frequency with operations. No production schedule or backup is delivered. Take transaction-consistent PostgreSQL backups (`pg_dump` custom format for rehearsals; production PITR/WAL policy separately sized), record DB/schema/Flyway/version/epoch/time and verify independent restore. Avoid putting DB passwords in command arguments; use protected credentials. Store signing keys, peppers, configuration/collector/client secrets separately under encrypted custody with recovery access. A dump alone is not recoverable authentication state.

Back up consumer DB/queues separately. Reference accepted tasks need matching AES key and endpoint/client configuration; restoring a queue can duplicate delivered revocations and omit newer accepted tasks. Default business login is intentionally lost on process restart. Post-snapshot password/credential-use/deletion changes cannot be reconstructed by SSO from an old snapshot; reconcile incidents before reopening.

## Restore Barrier — Required For Old Snapshots

1. Drain public ingress; stop SSO, scheduled jobs and consumer writers. Restore only to an isolated, explicitly identified target. Keep original backup/custody records. Review PostgreSQL/schema compatibility, current admin, permanent UUID/credential/client/audit/deletion facts.
2. Query and record the restored epoch with a protected DB session:

```sql
SELECT security_epoch, updated_at, change_reason FROM system_security_state WHERE id = 1;
```

3. Verify exact package/JAR/hash, supply target DB/server-only configuration and external secrets; no monitoring/web profile. In interactive PowerShell from package root:

```powershell
$m = Get-Content -LiteralPath './provenance/release-manifest.json' -Raw | ConvertFrom-Json
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
./scripts/rotate-security-epoch-after-restore.ps1 -Profile production -JarPath (Join-Path $PWD $m.artifacts.server.path) -ExpectedSha256 $m.artifacts.server.sha256 -ExpectedEpoch '<restored-epoch>' -Reason 'POSTGRES_RESTORE_APPROVED'
```

The script forces non-web/scheduling=false. The command refuses mismatched/blank epoch, noninteractive console, web mode or active scheduling; operator confirms target, then the SSO transaction rotates epoch/revokes transient security state. Do not retry with the old epoch after successful commit.

4. Verify new epoch differs; active `user_sessions`, `spring_session`, `oauth2_authorization`, unrevoked `oauth2_refresh_token_history` and reauthentication/transient challenges are cleared. Pending outbox may remain but old claims must be reset. SQL checks:

```sql
SELECT security_epoch FROM system_security_state WHERE id = 1;
SELECT count(*) FROM user_sessions WHERE status = 'ACTIVE';
SELECT count(*) FROM spring_session;
SELECT count(*) FROM oauth2_authorization;
SELECT count(*) FROM oauth2_refresh_token_history WHERE status <> 'REVOKED';
SELECT count(*) FROM oidc_password_challenges;
```

5. Install a **new persistent signing key/kid** separately; retire old kid at all consumer JWKS/verification/cache boundaries. Do not reopen with the restored key. No approved same-key restore policy is part of V1. Epoch transaction cannot atomically update keys/consumer stores.
6. Consumers clear local sessions and eligibility/password evidence, establish the current epoch through a fresh authoritative reconciliation generation, then reject delayed old-generation responses. Epoch/version/time lexical ordering is invalid; lower restored versions may be valid only within the new epoch. Actual consumers must implement this barrier, not simply accept any returned epoch.
7. Start SSO/jobs, verify DB readiness/Discovery/new JWKS and fresh login/UserInfo. Start consumers, verify old Cookies/code/AT/RT reject, new refresh/current/global logout/Back-channel succeed, pending outbox and due deletion behavior are reviewed. Reopen gradually only after consumer sign-off.

## Failure And Scope

On transactional failure keep ingress drained; diagnose target/epoch/DB/permissions before another attempt. On later consumer/key failure keep the new epoch and coordinate repair rather than reverting only the counter. A second restore uses a fresh target or explicitly recorded current epoch. Earlier isolated PostgreSQL/TLS/key/PTY drills are historical evidence, not this environment's disaster-recovery certification. Retain a sanitized signed-off result and test your actual backup/key custody and consumer barrier.
