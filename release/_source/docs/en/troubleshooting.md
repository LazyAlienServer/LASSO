# Troubleshooting

[Index](README.md) · [中文](../zh-CN/troubleshooting.md)

Record version/hash, UTC time, route/category/requestId and sanitized status first. Reproduce on an isolated environment when necessary. Never attach Authorization, complete Cookie, code/Token, passwords/recovery codes, private keys or decrypted queue values. Do not weaken TLS/CSRF/Origin/signature/rate limits to make a failing path appear successful.

| Symptom | Check / expected action |
| --- | --- |
| Startup key/config error | JDK21, nonlocal external placeholders, PKCS12 path/read permission/alias/same password/RSA pair/kid, exact DB target. No fallback to ephemeral local key |
| Flyway validation/migration failure | Correct database/schema/history and privileges, candidate compatibility, previous migration hashes. Preserve failure and restore isolated backup; no checksum repair/drop-table bypass |
| Discovery/callback mismatch | Exact issuer scheme/host/port/path, HTTPS proxy canonical headers, exact redirect registration and same hostname throughout. Preserve paths; verify CA, no insecure TLS flag |
| Login returns CAPTCHA/429 | Actual risk/network budget, hidden retry/concurrency and audit outcomes. Wait as directed; maintain trust in client address forwarding |
| Forbidden browser write | Session/admin/recent-password, CSRF token, same Origin, content type and target lifecycle. Back-channel CSRF exemption does not apply to browser APIs |
| Cookie lost / endless login | Secure __Host cookie, Path=/, no Domain, correct forwarded HTTPS/base URL, unique business Cookie name, no hostname switch |
| Required-password or eligibility deny | Finish actual password change; review lifecycle/reason. Business login is not an override for frozen/deletion/held status |
| Refresh invalid_grant / reuse | Same-session single-flight, absolute RT family expiry, revoked client/session. Definitive rejection clears local auth; do not replay previous RT |
| Refresh temporary503 | SSO network/DB/provider state; return unavailable, not eligibility denial or new credential evidence |
| Refreshed global logout400 | This exact V15 build, live authorization and initial hint association, same key-pepper, registered logout URI/client/audience. Pre-upgrade lost hints cannot be rebuilt |
| Back-channel pending/dead | Signature/issuer/audience/sid receiver checks, TLS/timeout, outbox worker/leases/retries and executed local receipt. Fix receiver before replay; inspect metadata only |
| BFF restart loses login | Expected default single-JVM behavior; top-level SSO reentry. Durable task queue does not preserve HttpSession/authorized clients |
| Metrics401/403/404 | Monitoring profile, management9091 rather than app9000, GET, independent las-sso-metrics Basic, bcrypt format/cost and private transport. No browser credentials |
| Scrape works, snapshot stale | DB/pool/sampler, availability and last_success. Readiness503 on DB down; scrape cannot validate cached DB gauges |
| Restore receives lower version | New epoch and authoritative consumer generation barrier. Reject old in-flight responses; never select epoch lexically or flip on each response |
| Maintenance no console/hash failure | Interactive real terminal, exact package JAR/hash, explicit server-only config and target DB; no redirected password input or wildcard artifact |

Healthy liveness alone is not DB readiness. Monitoring changes health's port. SSO DB restoration, signing-key replacement and consumer cache/session barriers are separate coordinated steps. If the issue persists, capture bounded sanitized logs/audit/category and escalate to the designated operator; [operations](operations.md) and [recovery](backup-and-recovery.md) define safe continuation.
