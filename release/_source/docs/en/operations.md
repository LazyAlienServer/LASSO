# Operations: checks, restart, reset and upgrade

[Index](README.md) · [中文](../zh-CN/operations.md) · [Troubleshooting](troubleshooting.md)

**Selected host edge:** Follow [N05](host-nginx-deployment.md) for the compose.host-nginx.yml helper, stop/start only SSO after controlling host ingress, and use validator `--topology host-nginx`. The `proxy` snippets below describe the bundled alternative; do not switch a host-edge helper back to compose.yml. Host TLS renewals use `nginx -t` then the host reload method and permissions for the actual host reader.

| Task | Procedure |
| --- | --- |
| Daily checks | Table below |
| Restart / administrator reset | Sections 1–2 |
| Upgrade / rollback | Section 3 |
| History growth / cleanup | [History](history-retention.md) |
| Manual recovery / deletion dispute | [Administrator guide](administrator-guide.md) |
| Reference dead task / queue key | [Reference queue](reference-revocation.md) |
| Backup / snapshot restore | [Recovery](backup-and-recovery.md) |

## Daily checks

| Check | Normal result | Investigate |
| --- | --- | --- |
| Readiness | DB-aware `UP` | [DB/startup](troubleshooting.md) |
| Scrape / snapshot | Available and fresh | [Monitoring](monitoring.md) |
| Login/admin failure / RT reuse | Explainable risk events | Audit, concurrency/exposure |
| Outbox age/pending/dead | Advancing work, tracked dead tasks | [Troubleshooting](troubleshooting.md) |
| Pool/disk/history | Headroom and sufficient disposal | [History](history-retention.md) |
| Certificate/backup/notifications | Renewal, backup and delivery work | [Backup](backup-and-recovery.md), [monitoring](monitoring.md) |

Retain version/commit/artifact hash/non-secret config revision/PG/JDK/proxy/Flyway state.

## 1. Docker commands

**Working directory: package root.** Same helper as [deployment](docker-deployment.md):

```sh
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
dc ps
dc restart sso
dc ps
```

**Expected:** Healthy SSO with same key/kid. Preserve secrets. Default consumer BFF restart ends business login; use top-level SSO reentry.

## 2. Offline administrator reset

**Before:** Drain ingress, stop Web writers, prepare backup and authorized maintenance.

```sh
dc stop proxy sso
dc --profile maintenance run --rm --no-TTY=false maintenance admin-reset-password
```

| Prompt | Action |
| --- | --- |
| Administrator username/UUID | Confirm unique target |
| Type YES | Confirm reset |
| Two password prompts | Hidden inputs, no pipe/recording |

**Success:** Exit 0; new credential ACTIVE; old password/session/authorization/recovery material invalidated. Reset is not bootstrap's temporary credential.

```sh
dc up -d sso proxy
dc ps
```

Verify fresh login/old-session rejection, regenerate recovery codes, then reopen.

### Host JAR / PowerShell

