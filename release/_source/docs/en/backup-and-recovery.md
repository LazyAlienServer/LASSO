# Backup and old-snapshot restoration

[Index](README.md) · [中文](../zh-CN/backup-and-recovery.md) · [Operations](operations.md)

**Host edge:** Use [N02/N05](host-nginx-deployment.md)'s standalone helper, close host-site ingress, then `dc stop sso` instead of bundled `dc stop proxy sso`. B02's separate restore helper uses compose.host-nginx.yml and validator `--topology host-nginx`; still start only PostgreSQL before restore and keep Web closed through the same epoch/key barriers. Code below shows the bundled alternative; do not overwrite the selected helper accidentally.

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

## B01 Create, inspect and retain one off-host backup

**Prerequisites:** Bash at package root, a verified source `dc` helper, the backup role, a protected existing directory and the selected encryption/off-host destination. This creates one logical dump; scheduling and WAL archiving are configured separately.

1. Record source version/schema/epoch, UTC start, destination and authorized window. Stop ingress, SSO and every other writer; keep PostgreSQL healthy for a clear maintenance snapshot.
2. Precreate the restricted external directory with its owner and choose a nonexistent destination. Run the block in Bash with the verified source `dc` helper in the same shell; stop on the first failure. Bash supports binary redirection; Windows PowerShell 5.1 text pipelines must not carry custom dump bytes.

```sh
set -euo pipefail
set -o noclobber
umask 077
BACKUP_DIR='/var/backups/las-sso'
test -d "$BACKUP_DIR" || exit 1
BACKUP="$BACKUP_DIR/las-sso-$(date -u +%Y%m%dT%H%M%SZ).dump"
test ! -e "$BACKUP" || exit 1
dc stop proxy sso
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_backup_password)" exec pg_dump -h 127.0.0.1 -U las_sso_backup -d las_sso --format=custom' > "$BACKUP"
test -s "$BACKUP" || exit 1
dc exec -T postgres pg_restore --list < "$BACKUP" > "$BACKUP.list"
sha256sum "$BACKUP"
```

3. Check both dump/list exit 0; a failed/truncated file is not success and must not replace the last good backup. If the block stops, record the failed attempt and follow the approved source-service resume procedure. The list contains object metadata; keep it private.
4. Encrypt using the operator-selected mechanism and transfer to independent host/storage. Verify the encrypted archive's hash, readability and authorized decryption. Keep keys/peppers/configuration/passwords/codes under separate custody; no key material belongs in the report.
5. Record time, schema, hash, storage reference and outcome. Resume the source and verify readiness before reopening. Install the chosen schedule/retention/failure notification and perform B02; an archive list alone is not recovery proof.

**Expected:** A consistent dump, an encrypted off-host copy and a resumed source. **Failure:** Permission errors require checking backup grants and migration ownership, not temporarily making the runtime a superuser. The password enters the dump process from a protected file, not argv.

## B02 Restore into a separate empty target and apply barrier

**Expected:** The isolated target has the restored data and correct owner/grants, then a new epoch/key and verified old-state/consumer barriers before cutover.

**Prerequisites:** An explicitly identified isolated target, retained source and protected decrypted dump. Follow the Docker guide with a distinct restore.env/project/volume/secrets/key. Initialize PostgreSQL roles only; **do not migrate empty application tables before restore**.

1. Verify the target helper and input validator identify project/images/ports/paths separate from the source. Start only PostgreSQL and wait for health. Confirm there are no existing application objects/data to overwrite.
2. Stream the verified binary dump as the migrator, which owns the restored objects. No DROP/clean command targets the source.

```sh
set -euo pipefail
dc() { docker compose --env-file /etc/las-sso/restore.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
python3 scripts/verify_docker_deployment.py --env-file /etc/las-sso/restore.env --compose deploy/docker/compose.yml
dc up -d --wait --wait-timeout 120 postgres
RESTORED_DUMP='/var/backups/las-sso/selected-restored.dump'
test -s "$RESTORED_DUMP" || exit 1
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_migrator_password)" exec pg_restore -h 127.0.0.1 -U las_sso_migrator -d las_sso --no-owner --no-acl --exit-on-error --single-transaction' < "$RESTORED_DUMP"
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
```

3. Check every command exits 0. Post-restore migration validates the history and permits only a reviewed forward upgrade. Query the target epoch using section 2B; verify UUID/Flyway/administrator/permanent facts and migrator ownership.
4. Keep Web stopped. Execute section 2C's real-TTY expected-epoch barrier and 2D's count checks, then install 2E's new signing key/kid, supported peppers and consumer retirement/cache/generation barriers.
5. Perform section 2F isolated smoke, old-state negatives, new login, notifications and job checks. Measure RPO from the backup boundary and RTO from the incident to usable service before considering cutover.
6. Drill cleanup targets only the isolated project. A real restoration records the final service/configuration and deliberately retains the source/backup.

**Failure:** Keep a failed restore closed and inspect schema/role/version; never clean the source. After a committed barrier, preserve the new epoch while repairing external components. Validate major-version/extension compatibility explicitly.
