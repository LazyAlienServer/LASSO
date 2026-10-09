# JAR / systemd deployment

[Index](README.md) · [中文](../zh-CN/jar-deployment.md) · [Selected Docker method](docker-deployment.md)

**Scope:** Existing host-Java layout with JDK 21, PostgreSQL, an unprivileged service account and TLS Nginx.

## 1. Verify and configure

**Working directory: package root.**

```text
python scripts/verify-package.py
```

Copy `config/server/server.properties.example` to protected external storage.

| Input | Requirement |
| --- | --- |
| `LAS_SSO_DB_URL/USER/PASSWORD` | Actual PostgreSQL and dedicated role |
| `LAS_SSO_ISSUER` | Canonical HTTPS, path equals servlet context |
| `LAS_SSO_SIGNING_KEY_*` | Persistent RSA≥2048 PKCS12, same password, alias/kid |
| Three pepper/CAPTCHA secrets | Independent, minimum 32 UTF-8 bytes |
| DB/key passwords | Independent, minimum 12 bytes; length is not entropy |

See [config index](../../config/README.md). Host app/management bindings are private loopback; do not substitute container templates.

## 2. Offline migration

Stop Web writers. Use migration role, same database/key/secrets and server-only config.

```text
java -jar application/las-sso-server-{{VERSION}}.jar --spring.profiles.active=production --spring.config.additional-location=file:/etc/las-sso/server.properties --spring.main.web-application-type=none --las.sso.maintenance.command=database-migrate --las.sso.scheduling.enabled=false --spring.session.jdbc.cleanup-cron=- --las.sso.monitoring.enabled=false
```

**Expected:** Embedded V1–V16 complete, exit 0, no HTTP listener or jobs. Keep Flyway validation and clean protection.

## 3. Bootstrap

Real PowerShell terminal at package root:

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.server.path
./scripts/bootstrap-admin.ps1 -Username InitialAdmin -Profile production -JarPath $j -ExpectedSha256 $m.artifacts.server.sha256
```

**Expected:** Two hidden inputs, unique administrator with temporary credential/forced first change. Existing user uses `-UserId`, not both options.

Linux may invoke the same JAR's `admin-bootstrap` directly. Replace `database-migrate` with `admin-bootstrap`, retain all other offline/job-disable arguments from step 2, and add `--username=InitialAdmin`.

## 4. Web and Nginx

Switch to reviewed runtime role, then:

```text
java -jar application/las-sso-server-{{VERSION}}.jar --spring.profiles.active=production --spring.config.additional-location=file:/etc/las-sso/server.properties
```

| Template | Action | Expected |
| --- | --- | --- |
| `deploy/systemd/las-sso.service` | Adjust path/user/environment, verify unit then install | Unprivileged, immutable program |
| `deploy/nginx/sso-site.conf.example` | Domain, certificate, forwarding include and `nginx -t` | Canonical HTTPS, unknown Host denial, no actuator proxy |
| Optional monitoring | Separate config/credential, `production,monitoring` | Health on private port 9091; [monitoring](monitoring.md) |

## 5. Verify

1. Private readiness `UP`: port 9000 without monitoring, 9091 with it.
2. Actual HTTPS Discovery/JWKS/Secure Cookie.
3. Administrator forced change/relogin/recovery custody.
4. [Opening checks](acceptance.md).

Failures: [troubleshooting](troubleshooting.md). systemd was not executed on the Windows build host; verify on target Linux.
