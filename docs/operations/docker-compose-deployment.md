# Docker Compose deployment and confirmed environment inputs

[中文](../zh-CN/operations/docker-compose-deployment.md) · [Executable instructions](../../las-sso-server/deploy/docker/README.md) · [Launch plan](../implementation/production-launch-plan.md)

Updated2026-10-09. This records user-confirmed scope and local implementation, not a production deployment approval.

## Confirmed scope and responsibility

| Input | Confirmed value |
| --- | --- |
| First opening | SSO account site; no business consumer required beforehand |
| Topology | Single-host Docker Compose; one SSO and separate PostgreSQL/Nginx containers |
| Public edge | Same-host Nginx HTTPS; no CDN initially |
| Private boundary | No host ports for app9000, management9091 or PostgreSQL; reviewed proxy-only ingress |
| Database | Same host initially; backup independently off-host |
| Release/deployment/on-call/manual recovery/key/backup custody | User personally owns initial responsibilities |
| Consumer rollout | LASLOG/AlienCommon registration, integration and gates apply when each launches |

## Inputs still to supply

| Input | Value/status | Required evidence |
| --- | --- | --- |
| Preproduction/production domain, issuer, DNS | Pending | Exact canonical host and public CA, no fixture/example |
| Host OS/architecture/CPU/RAM/disk/free space/time sync | Pending | Provisioned target identity and resource/headroom limits |
| User/data counts, peak rates, concurrency, latency/error limits | Pending | P13 representative ramp/soak and initial opening cap |
| TLS renewal/reload, external firewall/interface | Pending | P06 external probes and renewal/expiry rehearsal |
| Backup destination/encryption/schedule/retention | Pending | Independent off-host recoverable backup and failure notification |
| RPO/RTO | Pending | Operator-approved targets and measured P10 restore |
| Alert/support channel and escalation | Pending | Real recipient delivery/acknowledgement/resolved evidence |
| Key/secret/recovery-code storage and access | Owner confirmed; method pending | Linux modes/mounts, separation, restart/rotation and operator custody |
| Candidate/version/storage/maintenance window | Pending | New immutable artifacts and final target rehearsal |

Do not adopt earlier runbook user-count/concurrency assumptions as current targets. Supply no passwords, keys, Tokens or recovery codes in this matrix. The non-secret `deployment.env.example` deliberately leaves image references, host, paths and resource limits blank; the verifier refuses incomplete/mismatched inputs.

## P06–P10 progress and gates

| Task | Local evidence | Still required on target |
| --- | --- | --- |
| P06 | Compose isolation, rootless/read-only app/proxy, real fixture TLS/canonical forwarding/unknown Host/private ports, DB outage/recovery | Real Linux host/firewall, official DNS/public CA/renewal, external checks; fixture SPKI exception is not CA acceptance |
| P07 | File configtree injection without raw secret env/build args, stable signing key/session across restart, console-only passwords | Actual Linux UID/ACL, owner storage/separation/rotation and recoverable custody |
| P08 | Empty V1–V16 migrations, repeatable non-Web command, migration/runtime/backup role checks, read-only Flyway history and backup-role dump | Representative populated target migrations, time/disk, rollback and operational writer/job procedure |
| P09 | Private authenticated management, health/error behavior, bounded logs | Actual collector/Grafana/real notification channel/coverage/rotation |
| P10 | Hidden bootstrap and forced change; backup role can produce custom dump | Encrypted scheduled off-host backup, actual restore/key+epoch barrier, measured RPO/RTO and owner rehearsal |

See [local acceptance](../implementation/docker-compose-acceptance-2026-10-09.md) for exact artifacts/scans and coverage limits. Existing systemd/JAR instructions remain a separate deployment method; container config is not interchangeable with loopback host configuration. The historical `0.1.0-dev.20261007.1` package remains immutable and does not contain these changes. The next new package copies Docker templates/tools; this work does not create or authorize a production release.
