# Database And Embedded Flyway

The executable server contains authoritative `BOOT-INF/classes/db/migration/V1...V16` SQL. Packaging extracts names/hashes into migration-manifest.json, not a manual SQL deployment. Flyway validates/applies against externally selected PostgreSQL; provision reviewed migration/runtime roles, not package passwords. V16 creates runtime-history indexes with ordinary Flyway DDL; rehearse populated upgrade time/disk and drain writers. Older P02 binaries reject a future schema, so do not assume file-only rollback.

V13: service-access counters/timestamps. V14: atomic password-reauthentication challenge completion/consumption. V15: HMAC-only issued ID Token hint association, indexed authorization FK with deletion cascade. New hint history needs growth/authorization-cleanup/pepper-rotation planning; it is not an independent login/Bearer credential.

Migrate a fresh DB and a representative prior-schema copy first. Record Flyway schema history and backup before changing live schema. Existing-version migrations are append-only; never modify checksums to bypass validation. Do not automatically drop V15 for rollback. An old application might be schema-compatible but lose refreshed logout continuity; choose a validated application/schema combination or restore to an isolated target with full epoch/key/consumer security barriers.

[中文部署](../docs/zh-CN/deployment.md) / [English deployment](../docs/en/deployment.md), [恢复](../docs/zh-CN/backup-and-recovery.md) / [Recovery](../docs/en/backup-and-recovery.md).
