# Troubleshooting reference

[Index](README.md) · [中文](../zh-CN/troubleshooting.md) · [Docker guide](docker-deployment.md)

Use [N02/N05](host-nginx-deployment.md)'s helper for the selected external Nginx. Inspect host Nginx logs separately; `dc logs proxy` applies only to the bundled alternative. SSO `Restarting (1)` needs its latest startup exception, not repeated readiness curl while the process exits.

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
| PRODUCTION_KEY_ID_INVALID | Invalid public kid/development marker | N02 legal stable ID, distinct from alias; recreate after env change |
| PRODUCTION_MONITORING_CREDENTIAL_INVALID | Plaintext/missing prefix/full htpasswd line/wrong cost/quotes/spaces | Monitoring guide's `{bcrypt}` cost10–14, refresh mount and collector original password |
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
| Password change required | Temporary bootstrap password / credential state | Complete U03. A manual recovery credential uses U08's two-step recovery, not login as a temporary password |
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

## T01 Diagnose by layer and verify repair

**Expected:** The original fault is corrected and its path, negative checks, readiness and notifications are verified with a recorded configuration revision.

**Failure:** If the fault persists, retain the failing category and send minimal evidence to its owner; protected access stays closed.

1. Record UTC, version/image/source and symptoms before a restart loses evidence. Use section 1 to verify the intended target.
2. For an external outage check proxy/certificate/host/ports. For 502 check SSO readiness; for readiness/liveness 503/200 inspect DB/pool rather than restarting repeatedly.
3. A write 403 requires distinguishing identity, role, recent evidence, CSRF and origin. Business-only failure requires checking the consumer's client/URI/session ownership, not speculative account mutations.
4. Inspect bounded logs, read-back and audit privately. Lost one-time output requires checking commit then following U07/A04/A08, not retrieving old secrets.
5. Apply a minimal reviewed repair through O03/O04/M01. Reproduce faults in isolation and preserve security checks.
6. Retest the original path, expected refusal, readiness and notifications. Record recovery time, limitations and review; a running container alone is not success.

**Still failing:** Protected access stays closed, minimal metadata to owning module; SSO outage neither ban nor game-kick authority.
