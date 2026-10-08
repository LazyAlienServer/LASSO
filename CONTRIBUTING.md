# Contributing To LAS SSO

## Scope And Layout

Use the existing three-module Gradle build under `las-sso-server/`. Keep domain packages grouped by responsibility: account/admin/access, OIDC/session/security, logout/maintenance and observability. Do not add business permissions, Minecraft identities or consumer database migrations to SSO. The starter is a library; JDBC revocation tables remain reference-only.

Java production code belongs in `src/main/java`; tests mirror the relevant package under `src/test/java`. Keep test probes and fixture-only security chains out of production sources. Versioned Flyway migrations are append-only: add the next `V<number>__description.sql`, never rewrite an already applied migration.

## Formatting And Build

- UTF-8, four-space Java indentation, LF text endings; Windows batch launchers use CRLF. EditorConfig and Git attributes define these rules.
- Java uses Spotless with a pinned Google Java Format AOSP style. Run `spotlessApply`, then `spotlessCheck`; do not manually align imports or suppress formatter checks.
- Versions belong in `gradle/libs.versions.toml`; dependency repositories belong in `settings.gradle.kts`. Commit the affected module locks after intentional resolution changes.
- JDK 21/release 21, JUnit Platform and reproducible archive conventions are shared by the root build. Module scripts declare their module-specific plugins, dependencies and publishing only.
- Keep Java/Kotlin/SQL/PowerShell comments short and explanatory. Use typed APIs and parameterized SQL; secrets/identity data are not metric labels or exception-message payloads.

From `las-sso-server/`:

```powershell
.\gradlew.bat spotlessApply --no-daemon --console=plain
.\gradlew.bat build -PrequireDatabaseTests=true --no-daemon --console=plain
```

After intentional dependency changes resolve every affected module, for example:

```powershell
.\gradlew.bat dependencies :las-sso-spring-boot-starter:dependencies :las-sso-reference-bff:dependencies --write-locks --no-daemon --console=plain
```

## Verification

Use focused regressions while developing, and a complete database-required build before integrating shared authentication/persistence changes. Add tests for meaningful boundary/behavior changes, not a line-by-line copy of implementation. PostgreSQL Testcontainers require Docker; inspect actual failure/error/skipped counts. CI runs the same full build on Linux and parses maintenance PowerShell scripts on Windows.

From the repository root:

```powershell
python scripts/verify.py repository
python scripts/verify.py tests --require-no-skips
git diff --check
```

Repository checks validate source candidates, UTF-8/text/JSON, internal documentation links, migration sequence and Wrapper checksum. Links to declared sibling LASLOG documentation are external context, not build dependencies. This does not replace secret/vulnerability scans or runtime browser/proxy/restore acceptance. Never upload sensitive debug/acceptance logs as CI artifacts.

## Git And Delivery

UI interaction fixes also run `npm ci` then `npm test` in `scripts/browser/` using installed Edge (or the documented Playwright channel). These source-DOM regressions are independent of Gradle and do not replace real JAR/PostgreSQL/HTTPS flow acceptance. No private browser traces or authentication captures are committed.

Before committing inspect status, diff and recent commits. Stage explicit intended paths; never use a blanket commit of local settings, archives or build output. Preserve other contributors' work. Commit changes in coherent units using the repository's `feat:`, `fix:`, `docs:`, `build:` or `chore:` style; describe the behavior/checks rather than an invented release claim. Separate mechanical formatting from substantive fixes where practical.

Do not rewrite historical delivery IDs/manifests to claim new source or bytes. Record the source revision, chosen server/starter versions, lockfiles, artifact hashes, test results and acceptance scope for a new identified delivery. A local commit is not a push, signed hosted release or production deployment.

## Configuration And Documentation

External passwords, client secrets, signing/queue keys, peppers, certificates, Tokens, Cookie captures and recovery codes must stay outside tracked source. Use ignored `.local/`/`.env` locations or protected external settings. Local-only example clients in explicit local profiles are not production settings.

Keep active README pages concise and current. Contracts belong in `docs/architecture` or `docs/integration`; decisions in `docs/adr`; historical commands/counts in dated acceptance/delivery records. Update English and `docs/zh-CN/` counterparts together for new design/acceptance records. Do not turn an aspirational runbook gate into an implemented feature without code and evidence.
