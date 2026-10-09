# ADR-0016: Single-host Compose account-site first launch

- Status: accepted scope/topology, 2026-10-09; target acceptance pending
- [中文](../zh-CN/adr/0016-single-host-compose-account-site-launch.md)

## Decision

The user selected single-host Docker Compose, same-host Nginx HTTPS without an initial CDN, separate SSO/PostgreSQL containers and SSO account-site-only first opening. Application/database/management ports are private. The user owns initial deployment/on-call/manual recovery/key and backup custody. Database is initially on the host, with independent off-host backup. Domains, actual hardware/traffic, RPO/RTO, storage/notification methods and candidate/window remain unresolved inputs, not inferred defaults.

Implement immutable identified images, protected external file configuration/secrets, unprivileged read-only SSO/proxy, separate migration/runtime/backup DB roles and offline migrations before bootstrap/Web. Keep Flyway enabled and embedded, with runtime read-only schema history and no DDL. A non-interactive `database-migrate` may validate offline completion without a console; password/epoch commands keep actual interactive confirmation. Docker/Compose files are executable templates, not production provisioning approval.

## Consequences

SSO account-site launch does not wait for LASLOG/AlienCommon P11/P12; each consumer must satisfy those gates before its own launch. SSO's own target transport/custody/backup/monitoring/capacity/security/final candidate gates remain required. No MFA/email/business Claims or multi-instance scope is added. Systemd/host-loopback configurations are separate. Later CDN/multi-host/replica/prefix changes need corresponding new network/trust/custody acceptance.

Pinned upstream infrastructure images reported repairable OS findings; build-time security layers retain exact output/package/scan identities. Mutable package repositories mean a rebuild is new bytes. Archive scanner remnants for replaced binaries require effective runtime-file verification, without discarding raw findings. The full target/current release scan is still a release gate. See [environment and operations](../operations/docker-compose-deployment.md).
