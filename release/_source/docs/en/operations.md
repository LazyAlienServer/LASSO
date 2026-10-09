# Operations: checks, restart, reset and upgrade

[Index](README.md) · [中文](../zh-CN/operations.md) · [Troubleshooting](troubleshooting.md)

| Task | Procedure |
| --- | --- |
| Daily checks | Table below |
| Restart / administrator reset | Sections 1–2 |
| Upgrade / rollback | Section 3 |
| History growth / cleanup | [History](history-retention.md) |
| Manual recovery / deletion dispute | [Administrator guide](user-guide.md) |
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
