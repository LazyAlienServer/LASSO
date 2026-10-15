# Historical development delivery 0.1.0-dev.20261009.2

[中文](../zh-CN/development-delivery-2026-10-09-2.md) · [Package entry](../../../release/README.md) · [Verification record](../../../release/verification/0.1.0-dev.20261009.2.json)

Latest local independent delivery: `release/0.1.0-dev.20261009.2/` and `release/las-sso-0.1.0-dev.20261009.2.zip`. The 81,241,365-byte ZIP contains 98 files and 229 local handbook links. The tracked [English Docker tutorial template](https://github.com/LazyAlienServer/LASSO/blob/8390243505b781c25447fa4e125532f421f56b76/release/_source/docs/en/docker-deployment.md) and its Chinese counterpart each provide eight task-oriented steps, expected results and failure handling; the extracted package README links directly to deployment, troubleshooting, operations and integration. This is a development handoff, not production approval.

## Source and build

Clean committed source `297cdfc0dcf06a9db421064d5328b7bb71f07859` (tree `a7b9bc6493083d1959d079158391ab6e77b45d59`). JDK 21, Gradle 9.6.1, forced clean three-module build with database tests required: **588 server + 196 Starter + 50 reference BFF = 834**, zero failures, errors or skips; local Starter Maven publication passed. The test fixture for future `nbf` rejection now leaves time for JWT second precision and JWKS latency; runtime validation was not changed.

Trivy 0.75.0 identified 241 lock-file and 157 nested-JAR dependency rows with zero reported vulnerabilities. Its vulnerability DB (updated October 8) passed the freshness check; Java package mapping downloaded successfully (updated October 9). Semgrep 1.179.0 scanned 488 paths with zero errors and six previously reviewed identical findings. Gitleaks 8.30.1 reported zero current source/artifact findings; its one unchanged historical synthetic keystore-path test finding retains exit 1 and an explicit review. Independent complete-package archive-depth-3 scanning reported zero findings. Counts and tool scope do not constitute target image security acceptance.

## Independent package checks

From a fresh extraction of the ZIP, `scripts/verify-package.py` checked file inventory, hashes, source archive revision, artifact coordinates, embedded V1–V16 migrations and every handbook link. Mutated and extra-file fixtures were rejected, then the original passed again. Python/PowerShell tools parsed; maintenance rejected a wrong JAR hash before Java, and blank Docker input was rejected. Independent Gradle consumers compiled against both native and POM-only Starter metadata and checked Boot 4.1.1, Security 7.1.1, Framework 7.0.9 and Log4j 2.25.5 alignment. Prometheus 3.6 config/rules/fixtures and Alertmanager 0.28.1 config passed.

Without Git inside the extracted package, its tool built an image from the exact delivered JAR and manifest with an external receipt and verified source label: `sha256:3a8a328bef7d06927a48ebb678534e9fa1da537d1b4d1ad50197322f8f16b143`. The package verified unchanged afterward. Copied package Compose/properties and private random fixture secrets passed fresh offline V1–V16 migration, migrator ownership and read-only runtime history, repeat migration, hidden TTY bootstrap, browser first-login forced password change, Secure host cookie, canonical forwarding and unknown-Host/actuator checks. Same-key/JDBC session survived SSO restart; DB outage produced readiness 200→503→200 while liveness remained 200; private metrics returned 401/200 with missing/present collector credentials. Browser page errors: 0; fixture secret values absent from container logs. The fixture containers, networks, volume and external secrets/key/config were removed.

## Immutable SHA-256

| Item | SHA-256 |
| --- | --- |
| ZIP | `1e9d5782536cb0eec00f9175060b3ed4291e81a14be72f230e701c2a001b38eb` |
| Source ZIP | `c792ee5037e44b8a5c2805f4b789c7a1c6ada69d10a1f4313e472369d3fcfce9` |
| Server | `10c5755b481ec4bdd8461cc75907408b79bb32ce6650f55df613d819299d2310` |
| Starter | `1a916c9df33f2a0b5eab88795cdc49bce1115d4b3c6914f5d65f81608841f5ad` |
| Reference BFF | `e6bdf55dbfdde06b6faa1947fb675e2e7754d3f099dc90f2a14fd0663fbfadd6` |

The prior `.20261009.1` and `.20261007.1` ZIP hashes still match their recorded values. The browser used a two-day self-signed test certificate and SPKI exception; PostgreSQL/Nginx reused previously verified local test images, not newly accepted target images. Official DNS/public CA, Linux UID/ACL custody, production image/JDK/OS scans, off-host restore/RPO/RTO, capacity, notifications and full cross-consumer acceptance remain [target-environment gates](https://github.com/LazyAlienServer/LASSO/blob/8390243505b781c25447fa4e125532f421f56b76/docs/implementation/production-launch-plan.md). The exact-version full dual-BFF OIDC/refresh/logout/restore/Grafana/on-call flows were not rerun. No hosted release, push or production database operation occurred.
