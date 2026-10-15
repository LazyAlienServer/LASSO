# Troubleshooting

[Index](README.md) · [中文](../zh-CN/troubleshooting.md)

Record version/image, UTC, route, safe code/requestId and health before restarting. Inspect bounded logs privately; do not share raw authentication/configuration captures.

```sh
dc ps
dc logs --no-color --tail 100 sso
dc logs --no-color --tail 100 postgres
```

Host Nginx is checked separately. `Restarting (1)` means the SSO main process exits; readiness curl cannot diagnose its startup exception. Healthy PostgreSQL alone does not establish correct SSO credentials/schema/admin.

## Startup

| Category | Action |
| --- | --- |
| PRODUCTION_PROFILE_REQUIRED/CONFLICT | Explicit production, no local/test mixing |
| PRODUCTION_ISSUER_* | Canonical HTTPS host/port/path, same servlet context |
| PRODUCTION_KEY_ID_INVALID | Stable1–128 ASCII letters/digits/dot/underscore/hyphen; remove example markers/quotes/spaces; env change needs recreation |
| Unable to load persistent signing key | PKCS12 path/readers/RSA/alias/store+private password |
| PRODUCTION_SECRET_* | Independent valid protected files, no examples or unresolved placeholders |
| PRODUCTION_MONITORING_CREDENTIAL_INVALID | `{bcrypt}`+valid cost10–14 hash, no username/quotes/spaces; original password only collector |
| PRODUCTION_ADMIN_INITIALIZATION_REQUIRED | Offline bootstrap for fresh DB or authorized reset/expired temporary password |
| PRODUCTION_SCHEMA_INCOMPATIBLE | Exact compatible binary/migrations/checksums; no checksum repair |
| DB permission/authentication | Correct runtime/migrator/grants; existing PG volume does not rerun initialization |
| INTERACTIVE_CONSOLE_REQUIRED | Real terminal, --no-TTY=false; omit -T for password/epoch maintenance |

[Configuration](configuration.md) and [deployment](deployment.md) give exact preparation. An env change uses reviewed `up --force-recreate`, not restart. Runtime errors follow their current exception rather than the last incident's category.

## Requests and monitoring

| Symptom | Check |
| --- | --- |
| External TLS/502 | Host Nginx/site/certificate/upstream/loopback and SSO readiness |
| readiness503/liveness200 | DB/pool; repair connection, avoid restart storms |
| Login loop | Canonical hostname/HTTPS/Secure host Cookie/forwarding |
| Write403 | Actor role/session, recent password, CSRF/origin/content type |
| CAPTCHA/429 | Risk/Retry-After; wait, preserve limits |
| Password-change required | Bootstrap temporary password uses U03; manual recovery uses U08 |
| OIDC callback mismatch | Trusted issuer/CA/exact URI and preserved path |
| Refresh invalid_grant | Local session ends; no old-RT deliberate retry |
| Transient provider503 | Unavailable, not a ban or recent evidence |
| Old ID hint logout400 | History/pepper/client URI; lost pre-upgrade hints cannot be recreated |
| BFF restart ends login | Initial single-JVM behavior; queue does not restore HttpSession |
| Metrics401 / stale gauges | Original collector password/hash, private listener, DB snapshot freshness |
| One-time output lost | Check committed state, then authorized reissue/rotation, no plaintext retrieval |

After minimal repair recheck the original route, expected refusals, readiness and applicable notifications. Keep unresolved protected paths closed; use [recovery](recovery.md) for restore barriers and [integration](integration.md) for consumer failures.
