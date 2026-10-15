# Backup and restore

[Index](README.md) · [中文](../zh-CN/recovery.md)

Declare RPO/RTO, frequency, retention, encryption/off-host location and failure recipient. A same-host dump or Docker volume is not an off-host backup. Keep database, signing material/peppers/configuration and password/code custody independently protected. Reference/consumer storage has separate backups.

## Create one backup

Package-root Bash with the **source** host-edge helper from [deployment](deployment.md), existing protected backup directory and stopped writers. Drain host ingress, then:

```sh
set -euo pipefail
set -o noclobber
umask 077
BACKUP_DIR='/var/backups/las-sso'
test -d "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/las-sso-$(date -u +%Y%m%dT%H%M%SZ).dump"
dc stop sso
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_backup_password)" exec pg_dump -h 127.0.0.1 -U las_sso_backup -d las_sso --format=custom' > "$BACKUP"
test -s "$BACKUP"
dc exec -T postgres pg_restore --list < "$BACKUP" > "$BACKUP.list"
sha256sum "$BACKUP"
```

Require dump/list exit0; truncated/failed output never replaces a good backup. Bash handles binary bytes; Windows PowerShell5.1 text pipelines do not. Encrypt/transfer with the selected mechanism, verify remote hash and authorized decryption. Resume source/readiness before reopening; install real scheduling/retention/notification. Listing alone is not restoration proof.

## Restore to a separate empty target

Prepare distinct restore.env/project/PG volume and independent target DB passwords. Retain supported source peppers and a **new** signing pair/kid. Initialize PG roles only; do not create application tables before restore. Preserve source/backup. With target helper:

```sh
set -euo pipefail
dc() { docker compose --env-file /etc/las-sso/restore.env -f "$PWD/deploy/docker/compose.host-nginx.yml" "$@"; }
python3 scripts/verify_docker_deployment.py --env-file /etc/las-sso/restore.env --compose deploy/docker/compose.host-nginx.yml --topology host-nginx
dc up -d --wait --wait-timeout 120 postgres
RESTORED_DUMP='/var/backups/las-sso/selected-restored.dump'
test -s "$RESTORED_DUMP"
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_migrator_password)" exec pg_restore -h 127.0.0.1 -U las_sso_migrator -d las_sso --no-owner --no-acl --exit-on-error --single-transaction' < "$RESTORED_DUMP"
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
```

Stop on any failure. Verify Flyway/checksums, migrator ownership, retained UUID/admin/credential/client/audit/deletion facts and reviewed version/extension compatibility. Older snapshots cannot reconstruct later password changes, used recovery codes or deletion facts; record that impact.

## Apply the security barrier before Web

Query the **target** in a controlled DB session:

```sql
SELECT security_epoch, updated_at, change_reason FROM system_security_state WHERE id=1;
```

Use this actual expected epoch, same exact image, no Web/jobs/parallel writers, real terminal:

```sh
dc --profile maintenance run --rm --no-TTY=false maintenance rotate-security-epoch-after-restore \
  --expected-epoch='<restored-epoch>' --reason=POSTGRES_RESTORE_APPROVED
```

Confirm `ROTATE-EPOCH-AFTER-RESTORE`; exit0 commits new epoch/security reset. Query:

```sql
SELECT security_epoch FROM system_security_state WHERE id=1;
SELECT count(*) FROM user_sessions WHERE status='ACTIVE';
SELECT count(*) FROM spring_session;
SELECT count(*) FROM oauth2_authorization;
SELECT count(*) FROM oauth2_refresh_token_history WHERE status<>'REVOKED';
SELECT count(*) FROM oidc_password_challenges;
```

Expected different epoch and five zeros; permanent facts/audit remain, pending outbox may remain with stale claims cleared. No-console/cancel/expected mismatch must fail. Host-JAR alternative uses the packaged rotate-security-epoch-after-restore.ps1 with production/server-only settings and exact JarPath/ExpectedSha256.

## Verify, coordinate and cut over

1. Install new signing pair/kid. Consumers retire old kid, end old local sessions/password evidence/cache, and reconcile under a new generation; reject old in-flight replies. Never sort opaque epochs.
2. Start target Web; check readiness, discovery/new JWKS, fresh login/UserInfo/refresh/logout, old Cookie/code/AT/RT refusal, pending notifications/deletion jobs and each launched consumer.
3. Measure actual data-loss boundary/recovery time against RPO/RTO, obtain owner acceptance before cutover. Retain source/backup deliberately.

Failure before commit stays closed. Failure after epoch commit preserves that new epoch while external pieces are repaired; another restore needs an explicitly identified target/current epoch. Cleanup applies only to the declared disposable project. A binary swap alone is not rollback across an incompatible schema or restored security state.
