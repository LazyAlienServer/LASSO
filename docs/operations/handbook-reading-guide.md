# Release handbook navigation

[中文](../zh-CN/operations/handbook-reading-guide.md) · [Source handbook](../../release/_source/docs/en/README.md)

The revised package places Docker installation at the top of README and the handbook index. Instructions state working directory, command, expected result and failure action.

| Need | Source template / packaged page |
| --- | --- |
| First Docker install | [docker-deployment.md](../../release/_source/docs/en/docker-deployment.md) → `docs/en/docker-deployment.md` |
| Startup/login failure | [troubleshooting.md](../../release/_source/docs/en/troubleshooting.md) |
| Reset/upgrade/restart | [operations.md](../../release/_source/docs/en/operations.md) |
| Old-snapshot recovery | [backup-and-recovery.md](../../release/_source/docs/en/backup-and-recovery.md) |
| History growth | [history-retention.md](../../release/_source/docs/en/history-retention.md) |
| Reference queue | [reference-revocation.md](../../release/_source/docs/en/reference-revocation.md) |

The Docker template-directory README is a navigation stub, not another installation procedure. Packaging rewrites its source links to local package handbook links and removes source-only build instructions.

Version `.20261009.1` remains immutable. New handbook bytes are delivered under `.20261009.2`, preserving authentication code, locks and migrations. Target domains/resource limits/custody/backup/notification inputs remain operator-owned.
