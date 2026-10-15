# Operate and upgrade

[Index](README.md) · [中文](../zh-CN/operations.md) · [Recovery](recovery.md)

At package root, use the selected default helper throughout:

```sh
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.host-nginx.yml" "$@"; }
```

Close/drain this site's host Nginx ingress before offline writes; other host sites need not stop. Keep PostgreSQL healthy and stop SSO/other writers. Record version/image/Flyway/epoch/key ID and a tested backup, not secret values.

## Check, restart, stop/resume

```sh
dc ps
dc exec -T sso curl --fail --silent --show-error --max-time 45 http://127.0.0.1:9091/actuator/health/readiness
dc restart sso
```

Wait for readiness after restart. A changed env/image/port requires reviewed `dc up -d --no-deps --force-recreate sso`; restart does not apply it. Preserve key/peppers/secrets, no repeated bootstrap.

Planned stop: drain ingress, `dc stop sso`, then `dc stop postgres` only for a full stop. Resume PostgreSQL first with `--wait`, then SSO; verify HTTPS/login/metrics before reopening. `down --volumes` is only disposable-fixture teardown.

## Offline administrator reset

1. Confirm the unique administrator/authorized window and recovery material. Stop all writers.
2. Run in a real terminal:

```sh
dc stop sso
dc --profile maintenance run --rm --no-TTY=false maintenance admin-reset-password
```

3. Verify displayed username/UUID, type YES and enter the new password twice, hidden. Nonzero/cancel stops the workflow.
4. After exit0 start SSO/readiness. Reset produces an **ACTIVE password**, not a new temporary activation. Verify new login and old-session refusal; U07 generates/stores new recovery codes.

Lost output first checks state/audit. After a committed reset repair transport/notifications rather than restoring old credentials. Administrator manual recovery through a normal web credential is unavailable.

Host-JAR/PowerShell uses external server-only config and exact artifacts:

```powershell
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
./scripts/reset-admin-password.ps1 -Profile production -JarPath (Join-Path $PWD $m.artifacts.server.path) -ExpectedSha256 $m.artifacts.server.sha256
```

## Upgrade

1. Verify new package/notes/scans; rehearse the supported populated transition. Retain old package/image/settings and schema compatibility results.
2. Back up DB and separate external config/key material. Drain host ingress; stop SSO/other writers.
3. From **new package root**, retain original env/project/PG volume/key and set the new exact SSO image. Run:

```sh
set -euo pipefail
python3 scripts/verify_docker_deployment.py --env-file /etc/las-sso/deployment.env --compose deploy/docker/compose.host-nginx.yml --topology host-nginx
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
dc up -d --wait --wait-timeout 120 sso
dc exec -T sso curl --fail --silent --show-error --max-time 45 http://127.0.0.1:9091/actuator/health/readiness
```

4. Verify HTTPS/admin/new consumer login/refresh/logout, metrics401/200 and notifications. Record versions/result and reopen gradually.

Migration is forward-only; V16 ordinary indexes need time/disk rehearsal. Old binary rollback is valid only with known new-schema compatibility. Otherwise forward repair or authorized restore with epoch/key barriers. PG major upgrades need pg_upgrade or dump/restore, not an image swap.

## TLS renewal

Install a matching verified host certificate/key with correct host-Nginx readers. `nginx -t` must pass before `systemctl reload nginx` or the host's equivalent. New connections must see the new certificate; verify renewal/failure notification. TLS renewal does not rotate OIDC signing material.

## History and daily monitoring

```sh
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_backup_password)" exec psql -X -h 127.0.0.1 -U las_sso_backup -d las_sso -v ON_ERROR_STOP=1' < scripts/history-status.sql
```

The report is read-only,10s statement/500ms lock bounds, no Token values. Supply matching `-v *_days=...` when retention is overridden. Compare candidate counts/size/fanout over time and fresh cleanup metrics; candidates are not guaranteed deletions.

Cleanup defaults5minutes/batch100 with bounded per-phase transactions, busy SKIP LOCKED and oversized-family protection. Live hints/RT, incomplete/dead work and permanent audit/identity facts are retained. Minimum terminal session/credential90days; expired authorizations/orphan revoked RT/supported completed outbox30days. Do not tune retention below safety minima.

Eligible backlog: check jobs/locks/errors/throughput. Live or permanent growth: plan capacity/retention ownership. DELETE does not shrink files automatically; normal autovacuum reuses space, FULL/reindex requires a separate window. Review disk/pool/clock/certificate/backup/notifications each day; [monitoring](monitoring.md) defines metrics.
