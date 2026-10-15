# LAS SSO handbook

[中文](../zh-CN/README.md)

| Task | Read |
| --- | --- |
| Understand modules and identity | [Architecture](architecture.md) |
| Understand trust, credentials and sessions | [Security](security.md) |
| Install SSO behind one host Nginx | [Deployment](deployment.md) |
| Supply environment and secrets | [Configuration](configuration.md) |
| Restart, reset administrator, upgrade | [Operations](operations.md) |
| Back up or restore | [Recovery](recovery.md) |
| Collect metrics and alerts | [Monitoring](monitoring.md) |
| Diagnose a failed startup or request | [Troubleshooting](troubleshooting.md) |
| Register, change password, manage account | [Users](users.md) |
| Manage users and OIDC clients | [Administrators](administrators.md) |
| Call implemented endpoints | [API](api.md), [OpenAPI](../../api/openapi.json) |
| Integrate a backend/BFF | [Integration](integration.md) |
| Run reference BFF and its optional queue | [Reference sample](reference-bff.md) |

Use the selected version's README and manifest for exact artifacts. Commands in deployment/operations run from the extracted package root; development commands run from the source checkout. Replace example paths with the reviewed target.

The default deployment is one SSO/PostgreSQL Compose and one external Nginx on the same host. Official domain/public HTTPS is supplied at formal launch; isolated software fixtures are not target acceptance.

Current guides contain operational rules. [ADRs](https://github.com/LazyAlienServer/LASSO/tree/main/docs/adr) record decisions; [archived evidence](https://github.com/LazyAlienServer/LASSO/tree/main/docs/archive) records dated verification. Build and contribution instructions are in the repository's CONTRIBUTING.md.
