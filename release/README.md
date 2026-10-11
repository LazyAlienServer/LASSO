# LAS SSO Versioned Deliveries

`_source/` contains the tracked handbook/configuration/tool sources. Version directories and distribution ZIPs are generated, ignored artifacts, not committed binaries.

Selected next-version topology: [one external same-host Nginx](_source/docs/en/host-nginx-deployment.md) / [中文](_source/docs/zh-CN/host-nginx-deployment.md), SSO/PostgreSQL standalone Compose, loopback application publication and private management. Formal public domain/HTTPS inputs are supplied at target launch. This source change is not present in the immutable `.2` archive.

Latest local delivery: **0.1.0-dev.20261009.2**, with task-oriented bilingual handbooks and a prominent Docker deployment tutorial. [English record](../docs/integration/development-delivery-2026-10-09-2.md) / [中文记录](../docs/zh-CN/integration/development-delivery-2026-10-09-2.md) / [verification evidence](verification/0.1.0-dev.20261009.2.json). Source `297cdfc`; 98 files/229 links, 834 strict tests, independent ZIP/SDK/image/Compose smoke. Runtime P02–P06 is retained. Older packages remain immutable.

Previous local delivery: [English](../docs/integration/development-delivery-2026-10-09-1.md) / [中文](../docs/zh-CN/integration/development-delivery-2026-10-09-1.md), [trusted ZIP/artifact hashes and exact-version checks](verification/0.1.0-dev.20261009.1.json). Source `b847486`; 90 files/109 links, 834 strict tests. Later revisions do not alter its bytes.

Earlier local delivery: [English verification record](../docs/integration/development-delivery-2026-10-07-1.md) / [中文](../docs/zh-CN/integration/development-delivery-2026-10-07-1.md), with [trusted checksum and post-package results](verification/0.1.0-dev.20261007.1.json). Its immutable source is `a442b0b`; subsequent commits do not change its bytes.

For a future new version, from the repository root with a clean committed checkout, JDK21, Docker and Python3.10+ (replace `<new-version>` throughout; existing versions cannot be overwritten):

```powershell
python scripts/package_release.py build <new-version>
python scripts/scan_release.py <new-version> --gitleaks <installed-gitleaks-executable>
python scripts/package_release.py package <new-version>
python release/<new-version>/scripts/verify-package.py
```

On Windows the build command honors `JAVA_HOME`; use JDK21. Packaging rejects a dirty checkout, missing/failed/skipped module reports, incorrect artifact versions and existing version directories/ZIPs. It records the build source commit, exact application/SDK/configuration hashes, migration manifest and count-only verification evidence. A new version is required for changed inputs.

Version policy now accepts `MAJOR.MINOR.PATCH-dev.YYYYMMDD.N`, `MAJOR.MINOR.PATCH-rc.N` and `MAJOR.MINOR.PATCH`. These map to `development-delivery`, `release-candidate` and `software-release`; calendar dates and positive sequence numbers are validated, SNAPSHOT/build metadata/path-like inputs are rejected. Formal/RC support is tooling, not a completed final candidate or production authorization. `productionApproved` remains false for all software deliveries. Independent fixtures: `python -m unittest discover -s scripts/tests -v`.

Implemented API documentation is tracked at [English](_source/docs/en/api-reference.md) / [中文](_source/docs/zh-CN/api-reference.md) / [OpenAPI3.1](_source/api/openapi.json). New packages copy it under `docs/` and `api/`, replace the exact version and verify self-contained references. The Gradle `OpenApiContractTest` prevents MVC route/record-field drift; no live Swagger UI or `/v3/api-docs` route is implied. Existing `.2` bytes are not rewritten to add these files.

Scanning requires Docker images Trivy0.75.0/Semgrep1.179.0 and installed Gitleaks8.30.1. Load the identified images before scanning. Raw reports/build logs stay in ignored `.build-release/<version>/`, never in the delivery; only report hashes, scope/counts and sanitized finding metadata are published. `--rules` accepts the documented hash-verified historical rules; otherwise only rule packs are fetched, not uploaded source. Trivy checks its vulnerability DB and refreshes the Java package-mapping DB when needed. If mapping refresh fails, only explicit `--allow-cached-java-db` with a seeded `--cache` permits fallback and publishes its date/hash/limitation; a current vulnerability DB is still required. The known historical synthetic keystore-path test match makes Gitleaks exit 1: inspect the exact private report, retain the exit code and record a reviewed, version-specific `scan-summary.json` before packaging. Do not suppress or describe this as a passing history scan. `package` can record scans as not executed, but this delivery workflow includes them and reviews findings before handoff. No scan or current build silently reuses historical runtime acceptance.

The resulting `release/<version>/README.md` is the independent package entry. Handbooks are under `docs/zh-CN/` and `docs/en/`. Actual passwords, peppers, client credentials, keys, certificates, database backups and raw runtime logs are never included. Package/source ZIPs contain only declared release files or the committed source tree. No production deployment or hosted release follows automatically.
