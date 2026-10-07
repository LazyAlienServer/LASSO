# LAS SSO Versioned Deliveries

`_source/` contains the tracked handbook/configuration/tool sources. Version directories and distribution ZIPs are generated, ignored artifacts, not committed binaries.

Current packaging target: **0.1.0-dev.20261007.1**. This is a new development delivery after the engineering cleanup; it must not replace historical `.20261006.7` bytes.

From the repository root, with a clean committed checkout, JDK21, Docker and Python3.10+:

```powershell
python scripts/package_release.py build 0.1.0-dev.20261007.1
python scripts/scan_release.py 0.1.0-dev.20261007.1 --gitleaks <installed-gitleaks-executable>
python scripts/package_release.py package 0.1.0-dev.20261007.1
python release/0.1.0-dev.20261007.1/scripts/verify-package.py
```

On Windows the build command honors `JAVA_HOME`; use JDK21. Packaging rejects a dirty checkout, missing/failed/skipped module reports, incorrect artifact versions and existing version directories/ZIPs. It records the build source commit, exact application/SDK/configuration hashes, migration manifest and count-only verification evidence. A new version is required for changed inputs.

Scanning requires Docker images Trivy0.75.0/Semgrep1.179.0 and installed Gitleaks8.30.1. Load the identified images before scanning. Raw reports/build logs stay in ignored `.build-release/<version>/`, never in the delivery; only report hashes, scope/counts and sanitized finding metadata are published. `--rules` accepts the documented hash-verified historical rules; otherwise only rule packs are fetched, not uploaded source. Trivy refreshes its vulnerability DB. `package` can record scans as not executed, but this delivery workflow includes them and reviews findings before handoff. No scan or current build silently reuses historical runtime acceptance.

The resulting `release/<version>/README.md` is the independent package entry. Handbooks are under `docs/zh-CN/` and `docs/en/`. Actual passwords, peppers, client credentials, keys, certificates, database backups and raw runtime logs are never included. Package/source ZIPs contain only declared release files or the committed source tree. No production deployment or hosted release follows automatically.
