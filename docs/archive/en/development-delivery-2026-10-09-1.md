# Historical development delivery 0.1.0-dev.20261009.1

[中文](../zh-CN/development-delivery-2026-10-09-1.md) · [Packaging entry](../../../release/README.md) · [Post-package evidence](../../../release/verification/0.1.0-dev.20261009.1.json)

Latest local independent delivery: `release/0.1.0-dev.20261009.1/` and `release/las-sso-0.1.0-dev.20261009.1.zip`. ZIP81217917bytes (about77.5MiB),90files/109package-local links. Previous `.20261007.1` remains unchanged. This is a development handoff, not production rollout approval.

## Sources and contents

- Clean committed source `b84748640c26fc835f336cdbb24b38cf96dc0da5`, tree `75f69287d810dd687144ccda777e447a4e99ad35`; later acceptance commits do not replace the snapshot.
- P02 startup/key/config/admin gates; P03 reference V2 recovery/retention/rotation and submission outcomes; P04 lifecycle/recovery/secret UI and manual runbook; P05 V16 bounded history retention; P06 single-host Compose/offline migration and identified image tools.
- Exact executable server/reference BFF; Starter JAR/POM/native metadata/checksums in versioned file Maven repository; source git archive, embedded SSO V1–V16 and reference V1–V2 separation, three locks/catalog/Wrapper/nested-library provenance.
- Independent English/Chinese handbooks and release notes; Docker/Nginx/systemd/monitoring/config inputs; console-only maintenance, read-only status/smoke and checksum/provenance verification tools. No actual keys/secrets/backups/logs or container images in the ZIP.

## Current checks

Clean JDK21/Gradle9.6.1 build with explicit server/Starter version, all tasks rerun, database required: **588server+196starter+50reference=834**, zero failures/errors/skips. Local Starter publication passed. Trivy0.75.0 identified241locked/157nestedJAR dependency rows, zero reported vulnerabilities using vulnerability DB updated2026-10-08 and explicitly cached Java mapping2026-10-06 after refresh download failed. Mapping limitation is published; no fully refreshed final-target scan claim.

Semgrep1.179.0 scanned475paths/6findings/0errors: fixed-context production redirect, two operator-local/fixed-rule URL uses and three trusted-local XML metadata parsers reviewed, no new production-path defect identified. Gitleaks8.30.1 current source/artifacts zero; history1fixed MockEnvironment keystore-path label reviewed with original exit1 retained, no secret value/source excerpt or suppression. Extracted complete-package depth3 scan zero. Report counts are coverage, not proof of no flaws.

Independent extraction verified inventory/versions/source revision/embedded migrations/local links/hash; changed-file and extra-file negatives rejected then original reverified. PowerShell/Python tools parsed; wrong maintenance hash refused before Java. Starter native/POM-only independent consumers compiled public APIs and verified Boot4.1.1/Security7.1.1/Framework7.0.9/Log4j2.25.5 alignment. Prometheus3.6 config/seven rules/rule fixtures and Alertmanager0.28.1 config passed. systemd remains template only.

From the extracted package without Git, Docker image build validated package provenance and exact delivered JAR, external staging, nonfixture source label. Image `sha256:08867282620512116e4920d26e8079196069c86b556fa2178ef1e7c98489522e`; package unchanged afterward. Copied Compose/properties with private random secrets/PKCS12, earlier patched PG/Nginx images and loopback limits passed:

- fresh V1–V16 nonWeb migration, migrator object ownership, postmigration runtime Flyway-history read-only SQL, repeat exit0;
- real hidden TTY bootstrap, TLS first-login forced change, Secure/HttpOnly/Lax host-only Cookie;
- canonical spoofed-forwarding endpoints, HTTP308/unknownHost421/actuator404, private app/DB/metrics ports, rootless/read-only app/proxy;
- same signing JWKS and JDBC login after SSO restart, private metrics401/200, noTTY maintenance refusal;
- DB outage readiness200→503→200/liveness200 and fresh account access recovery, zero browser pageerrors, exact fixture secret values absent from logs.

TLS used a two-day `auth.las.test` self-signed fixture, explicit certificate/hostname HTTP trust and browser SPKI exception; not official DNS/publicCA/renewal/firewall acceptance. Infrastructure images reused earlier verified patched local bytes, not newly rebuilt current target scan. Containers/networks/volume and external keys/secrets/config/private state removed.

## Immutable SHA-256

| Item | SHA-256 |
| --- | --- |
| Distribution ZIP | `3009f5d8b748d677a845b25fa304a9d0353f5fb29fa184850573ae4f8aede11c` |
| Source ZIP | `3c23b83321a5e5382c78078a38587ddb695a28fa86276e302d7ae4d678049031` |
| Server | `fb8b702d8719b45b6daa845c57decaf14d85e58dd7dce4770c9f4f70ce3df2a0` |
| Starter | `1a916c9df33f2a0b5eab88795cdc49bce1115d4b3c6914f5d65f81608841f5ad` |
| Reference BFF | `95eb7204fd57eaaa492ae844dce6cb1df7a50805e1179254a21a19e52064c6fe` |

From extracted root: `python scripts/verify-package.py`, then read `README.md`, `RELEASE-NOTES.md`/`RELEASE-NOTES.zh-CN.md` and the appropriate handbook. SHA256 is integrity, not a release signature. Post-package results are recorded outside completed bytes.

Target domains/hardware/traffic/key-custody/backup/RPO/RTO/notifications/security/soak/final rollout remain the [launch plan](https://github.com/LazyAlienServer/LASSO/blob/8390243505b781c25447fa4e125532f421f56b76/docs/implementation/production-launch-plan.md). Exact-version full dual-BFF OIDC/refresh/logout/restore/Grafana/on-call scenarios were not rerun. Each future consumer has its own P11/P12; SSO account-site-only initial scope still needs target operational gates. No push/hosted release/production DB operation.
