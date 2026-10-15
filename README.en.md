# LAS SSO

[中文](README.md) · [Downloads](https://github.com/LazyAlienServer/LASSO/releases) · [Handbook](docs/en/README.md)

Java21/Spring Boot/PostgreSQL identity service: accounts/password recovery, OIDC Code+PKCE, SSO sessions, lifecycle and first-party service eligibility. Consumers own business membership/roles/authorization.

| Task | Start |
| --- | --- |
| Install a delivery | [Deployment](docs/en/deployment.md), [configuration](docs/en/configuration.md) |
| Integrate a backend | [Integration](docs/en/integration.md) |
| Use/manage accounts | [Users](docs/en/users.md), [administrators](docs/en/administrators.md) |
| Operate and recover | [Operations](docs/en/operations.md), [monitoring](docs/en/monitoring.md), [recovery](docs/en/recovery.md) |
| Call API | [API](docs/en/api.md), [OpenAPI](api/openapi.json) |
| Contribute | [CONTRIBUTING](CONTRIBUTING.md) |

Latest candidate: [0.1.0-rc.1](https://github.com/LazyAlienServer/LASSO/releases/tag/v0.1.0-rc.1). Current work/limits: [ROADMAP](ROADMAP.md). Version changes: [CHANGELOG](CHANGELOG.md).

## Local development

JDK21, Docker, Python3.10+. From `las-sso-server/`:

```sh
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

Windows uses gradlew.bat. SSO localhost9000; run `:las-sso-reference-bff:bootRun` separately for port8088. Keep one hostname throughout authorization. Local key/secrets are development-only.

To initialize a development administrator, stop bootRun and keep PostgreSQL running. In a real PowerShell terminal, stop on build failure:

```powershell
.\gradlew.bat bootJar --no-daemon --console=plain
$jar = (Resolve-Path './build/libs/las-sso-server-0.1.0-SNAPSHOT.jar').Path
$hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
./scripts/bootstrap-admin.ps1 -Username InitialAdmin -Profile local -JarPath $jar -ExpectedSha256 $hash
```

Password entry is hidden. After exit 0 restart bootRun and follow U03 to change the temporary password. Do not repeat initialization for an existing administrator. Maintenance wrappers default to production; development explicitly passes `-Profile local`.

The three-module build stays under las-sso-server. docs/en and docs/zh-CN are current guides; docs/adr explains decisions; docs/archive preserves selected evidence. api contains HTTP specification, scripts contains tools and release contains package manifest/templates/version evidence.

Keep real settings/keys/passwords/backups outside source/packages. Generated distribution belongs in Release assets. Published source/bytes and dated evidence remain identifiable.
