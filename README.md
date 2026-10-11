# LAS SSO

LAS SSO manages identity, credentials, account lifecycle, SSO sessions and first-party service-access eligibility. Business permissions and consumer databases belong to the relying applications.

## Repository

| Path | Purpose |
| --- | --- |
| `las-sso-server/` | Active Java 21 / Spring Boot / PostgreSQL application and the only active Gradle build |
| `las-sso-server/las-sso-spring-boot-starter/` | Reusable servlet BFF integration library |
| `las-sso-server/las-sso-reference-bff/` | Acceptance sample, not the LASLOG backend |
| `docs/` | Contracts, ADRs, historical evidence and paired English/Chinese records |
| `scripts/` | Portable repository/report verification tools |
| `release/` | Versioned delivery templates, independent handbooks and ignored generated packages |
| `design/`, `research/` | Reference assets and historical research |
| `SAS-Test/` | Ignored historical protocol demo; not an active module |

## Build And Verify

Requirements: JDK 21, Docker for PostgreSQL integration tests, Python 3.10+ for repository/report checks. Use the checked-in Gradle Wrapper; no global Gradle or workstation-specific init script is needed.

From the repository root:

```powershell
python scripts/verify.py repository
```

From `las-sso-server/`:

```powershell
.\gradlew.bat build -PrequireDatabaseTests=true --no-daemon --console=plain
```

From the repository root after the build:

```powershell
python scripts/verify.py tests --require-no-skips
```

On Linux use `./gradlew` for the same tasks. `build` includes Java formatting checks for all three modules. `-PrequireDatabaseTests=true` rejects skipped tests; Docker must be running. Focused local tests may omit that flag, but a skipped database suite is not persistence evidence.

## Local Development

From `las-sso-server/`:

```powershell
docker compose up -d
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

SSO: `http://127.0.0.1:9000`. In a second terminal run `:las-sso-reference-bff:bootRun`, then visit `http://127.0.0.1:8088`. Keep one hostname throughout the flow. Local clients and ephemeral signing keys are development-only.

## Documentation

- [Server operation and maintenance](las-sso-server/README.md)
- [Starter contract](las-sso-server/las-sso-spring-boot-starter/README.md)
- [Reference BFF](las-sso-server/las-sso-reference-bff/README.md)
- [Versioned delivery packaging](release/README.md)
- [Implemented API reference](release/_source/docs/en/api-reference.md) / [中文](release/_source/docs/zh-CN/api-reference.md) / [OpenAPI3.1](release/_source/api/openapi.json)
- [Contribution and engineering rules](CONTRIBUTING.md)
- [Engineering baseline](docs/implementation/engineering-baseline-2026-10-07.md) / [中文](docs/zh-CN/implementation/engineering-baseline-2026-10-07.md)
- [Documentation index](docs/README.md)
- [Current production launch plan](docs/zh-CN/implementation/production-launch-plan.md) / [English](docs/implementation/production-launch-plan.md)

Historical delivery manifests identify their original source/artifact snapshots. Formatting and build-convention changes do not overwrite those deliveries or imply production readiness. New release inputs require a new identified combination.
