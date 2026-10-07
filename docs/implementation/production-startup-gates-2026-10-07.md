# Production Startup Gates Implementation

- Status: implemented locally in the current source; target environment work remains P02/P06–P10.
- Decision: [ADR-0012](../adr/0012-production-configuration-startup-gates.md) / [中文](../zh-CN/adr/0012-production-configuration-startup-gates.md).

## Code Surface

| Area | Implementation |
| --- | --- |
| Early configuration | `org.las.sso.config.ProductionConfigurationGate`; explicit `production`, profile conflict, HTTPS issuer/context path, distinct secret and Flyway/session/actuator/Cookie rules |
| Database startup | `ProductionStartupConfiguration` and `ProductionDatabaseGate`; Flyway-dependent schema/security epoch/admin/ACTIVE-client snapshot before Web startup |
| Schema compatibility | Flyway migration information must contain only applied SUCCESS rows with matching checksums; future/above-target/missing states fail closed |
| Key loading | `PersistentSigningKeyConfiguration`; non-local eager loading, matching RSA pair and minimum2048-bit modulus, password-array cleanup and category-only failure |
| Maintenance | Bootstrap/reset/restore scripts force cleanup-disabled settings; bootstrap rejects password command-line input |
| Tests | `ProductionConfigurationGateTest`, `ProductionStartupIntegrationTest`, command regression; actual PostgreSQL18.4, PKCS12 keytool and servlet startup |

## Verified Cases

Additional regressions verify normal ACTIVE administrator startup, rejection of a future Flyway version, and production registration rejecting reserved example endpoint URIs before committing them. The gate is eager even when global lazy initialization is requested; a failed empty-admin process leaves no listening socket.

Focused JDK21/Docker run on 2026-10-07 passed configuration, production startup, maintenance and local compatibility tests. The integration sequence verified empty DB Web refusal, non-Web bootstrap, first-change/expired administrator states, valid/invalid/disabled clients, example Secret and missing epoch refusal, wrong key password/alias/1024-bit key refusal, and no listener after rejected startup. Error assertions exclude the fixture secret. Final strict full build: **562 server +193 Starter +37 reference =792 tests**, zero failures/errors/skips, with all tasks rerun and required database coverage.

Repository checks:481 UTF-8/text candidates,1150 local links/one declared sibling link, three locks/Wrapper checksum and15 migrations. Four maintenance PowerShell scripts parsed. Gitleaks8.30.1 reported one generic-api-key fixture false positive: `ProductionConfigurationGateTest`'s dummy path `protected-key.p12`, not a credential or key file. Raw finding retained/reviewed, not claimed as zero. No production image/DAST or live hidden-console maintenance rehearsal was performed. Startup integration removes the interactive runner only for service tests, then separately executes the real runner to verify no-console refusal.

The full repository build validates this change; subsequent code edits need proportional revalidation. This implementation does not verify public CA/DNS/proxy, actual secret-manager injection, production backups/RPO/RTO, target capacity, consumer gates or formal launch approval. A new delivery ID is required for binaries built from this source; historical `.7` and `0.1.0-dev.20261007.1` bytes remain unchanged.