Package root, real terminal, same protected server-only config:

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
./scripts/reset-admin-password.ps1 -Profile production -JarPath (Join-Path $PWD $m.artifacts.server.path) -ExpectedSha256 $m.artifacts.server.sha256
```

Hash mismatch rejects before Java. No wildcard/latest JAR selection. Bootstrap: [Docker](docker-deployment.md) / [JAR](jar-deployment.md).

## 3. Upgrade and rollback

| Order | Action | Continue when |
| --- | --- | --- |
| 1 | Verify candidate and scan, rehearse populated upgrade | Version-specific evidence |
| 2 | Verify backup, record epoch/Flyway/old images/config | Recovery material available |
| 3 | Stop proxy/sso and other writers | No parallel writers/workers |
| 4 | Select exact new image, offline migration | Exit 0 |
| 5 | Runtime permission SQL | Flyway history read-only |
| 6 | Start and smoke readiness/TLS/admin/launched consumers | Required paths pass |
| 7 | Record version/time/result, reopen gradually | Monitoring/notifications healthy |

- V16 ordinary indexes require representative time/disk rehearsal.
- Forward-only Flyway; binary rollback needs verified schema compatibility. Production rejects unknown future migrations.
- Snapshot restore uses [epoch/key/consumer barriers](backup-and-recovery.md), not just JAR replacement.
- `down --volumes` deletes data; not upgrade/restart/rollback.

## 4. Incident record

| Field | Value |
| --- | --- |
| Time/environment/version | UTC, target, image/JAR hash |
| Symptom | Route, stable category, requestId |
| Impact/action | Drain/recovery/authorization reference |
| Checks | Readiness/login/old-state rejection |
| Follow-up | Owner/review time |

No passwords/codes/Tokens/full Cookies/private keys/queue ciphertext. Unavailable SSO is not an account ban.

## O01 Daily checks, stop and resume

**Prerequisites/location:** Installed instance/package-root Bash, section1 dc helper; verify intended project/env.

1. Use `dc ps` to check healthy services, selected images and proxy-only published ports. Check private readiness with curl `--max-time 45` to allow for database waits; check liveness separately.
2. Check collector UP/fresh snapshots and host disk/backup/certificate/clock. Inspect bounded backlog metadata privately; do not share raw logs.
3. For a planned stop, notify affected users and drain ingress, then `dc stop proxy sso`. A whole-instance stop subsequently uses `dc stop postgres`. Preserve data; never use down --volumes for this workflow.
4. Resume PostgreSQL first and wait for health, then SSO/proxy. Verify HTTPS login, discovery and metrics before opening ingress.
5. An SSO-only restart preserves key/peppers/secrets and needs no new bootstrap. Consumer restart logout and SSO persistence are separate policies.

**Expected:** Same project/volume/key and restored health/notifications. **Failure:** DB503 repairs DB/pool before repeated restart; startup gate failures diagnosed. `restart` does not apply edited Compose env/image; configuration needs reviewed up/recreate.

## O02 Complete offline administrator reset

**Expected:** Offline reset commits once, the administrator logs in with the new ACTIVE password, old state is refused and recovery custody is renewed.

1. Confirm the unique administrator UUID, authorization and maintenance window. Record version/Flyway/epoch/key ID and the tested backup.
2. Define `dc`, stop proxy/SSO/all related writers and keep PostgreSQL healthy. Prevent concurrent maintenance.
3. Run section 2 in a real TTY, verify the displayed identity, then enter YES and the new password twice. Cancellation or nonzero exit stops the workflow.
4. After exit 0, record audit/time, start SSO/proxy and check readiness. Log in with the new ACTIVE password; this is not another temporary activation.
5. Regenerate and store codes through U07. Verify old-password/session refusal in isolation and new consumer login/notifications before completing the window.

**Failure:** Lost output first checks authorized state/audit, no speculative repeated reset. Correct console/policy; after committed reset repair notification/network rather than old hashes/epoch.

## O03 Software upgrade preserving configuration/data

**Expected:** The exact new software runs against the retained database/key/configuration, with validated migrations, runtime grants and supported smoke paths.

**Failure:** Keep ingress closed and use the rollback/forward-repair criteria below; do not skip a failed migration or grant step.

**Prerequisites:** Intact candidate/exact images, retained old package/images/non-secret settings, schema compatibility and isolated populated upgrade rehearsal. **Location:** New extracted package-root Bash. External secrets/key/TLS/config persist; retain same `LAS_SSO_PROJECT` and existing PG volume, not a new empty project.

1. Verify package/manifest/source/hash and build or load the verified new image. Record old/new SSO IDs; unchanged database/proxy images retain their old IDs.
2. Review release notes, Compose, properties and migrations. Ordinary upgrades do not rotate secrets/key or bootstrap again. Merge required new properties; never overwrite live configuration with an example.
3. Create a [consistent backup](backup-and-recovery.md) and separate external-configuration backup. Check recovery material/space, close ingress and stop all writers.
4. Use the new package's Compose and original external environment/project. Update the SSO image to its exact ID, retain the old value and verify the existing database/volume/network. The validator must exit 0 before migration.

```sh
set -euo pipefail
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
python3 scripts/verify_docker_deployment.py --env-file /etc/las-sso/deployment.env --compose deploy/docker/compose.yml
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
dc up -d --wait --wait-timeout 120 sso proxy
dc ps
dc exec -T sso curl --fail --silent --max-time 45 http://127.0.0.1:9091/actuator/health/readiness
```

5. Check each command exits 0 before continuing; no new migration still requires validating old checksums. Wait for health, then check account/admin/launched-consumer login, refresh, current/global logout, metrics 401/200 and notifications.
6. Retain version/image/schema/configuration revision and results without secrets. Reopen gradually and observe resources/errors/queues. DNS/secrets do not need recreating for each software update.

**Failure/rollback:** Stay closed, inspect actual migration commits. Old binary swap is not safe without tested new-schema compatibility; use retained matching image/Compose/config only when compatible, otherwise forward repair or authorized isolated restore/new epoch/key/consumer barrier. PG major-version upgrade separately needs dump/restore or pg_upgrade; image replacement alone is insufficient.

## O04 TLS renewal and reload

**Prerequisites:** Actual CA renewal procedure, verified SAN/chain/expiry/key pair; external TLS only, not signing.p12.

1. Obtain renewal in protected storage, retain correct UID101 read/ACL and private rollback material, verify hostname/chain.
2. Install files, proxy nginx test first, reload only after success.

```sh
set -euo pipefail
dc exec -T proxy nginx -t
dc exec -T proxy nginx -s reload
```

3. New client TLS connection verifies new expiry/no exception/login/discovery; verify renewal scheduler/failure alert.
4. Mount inode/path/read failures require investigation and possibly reviewed proxy recreation, not disabled CA checks/world-readable key.

**Expected:** New certificate actually served, unchanged issuer/OIDC signing key. **Failure:** Failed test leaves existing workers/certificate, repair/retest; fixture no-certificate-check health probe is not renewal evidence.
