# LAS SSO {{VERSION}}

**Development delivery / 开发交付**. This package identifies current application/SDK bytes and documentation; it is not a declaration that production rollout gates are complete.

## Start Here / 入口

- [中文手册目录](docs/zh-CN/README.md) / [English handbook index](docs/en/README.md)
- [Release notes](RELEASE-NOTES.md): changes, compatibility and known limitations
- [Configuration guide](config/README.md)
- [Starter consumption](sdk/README.md) / [Reference BFF](examples/reference-bff/README.md)
- [Database/migrations](database/README.md)
- `provenance/release-manifest.json`: source commit, tests, file inventory and dependencies
- `provenance/SHA256SUMS`: SHA-256 of every package file except the checksum list itself

From this directory, before installation:

```text
python scripts/verify-package.py
```

Install and supply external settings using the deployment handbook. Never start production using a local/test profile or the packaged templates without required external values. The app does not implement every production startup gate; keep ingress closed until deployment checks and administrator/client initialization are complete.

Programs: `application/las-sso-server-{{VERSION}}.jar`; SDK file Maven repository under `sdk/maven/`; runnable sample under `examples/reference-bff/`. Maintenance uses explicit JAR paths and hashes. The `source/` ZIP is the exact committed source snapshot, not a dependency of the operations manuals.

SHA-256 checks detect accidental content changes but are not release signatures or a substitute for trusted distribution. Source and package provenance belong to this version only; earlier browser/restore/alert acceptance is explicitly historical.
