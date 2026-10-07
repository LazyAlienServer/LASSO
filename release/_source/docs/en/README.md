# LAS SSO {{VERSION}} — Handbook Index

For operators, client developers and account users. All paths are relative to the extracted package. [中文](../zh-CN/README.md) · [Package entry](../../README.md) · [Release notes](../../RELEASE-NOTES.md)

| Handbook | Use |
| --- | --- |
| [Deployment](deployment.md) | Prepare external settings, database, signing key, proxy, initialization and startup |
| [Operations](operations.md) | Daily checks, maintenance commands, upgrades and incidents |
| [External integration](external-integration.md) | Generic OIDC, Starter and LAS first-party contracts |
| [User guide](user-guide.md) | Registration, account security, recovery and administrator pages |
| [Backup and recovery](backup-and-recovery.md) | Custody, PostgreSQL restore, epoch/key and consumer barriers |
| [Monitoring](monitoring.md) | Private metrics, Prometheus/Grafana/Alertmanager and alert handling |
| [Troubleshooting](troubleshooting.md) | Diagnose discovery, login, refresh, logout, migration and metrics failures |
| [Acceptance](acceptance.md) | Package/build evidence and environment-specific acceptance requirements |

SSO owns identity, credentials, account lifecycle and service eligibility. Consumers own membership, business roles, Minecraft identity/admission and high-risk operations. A reference BFF is a sample. Successful SSO login does not grant business permissions. No production approval is implied by this development delivery.
