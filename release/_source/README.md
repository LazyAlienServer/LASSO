# LAS SSO {{VERSION}}

**Development delivery / 开发交付**. This package identifies current application/SDK bytes and documentation; it is not a declaration that production rollout gates are complete.

## Start Here / 入口

- [中文手册目录](docs/zh-CN/README.md) / [English handbook index](docs/en/README.md)
- [Release notes](RELEASE-NOTES.md): changes, compatibility and known limitations
- [中文发行说明](RELEASE-NOTES.zh-CN.md)：P02–P06、兼容与验收范围
- [Configuration guide](config/README.md)
- Single-host Compose: `deploy/docker/README.md` / `deploy/docker/README.zh-CN.md`; exact image provenance, private networks, file secrets and offline migration/bootstrap
- [Starter consumption](sdk/README.md) / [Reference BFF](examples/reference-bff/README.md)
- [Database/migrations](database/README.md)
- `provenance/release-manifest.json`: source commit, tests, file inventory and dependencies
- `provenance/SHA256SUMS`: SHA-256 of every package file except the checksum list itself

From this directory, before installation:

```text
python scripts/verify-package.py
```

Install and supply external settings using the deployment handbook. Production requires an explicit `production` profile and rejects unsafe startup settings; do not mix `local`/`test` or use templates without actual external values. Initialize the administrator through non-Web maintenance before Web startup; keep ingress closed until target TLS/network/client and acceptance checks are complete.

Programs: `application/las-sso-server-{{VERSION}}.jar`; SDK file Maven repository under `sdk/maven/`; runnable sample under `examples/reference-bff/`. SSO maintenance scripts target the server JAR; `reference-revocation-maintenance.ps1` explicitly targets the BFF JAR. Both verify an exact path/hash. The `source/` ZIP is the exact committed source snapshot, not a dependency of the operations manuals.

SHA-256 checks detect accidental content changes but are not release signatures or a substitute for trusted distribution. Source and package provenance belong to this version only; earlier browser/restore/alert acceptance is explicitly historical.
