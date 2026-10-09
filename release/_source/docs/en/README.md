# LAS SSO {{VERSION}} — Operator handbook

[Package entry](../../README.md) · [中文](../zh-CN/README.md) · [Release notes](../../RELEASE-NOTES.md)

## Choose a task

| Task | Start here | Next |
| --- | --- | --- |
| First deployment | **[Docker deployment](docker-deployment.md)** | [Monitoring](monitoring.md), [opening checks](acceptance.md) |
| Existing host JAR/systemd layout | [JAR deployment](jar-deployment.md) | [Opening checks](acceptance.md) |
| Unreachable service or failed login | **[Troubleshooting](troubleshooting.md)** | Follow the symptom branch |
| Restart, administrator reset, upgrade | [Operations](operations.md) | Verify the result before reopening |
| Backup or disaster restoration | [Backup and recovery](backup-and-recovery.md) | Record recovery time and old-state rejection |
| Integrate a business website | [External integration](external-integration.md) | Complete consumer acceptance |
| Account and user administration | [User guide](user-guide.md) | Preserve one-time output privately |
| Growing history or cleanup metrics | [History retention](history-retention.md) | Read the status report |
| Reference BFF revocation dead tasks | [Reference queue](reference-revocation.md) | Recover the exact task; sample-specific |

## Conventions

- Each procedure states its **working directory**; Docker instructions use the package root.
- **Expected results** determine whether to continue. Follow the failure table when they differ.
- `{{VERSION}}` identifies this package; `<...>` marks an operator-supplied value.
- Supply real paths and reviewed resource limits. Empty template values fail validation.
- Passwords come from protected files or hidden terminal prompts.

## First-launch topology

```text
Browser → Nginx HTTPS → SSO → PostgreSQL
                       └→ private management 9091 → collector
```

| Component | Exposure |
| --- | --- |
| Nginx | Reviewed HTTP/HTTPS host interface |
| SSO 9000, management 9091, database | No host port mappings |
| Backup | Encrypted off-host storage, configured separately |

SSO owns identity, credentials, account lifecycle and eligibility. Business roles, Minecraft admission and consumer high-risk actions remain business-owned.
