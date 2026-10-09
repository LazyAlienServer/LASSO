# Troubleshooting reference

[Index](README.md) · [中文](../zh-CN/troubleshooting.md) · [Docker guide](docker-deployment.md)

## Locate the failing layer

```text
Unreachable → proxy healthy? → sso healthy? → postgres healthy?
Page opens, login fails → Cookie / CSRF / risk / credential state
Only business site fails → client / refresh / Back-channel / eligibility
Only metrics fail → management port 9091 / collector credential / snapshot
```

## 1. First Docker checks

**Package root:**

```sh
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
dc ps
dc logs --tail 100 postgres
dc logs --tail 100 sso
dc logs --tail 100 proxy
```

Inspect privately; share time/version/requestId/category, not raw authentication logs.

| Symptom | First check | Action |
| --- | --- | --- |
| Input validation fails | Blank values, host/port mismatch, mutable image ID, limits or shell overrides | [Deployment step 4](docker-deployment.md) |
| Image missing | never-pull ID not loaded | Inspect/load exact image |
| postgres unhealthy | Init error, PG UID/secret, disk/access | PG logs; existing volume does not rerun init |
| sso cannot start | DB/key/config/admin gate | Categories below |
| proxy restarts/unhealthy | TLS UID101/template/upstream | Proxy logs and certificate/host |
| 502/504 | SSO/DB/pool | Readiness first |

```sh
dc exec -T sso curl --silent --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:9091/actuator/health/readiness
dc exec -T sso curl --silent --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:9091/actuator/health/liveness
```

| Readiness/liveness | Meaning |
| --- | --- |
|200/200 | Process+DB ready; not all target gates |
|503/200 | DB/pool/readiness fault; repair connection, avoid restart storm |
|No connection | Process/listener/startup failure |

## 2. Startup categories

| Category/message | Meaning | Action |
| --- | --- | --- |
| `PRODUCTION_PROFILE_REQUIRED` / `PRODUCTION_PROFILE_CONFLICT` | Missing production or mixed local/test | Correct profile and source |
| `PRODUCTION_ISSUER_INVALID` / `PRODUCTION_ISSUER_CONTEXT_MISMATCH` | Canonical URI/path mismatch | Actual HTTPS host/port and root-path topology |
| PRODUCTION_SECRET_* | Missing/short/reused/dev secret | Protected files, never print |
| Unable to load persistent signing key | Path/UID/alias/password/RSA | Access/same password, no ephemeral fallback |
| PRODUCTION_ADMIN_INITIALIZATION_REQUIRED | No unique usable admin/expired temporary password | Offline bootstrap/reset |
| PRODUCTION_SCHEMA_INCOMPATIBLE | Checksum, migration state or future schema | Use compatible exact binary; no checksum repair |
| DB permission denied | Runtime needs migration/wrong grants | Migrator offline plus permission SQL |
| INTERACTIVE_CONSOLE_REQUIRED | No interactive terminal | Real terminal, `--no-TTY=false`; omit `-T` |

## 3. Browser/account

| Symptom | Check | Action |
| --- | --- | --- |
| Cookie/login loop | Secure __Host, Path=/, no Domain, HTTPS and consistent hostname | Correct proxy/Cookie settings |
| Write returns 403 | Session, administrator, recent password, CSRF, Origin and content type | New CSRF and genuine reauthentication |
| CAPTCHA/429 | Risk budget, retries and proxy IP trust | Wait for Retry-After; retain rate limiting |
| Password change required | Temporary bootstrap/manual credential | Genuine password change, not refresh |
| Eligibility denied | Freeze/deletion/held/credentials | Reason, not business-login override |
| One-time output gone | Closed/navigated cleanup | Supported reissue/rotation, no redisplay |

## 4. OIDC/refresh/logout

| Symptom | Check | Action |
| --- | --- | --- |
| Discovery/callback mismatch | Issuer, scheme, host, port, path, exact URI and CA | Preserve paths; retain TLS verification |
| Refresh invalid_grant/reuse | Same-session coordination, absolute expiry or revocation | End login after definite rejection; do not reuse old RT |
| Refresh temporarily returns 503 | Database, provider or network | Unavailable, not a ban or password evidence |
| Global logout returns 400 after refresh | V15+, live initial hint, pepper and client URI | Lost pre-upgrade hints cannot be reconstructed |
| Back-channel pending/dead | TLS, claims, lease, retry and local receipt | Repair the receiver; inspect metadata only |
| BFF restart ends login | Default single JVM | Expected; the queue does not preserve HttpSession |
| Lower version after restore | New epoch, local generation and in-flight responses | Compare within an epoch; reject the prior generation |

Contracts: [integration](external-integration.md). Reference task recovery: [queue](reference-revocation.md).

## 5. Metrics/recovery

| Symptom | Check | Action |
| --- | --- | --- |
| Metrics returns 401 | Dedicated Basic credential, hash and cost | Collector credentials, not human/OIDC credentials |
| Metrics returns 403/404 | Monitoring, GET and private port 9091 | Public actuator 404 is expected |
| Scrape succeeds, stale snapshot | DB, pool, sampler and last_success | Gauges are invalid; [monitoring](monitoring.md) |
| History grows | Eligible/busy/oversized/audit | [History](history-retention.md) |
| Epoch command refuses | Target, current epoch and repeated execution | No counter override; [recovery](backup-and-recovery.md) |

## Support record

Version/hash, UTC, route, category, requestId, readiness/liveness and action/result.

No passwords, codes, Tokens, full Cookies, private keys or decrypted queue contents. Keep TLS, CSRF, Origin and signature checks enabled.
