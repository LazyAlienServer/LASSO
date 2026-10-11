# Choose a deployment method

[Index](README.md) · [中文](../zh-CN/deployment.md)

## Selected topology

**Single-host Docker Compose, same-host Nginx, separate PostgreSQL, account-site first launch.**

Start with **[Docker + one external host Nginx](host-nginx-deployment.md)**. Official domain/HTTPS inputs are supplied at formal target launch; software fixtures do not substitute target acceptance.

| Method | Instructions | Use |
| --- | --- | --- |
| Docker Compose + host Nginx | [N01–N06 guide](host-nginx-deployment.md) | Selected first launch |
| Compose + bundled Nginx | [Alternative guide](docker-deployment.md) | Independently chosen container edge |
| Host JAR / systemd | [Step-by-step guide](jar-deployment.md) | An existing independent host-process layout |

## Supply first

| Input | Purpose |
| --- | --- |
| Official domain, HTTPS issuer, certificate and renewal | Browser edge and identity namespace |
| Host OS/architecture/CPU/RAM/disk and limits | Runtime and capacity validation |
| Secret/signing key/kid custody | Stable identity across restart and maintenance |
| Off-host backup, retention and RPO/RTO | Disaster recovery |
| Real notification channel and maintenance window | Operator response |

Account-site-only opening may have no business clients. Each consumer supplies its callbacks, authorization and integration evidence when it launches.

## Shared startup order

```text
Verify package → configuration/key → database → offline migration
→ hidden bootstrap → Web → first password change → target checks → opening
```

| Rule | Check |
| --- | --- |
| Non-development requires `production` | Never combine `local` / `test` |
| Initialize administrator before Web | Missing usable administrator rejects Web startup |
| Keep embedded Flyway validation | No checksum repair or published migration edits |
| Private app, database and metrics | Access only through the reviewed Nginx edge |

Failures: [troubleshooting](troubleshooting.md). Opening criteria: [acceptance](acceptance.md).
