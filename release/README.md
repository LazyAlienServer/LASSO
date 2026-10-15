# Release tooling

Current delivery: [RC1](https://github.com/LazyAlienServer/LASSO/releases/tag/v0.1.0-rc.1), [evidence](verification/0.1.0-rc.1.json). History: [archive](../docs/archive/README.md). Current stable-image follow-up is in [ROADMAP](../ROADMAP.md).

From clean committed source, JDK21/Docker/Python3.10+:

```sh
python scripts/package_release.py build <new-version>
python scripts/scan_release.py <new-version> --gitleaks <installed-gitleaks>
python scripts/package_release.py package <new-version>
python release/<new-version>/scripts/verify-package.py
```

Versions: MAJOR.MINOR.PATCH, -rc.N, -dev.YYYYMMDD.N; positive sequences/valid dates. Changed inputs need a new ID. Outputs are ignored and never overwritten.

`package-files.json` explicitly selects source→package paths. Inputs must be reviewed Git source candidates; tree globs cannot pick up ignored local settings. `templates/` holds package entry/configuration only; current documentation is copied from docs/en and docs/zh-CN, OpenAPI from api, tools from scripts. Render only declared text placeholders; executable tools are copied unchanged.

Strict build/publish evidence identifies commit/tree/artifacts/tests; package verification checks hashes/inventory/source archive/migrations/API/links. Raw build/scan/acceptance logs stay private in .build-release. Preserve scanner exit codes/findings and explicit review; source/dependency scans are not target-image/DAST coverage.

For each new candidate independently extract/consume/build and exercise supported account/OIDC/maintenance/upgrade/restore paths. Retain sanitized version evidence, publish ZIP/checksums/summary as Release assets. Software release, target public deployment and actual-consumer acceptance have separate evidence. The package does not assert production approval.
