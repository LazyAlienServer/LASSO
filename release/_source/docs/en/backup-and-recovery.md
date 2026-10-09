# Backup and old-snapshot restoration

[Index](README.md) · [中文](../zh-CN/backup-and-recovery.md) · [Operations](operations.md)

## Declare objectives

| Input | Owner supplies |
| --- | --- |
| RPO | Acceptable loss interval |
| RTO | Recovery-time limit |
| Schedule, retention and off-host location | Frequency, encryption and independent custody |
| Failure notification / restore authority | Actual channel and responsible operator |

A Docker volume or same-host dump is not an off-host backup. Configure scheduling and any PITR/WAL strategy separately.

## 1. Inventory

| Material | Verify |
| --- | --- |
| Consistent PostgreSQL backup | Database/schema/Flyway versions, epoch, time and hash |
| Signing key/kid, peppers and configuration | Independently encrypted, readable during recovery |
| Database/key passwords and recovery codes | Separate custody, not plaintext beside the database |
| Consumer database / reference queue | Separate backup; matching AES key, endpoint and client |

Use a protected password file or connection service, never password arguments. A custom-format pg_dump is only a format choice; verify an actual isolated restore.

Snapshots cannot reconstruct later password changes, recovery-code use or account/deletion facts. Record the impact before restoring.

## 2. Restore workflow

### A. Isolate and stop writers

1. Drain ingress; stop SSO, jobs and relevant consumer writers.
2. Restore into a separately identified target. Preserve the original backup.
3. Verify schema compatibility, administrator, UUID, credential, client, audit and deletion facts.

### B. Record restored epoch

```sql
SELECT security_epoch, updated_at, change_reason
FROM system_security_state WHERE id = 1;
```

**Expected:** One current epoch to use as the expected value in the next step.

### C. Execute barrier

**Docker:** Use the isolated target's environment/project, a real terminal and the exact image:

```sh
dc() { docker compose --env-file /etc/las-sso/restore.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
dc --profile maintenance run --rm --no-TTY=false maintenance rotate-security-epoch-after-restore \
  --expected-epoch='<restored-UUID>' --reason=POSTGRES_RESTORE_APPROVED
```

`restore.env` must identify the isolated restore project/database/key; validate it using the [Docker guide](docker-deployment.md). Replace the UUID with the value queried in step B.

**JAR:** Package root with server-only configuration; no Web or monitoring profile:

```powershell
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
./scripts/rotate-security-epoch-after-restore.ps1 -Profile production -JarPath (Join-Path $PWD $m.artifacts.server.path) -ExpectedSha256 $m.artifacts.server.sha256 -ExpectedEpoch '<restored-UUID>' -Reason 'POSTGRES_RESTORE_APPROVED'
```

**Expected:** Confirm the target interactively; the new epoch and security reset commit, then the command exits 0. An epoch mismatch, missing console, Web mode or enabled jobs must fail.

### D. Check result

```sql
SELECT security_epoch FROM system_security_state WHERE id = 1;
SELECT count(*) FROM user_sessions WHERE status = 'ACTIVE';
SELECT count(*) FROM spring_session;
SELECT count(*) FROM oauth2_authorization;
SELECT count(*) FROM oauth2_refresh_token_history WHERE status <> 'REVOKED';
SELECT count(*) FROM oidc_password_challenges;
```

**Expected:** A different epoch and five zero counts. Pending outbox work may remain with old claims reset; audit and permanent facts remain retained.

### E. Key and consumer barriers

| Component | Required action |
| --- | --- |
| SSO | Install a new persistent signing key/kid; do not reopen with the restored old key |
| Consumers | Retire the old kid; clear local sessions, eligibility caches and password evidence |
| Reconciliation | Establish a new local generation through fresh authoritative queries; reject old in-flight responses |

Epoch is opaque and cannot be sorted. Compare versions only within one epoch; do not switch generations on every arriving response.

### F. Verify and open

| Check | Criterion |
| --- | --- |
| SSO | Readiness, new Discovery/JWKS, login and UserInfo pass |
| Old state | Old Cookie/code/AT/RT rejected |
| Launched consumers | New refresh, local/global logout and Back-channel pass |
| Jobs | Pending outbox and due deletion work progress correctly |
| Operator | RPO/RTO measured, acknowledgement recorded, gradual opening |

## Failure actions

| Failure | Action |
| --- | --- |
| Epoch transaction | Stay closed; inspect target, epoch, database and grants |
| Key/consumer failure after commit | Preserve the new epoch and coordinate repair |
| Another restore | Use a fresh target or explicitly recorded current epoch, not the old expected value |

The database transaction cannot atomically replace external keys/caches. Copying the database or rolling back a JAR alone is not recovery. Historical drills do not verify the target backup.
