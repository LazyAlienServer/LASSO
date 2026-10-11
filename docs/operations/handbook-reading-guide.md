# Release handbook navigation

[中文](../zh-CN/operations/handbook-reading-guide.md) · [Source handbook](../../release/_source/docs/en/README.md)

The revised package places Docker installation at the top of README and the handbook index. Instructions state working directory, command, expected result and failure action.

| Need | Source template / packaged page |
| --- | --- |
| Selected single external host Nginx | [host-nginx-deployment.md](../../release/_source/docs/en/host-nginx-deployment.md), N01–N06; standalone host-edge Compose |
| First Docker install | [docker-deployment.md](../../release/_source/docs/en/docker-deployment.md) → `docs/en/docker-deployment.md` |
| Startup/login failure | [troubleshooting.md](../../release/_source/docs/en/troubleshooting.md) |
| Reset/upgrade/restart | [operations.md](../../release/_source/docs/en/operations.md) |
| Old-snapshot recovery | [backup-and-recovery.md](../../release/_source/docs/en/backup-and-recovery.md) |
| History growth | [history-retention.md](../../release/_source/docs/en/history-retention.md) |
| Reference queue | [reference-revocation.md](../../release/_source/docs/en/reference-revocation.md) |
| Every operation's detailed steps | [Operation index](../../release/_source/docs/en/operation-index.md), [user](../../release/_source/docs/en/user-guide.md), [admin](../../release/_source/docs/en/administrator-guide.md), [API calls](../../release/_source/docs/en/api-operations.md) |

The Docker template-directory README is a navigation stub, not another installation procedure. Packaging rewrites its source links to local package handbook links and removes source-only build instructions.

[2026-10-11 validation](operation-handbook-validation-2026-10-11.md): 17 pages/57 workflows per language, all 59 API operations, 415 rendered links, command parsing/failure-stop and isolated SQL/backup/restore checks. The record distinguishes those checks from actual deployment and current-artifact browser acceptance.

Version `.20261009.1` and the completed `.20261009.2` remain immutable. Subsequent workflow/API source changes await a new identified package; they are not patched into historical bytes. Target domains/resource limits/custody/backup/notification inputs remain operator-owned. Procedures include actual page labels, actor/location, ordered actions, expected read-back and failure handling; plaintext and remote-result uncertainty are explicit.
