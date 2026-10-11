# LAS SSO {{VERSION}} — Operator handbook

[Package entry](../../README.md) · [中文](../zh-CN/README.md) · [Release notes](../../RELEASE-NOTES.md)

## Choose a task

**[Complete operation workflow index](operation-index.md)**: user U, admin A, API F, operations O, backup B, monitoring M and related numbered procedures.

| Task | Start here | Next |
| --- | --- | --- |
| Selected first deployment | **[Docker + external host Nginx](host-nginx-deployment.md)** | [Monitoring](monitoring.md), [opening checks](acceptance.md) |
| Bundled Nginx alternative | [Bundled Docker deployment](docker-deployment.md) | Separate service/helper selection |
| Existing host JAR/systemd layout | [JAR deployment](jar-deployment.md) | [Opening checks](acceptance.md) |
| Unreachable service or failed login | **[Troubleshooting](troubleshooting.md)** | Follow the symptom branch |
| Restart, administrator reset, upgrade | [Operations](operations.md) | Verify the result before reopening |
| Backup or disaster restoration | [Backup and recovery](backup-and-recovery.md) | Record recovery time and old-state rejection |
| Integrate a business website | [External integration](external-integration.md) | Complete consumer acceptance |
| Request fields/authentication/errors | [API reference](api-reference.md) | [Call workflows](api-operations.md), [OpenAPI 3.1](../../api/openapi.json) |
| Personal account | [User procedures](user-guide.md) | Preserve one-time output |
| Admin users/clients/deletion/audit | [Administrator procedures](administrator-guide.md) | Verify target/audit/one-time output |
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
| SSO 9000 | Host edge: loopback-only publication; bundled edge: no host mapping |
| Management 9091, database | No host port mappings |
| Backup | Encrypted off-host storage, configured separately |

SSO owns identity, credentials, account lifecycle and eligibility. Business roles, Minecraft admission and consumer high-risk actions remain business-owned.
