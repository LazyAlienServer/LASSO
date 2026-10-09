# Release notes — {{VERSION}}

[中文](RELEASE-NOTES.zh-CN.md) · [Package entry](README.md)

## Changes since 0.1.0-dev.20261009.1

| Area | Change |
| --- | --- |
| Entry | Task navigation; Docker installation and troubleshooting prominently linked |
| Docker | Dedicated eight-step tutorial with commands, expected results and failure actions |
| Handbook | Short sections, tables and separate host JAR instructions |
| Specialist tasks | Separate history-retention and reference-revocation maintenance pages |
| File access | Clarified PostgreSQL/SSO shared readers for database secret files |
| Packaging | Docker entry links resolve within the extracted package |
| Test fixture | Future-nbf rejection uses a margin beyond timestamp rounding/JWKS latency; runtime validation unchanged |

Authentication runtime, dependency locks and existing migrations are unchanged by this handbook revision.

## Included functionality

| Phase | Included |
| --- | --- |
| P02 | Production profile/config/key/admin/client startup gates |
| P03 | Revocation submission outcomes and reference V2 operator recovery/retention/key rotation |
| P04 | Account/recovery/lifecycle UI fixes and one-time-secret clearing/manual recovery procedure |
| P05 | V16 indexes, bounded90/30-day runtime cleanup and read-only history report |
| P06 | Single-host Compose, private networks/file secrets, DB roles and offline database-migrate |

Server, Starter file Maven repository, reference BFF, source archive, bilingual handbooks, configuration and operator tools are included. Build/load container images separately.

## Selected first launch

- Account site only; one SSO, separate PostgreSQL, same-host Nginx, no initial CDN.
- Each consumer completes its own integration before opening.
- Official domain, hardware/traffic, custody, backup/RPO/RTO and notification values remain operator inputs.

## Evidence locations

| Question | Read |
| --- | --- |
| What exact bytes/source? | provenance/release-manifest.json and SHA256SUMS |
| Which tests/scans ran? | provenance/verification-summary.json |
| Which dependencies/migrations? | provenance/dependencies and database/migration-manifest.json |
| What must target operators verify? | [Acceptance](docs/en/acceptance.md) |

Earlier `.20261009.1` exact-package Compose smoke and P02–P06 records remain historical unless rerun for this version. Previous SSO OS Medium/Low findings, removed-gosu archive metadata and older Java-mapping limitations remain documented in those records.

## Compatibility and limits

| Item | Rule |
| --- | --- |
| Runtime | JDK21, Boot4.1/Security7.1; exact versions in locks/catalog |
| Database | Embedded SSO V1–V16; reference queue V1–V2 is independent |
| Starter | Default single-JVM/HttpSession; business login ends on restart |
| Durable revocation | Committed tasks recover; pre-submit compensation remains consumer-owned |
| Live history | Live hints/RT protected; no whole-storage or audit-archive guarantee |
| Restore | New epoch/key/kid and consumer barriers; verified schema-compatible rollback only |
| Deferred | Eligibility events, multi-instance/retained login; no added MFA/email/business Claims |

Development delivery. Target public CA/renewal/firewall/Linux custody/current image scans/DAST/backup/soak/final opening need their own evidence. No production deployment or hosted signature is implied.
