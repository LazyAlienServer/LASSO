# Deployment

[Index](README.md) · [中文](../zh-CN/deployment.md)

## 1. Prepare The Environment

Use JDK21, PostgreSQL (18.4 was the acceptance baseline), a private app/DB network and a canonical HTTPS issuer. Supply an operator-owned domain, exact consumer callback/logout URLs, CA/renewal, firewall rules, storage/capacity and incident contacts. Use separate staging/production DBs and clients. This package does not provision them. Complete production startup validation/no-admin maintenance mode remains pending; keep ingress closed until manual checks and initialization succeed.

Verify the package from its root with `python scripts/verify-package.py`. Read `provenance/release-manifest.json` and retain its trusted server hash. Install the entire immutable version directory at `/opt/las-sso/{{VERSION}}`, create an unprivileged `las-sso` user and a protected `/etc/las-sso/` configuration location. Do not let the runtime user rewrite release files.

## 2. Database, Secrets And Signing Key

Create a dedicated PostgreSQL database/account with migration privileges appropriate to Flyway V1–V15. Configure PostgreSQL authentication/network access and backup before opening traffic. JDBC URL example: `jdbc:postgresql://db.internal:5432/las_sso`; use validated TLS for cross-host connections. Do not share the identity DB with a business application or the reference revocation queue.

Copy `config/server/server.properties.example` to `/etc/las-sso/server.properties`. In a root/service-readable protected environment file or secret mechanism, supply:

| Variable | Meaning |
| --- | --- |
| `LAS_SSO_DB_URL`, `LAS_SSO_DB_USER`, `LAS_SSO_DB_PASSWORD` | Actual JDBC target and credentials |
| `LAS_SSO_ISSUER` | Exact canonical HTTPS URL; discovery/clients must match scheme, host, port and path |
| `LAS_SSO_SIGNING_KEY_PATH`, `LAS_SSO_SIGNING_KEY_PASSWORD`, `LAS_SSO_SIGNING_KEY_ALIAS`, `LAS_SSO_SIGNING_KEY_ID` | Persistent PKCS12 RSA pair, same keystore/private-key password, stable nonblank kid |
| `LAS_SSO_RECOVERY_PEPPER` | Independent high-entropy recovery hash secret |
| `LAS_SSO_TRANSIENT_SECURITY_PEPPER` | Independent high-entropy transient/HMAC key secret; also affects retained hint lookup |
| `LAS_SSO_CAPTCHA_HMAC_SECRET` | Independent high-entropy CAPTCHA secret |

Generate secrets through a trusted secret manager/CSPRNG; preserve exact values across restarts and protect custody/backups. Do not use local values, sample client credentials or a shared human password. Keytool may create a PKCS12 RSA3072 `RS256` pair with interactive hidden password input (`-genkeypair -keyalg RSA -keysize 3072 -storetype PKCS12`); choose your path/alias/validity and use the same key password. No signing private key belongs in public JWKS. The app loads one configured key, not a seamless multi-key rotation manager; plan outages/cache expiry and [recovery barriers](backup-and-recovery.md).

## 3. Initialize While Ingress Is Closed

Offline maintenance uses JDK21 plus the same DB/key/secrets and explicit external config. Stop web writers before initialization. In PowerShell from package root:

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
$manifest = Get-Content -LiteralPath './provenance/release-manifest.json' -Raw | ConvertFrom-Json
$jar = Join-Path $PWD $manifest.artifacts.server.path
$sha = $manifest.artifacts.server.sha256
./scripts/bootstrap-admin.ps1 -Username '<chosen-login-name>' -Profile production -JarPath $jar -ExpectedSha256 $sha
```

Use `-UserId '<existing-UUID>'` instead of Username for an existing account. Real passwords are read by the Java console without echo, not parameters/env/history. Keep operator prompts and one-time recovery output private. Packaged bootstrap/reset defaults select `production`; JAR and expected hash are mandatory. `production` is a name you select, not a special provisioning profile. No `local`/`test` in live settings. Linux administrators can install PowerShell or invoke the same Java non-web maintenance command with explicit profile/config/JAR, retaining the hash and interactive-console checks; this does not require a source checkout.

## 4. Start And Configure The Proxy

From package root, manual web startup:

```text
java -jar application/las-sso-server-{{VERSION}}.jar --spring.profiles.active=production --spring.config.additional-location=file:/etc/las-sso/server.properties
```

For Linux, review/install `deploy/systemd/las-sso.service`, adjust Java/install/config paths, secure the environment file, then run `systemd-analyze verify`, `systemctl daemon-reload` and `systemctl start las-sso`. Use a maintenance window for upgrades; no privileged runtime account or writable package is needed. Capture logs to access-controlled journal/storage, never authentication payloads.

Review `deploy/nginx/sso-site.conf.example`; replace hostname/certificate paths and install `deploy/nginx/las-forwarding.conf` as `/etc/nginx/snippets/las-sso-forwarding.conf`. `nginx -t` before reload. Direct app9000 must be inaccessible to untrusted callers. Proxy removes alternate forwarding metadata and overwrites canonical host/proto/port/client address. Preserve OIDC/API paths; do not strip a prefix. The sample rejects unknown hosts and hides `/actuator/`; it does not design your CDN/load-balancer trust chain. Secure/HttpOnly/Lax `__Host-las_sso` is HTTPS host-only; no Cookie Domain.

## 5. Verify, Register Clients, Then Open

Check private readiness200/UP and liveness200, Flyway schema history V1–V15, canonical discovery/JWKS without private material and real TLS browser cookies. With `monitoring`, health moves to the private management port; otherwise use the app port. Run:

```text
python scripts/smoke.py --issuer https://auth.example.com --readiness-url http://127.0.0.1:9000/actuator/health/readiness
```

This checks protocol/health, not login or a full deployment. Verify administrator login, required-password change, exact client registration and all [acceptance](acceptance.md) paths before ingress opens. Register independent confidential clients through `/admin/clients`, deliver each one-time secret privately, then follow [integration](external-integration.md).

Optional monitoring: supply its independent hash; use profiles `production,monitoring` and additional locations `file:/etc/las-sso/server.properties,file:/etc/las-sso/monitoring.properties`. Update the service ExecStart explicitly; maintenance uses only server config/profile. See [monitoring](monitoring.md).
