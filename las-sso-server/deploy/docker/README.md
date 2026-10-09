# Docker deployment files

[中文](README.zh-CN.md) · **[Complete Docker tutorial](../../../release/_source/docs/en/docker-deployment.md)**

Selected topology: single-host Compose, same-host Nginx, separate SSO/PostgreSQL, no initial CDN.

## Choose a task

| Task | Read |
| --- | --- |
| First installation from package | [Docker tutorial](../../../release/_source/docs/en/docker-deployment.md) |
| Maintenance, reset, upgrade | [Operations](../../../release/_source/docs/en/operations.md) |
| Startup/login failure | [Troubleshooting](../../../release/_source/docs/en/troubleshooting.md) |
| Confirmed target inputs | [Environment matrix](../../../docs/operations/docker-compose-deployment.md) |

Source handbooks are templates; packaging substitutes `{{VERSION}}`. Extracted packages have exact versions and need no source/Git.

## Files

| File | Purpose |
| --- | --- |
| `compose.yml` | SSO, PostgreSQL, Nginx and offline maintenance |
| `Dockerfile` | Exact JAR/hash, non-root SSO image |
| `postgres.Dockerfile` / `proxy.Dockerfile` | Pinned bases with build-time security updates |
| `deployment.env.example` | Non-secret hostname/path/image/resource inputs |
| `server.properties.example` | Internal app/management binding and configtree |
| `postgres-init.sh` | Empty-volume roles/default grants |
| `postgres-runtime-permissions.sql` | Read-only runtime Flyway history after each migration |
| `server-entrypoint.sh` | Fixed Web/offline launch modes |
| `proxy-entrypoint.sh` / `nginx.conf.template` | Canonical TLS/forwarding boundaries |

## Source-build entry

**Working directory: repository root.** Create a new clean strict version first.

```text
python scripts/package_release.py build <new-version>
python scripts/build_docker_image.py --jar <exact-server.jar> --expected-sha256 <trusted-hash> --version <new-version> --tag <chosen-tag> --build-evidence <build-evidence.json>
python scripts/build_docker_infrastructure.py --postgres-tag <db-tag> --proxy-tag <proxy-tag> --output-dir <external-existing-parent/new-receipts>
```

| Check | Expected |
| --- | --- |
| Source/version/JAR hash | Matches clean-build evidence |
| Compose image identity | Immutable ID/digest, not mutable tag |
| App/database/management | No host-published ports |
| Password maintenance | Actual hidden TTY, no password argv |

`--allow-dirty-fixture` is development-only. DNS, Linux UID/ACL, TLS, backups, notifications and capacity require target acceptance.
