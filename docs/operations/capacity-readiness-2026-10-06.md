# Bounded capacity, monitoring observation and readiness repair — 2026-10-06

- [Chinese](../zh-CN/operations/capacity-readiness-2026-10-06.md), [final acceptance](final-artifact-acceptance-2026-10-06.md), [delivery .5](../integration/development-delivery-2026-10-06-5.md).
- This is a **short workstation observation**, not sustained capacity, a one-minute propagation SLA or production sizing. No production threshold/Argon2 parameter was tuned to obtain a green result.

## 1. Real defect and regression

The exact `.4` final JAR's `/actuator/health/readiness` returned200 after PostgreSQL stopped, while the internal first-party query correctly returned503. Liveness stayed200. `application.yml` enabled probe endpoints but did not include `db` in the readiness group; Boot's availability-only readiness did not express the application's database dependency.

`DatabaseReadinessIntegrationTest` was added **before** the repair and failed the database-down503 assertion. Adding `management.endpoint.health.group.readiness.include: readinessState,db` fixed it. The final regression uses real Tomcat HTTP/PG18.4, stops and restarts the same fixture container with a fixed loopback port, asserts200→503→200 readiness, continuous200 liveness, and exact coarse `{"status":"DOWN"}` without component details. Database tests retain disabledWithoutDocker; final full run has zero skips.

Initial recovery-fixture failures were separated: the image's initialization socket briefly appeared ready before final postmaster restart; dynamically published ports could change on Docker restart. Fixture TCP readiness and an explicit ephemeral-but-fixed loopback binding corrected those; application pool/JDBC logic was not changed on that evidence. No missing schema, key-management, administrator bootstrap, credential-policy or production-configuration startup validator was added. The readiness group checks database availability/current application readiness, not every proposed startup gate in the runbook.

## 2. Measurement topology

Final `.5` observation ended **20:05:11 Asia/Shanghai**, approximately one minute. Exact hashed JAR, Zulu21 on Windows,20logical processors, PostgreSQL18.4 container on a fixed loopback port, local HTTP/no forwarding/no BFF/TLS. Explicit fixture JVM heap256MiB→1GiB; Hikari max10/min2/connection-timeout2s. These are external test settings, not persisted production sizing. The local profile uses an ephemeral signing key; persistent-key/TLS evidence is in the separate final-artifact exercise.

Password hashing uses the exact final-JAR libraries and configured Argon2id parameters: salt16/hash32/parallelism1/memory19,456KiB/iterations2. Eight warmups,16checks per worker, one pre-encoded credential; isolated hash p95 was30.62/45.00/72.13ms at1/4/8workers. This is a hash-only CPU observation, not whole HTTP login or a parameter-strength certification.

Live fixture users/client were created only in the disposable DB; internal Basic client was explicitly Argon2id-encoded rather than the local example's cheaper hash. Queries include actual budget reservation/refund, secret verification, JDBC eligibility read, Tomcat and transport. One known active subject,96requests per phase,8warmups, closed connections, bounded workers. Success validates ALLOWED/version1/no-store;429 validates RATE_LIMITED/Retry-After. No stored Token/Cookie/password or subject/IP metric labels appear in output.

## 3. Final bounded results

| First-party query concurrency | Responses | Total observed req/s | p50 / p95 / p99 ms |
| --- | --- | --- | --- |
| 1 | 96×200 | 13.84 | 65.17 / 118.25 / 258.83 |
| 4 | 96×200 | 46.31 | 83.76 / 108.93 / 133.84 |
| 8 | 96×200 | 60.93 | 123.70 / 187.02 / 212.14 |
| 16 | 91×200,5×429 | 60.65, includes refusals | 196.91 / 551.15 / 878.47 |

Default client10/network20 budgets include concurrent in-flight reservations;16workers may be refused even with correct credentials. Every phase ended with0 FIRST_PARTY_AUTH client/network rows: successful reservations refunded, throttled reservations rolled back. This does not justify automatically raising budget thresholds or promising concurrent admission. The response-rate figure at16workers includes denied work, not successful-query throughput.

| Password-login concurrency | POST responses | Whole workflow req/s | POST p50 / p95 / p99 ms |
| --- | --- | --- | --- |
| 1 | 32×302 success | 3.70 | 83.80 / 131.20 / 175.50 |
| 4 | 32×302 success | 5.36 | 160.75 / 239.09 / 386.28 |
| 8 | 32×302 success | 5.51 | 181.40 / 343.44 / 347.64 |

Each login creates a distinct cookie jar, requests `/account` to establish a saved request, obtains actual CSRF then posts a valid password. Workflow elapsed includes preparation requests; percentiles time the POST only. Assert302 points to account,96active SSO sessions observed. This is one-subject contention, not independent-user, brute-force, registration, OIDC/refresh/Back-channel mixed workload or long-duration expiry testing. Earlier probe incorrectly expected `/account` without establishing a saved request; corrected fixture, not product repair. Earlier runs varied with host load; the last measured run is reported here, not the fastest selected result.

## 4. Monitoring and failure recovery

31one-second local same-user JMX samples; no remote JMX flag/listener or public metrics route added:

- maximum observed process CPU fraction0.2647 across the workstation's logical processors;
- maximum sampled heap438.50MiB,38threads, cumulative GC delta831ms;
- Hikari peak10active/10total,1awaiting;1-second samples may miss faster peaks;
- SQL snapshot96active sessions,0pending/dead outbox,11DB connections including observer,0lock waiters. This idle outbox is not queue-delivery capacity validation.

Actual stop/start result: readiness200→503→200, liveness200 throughout, internal query503 during outage→200 after recovery. Fixture2s pool timeout and bounded recovery retries are not a measured guarantee for default30s/production networking. Health details remain never exposed.

Anonymous `/actuator/metrics` returns302 to login with redirect following disabled; an earlier auto-followed200 was a login page, not a metrics response. Base exposure remains health/info. No custom domain MeterRegistry counters/timers/gauges, Prometheus scrape/exporter, dashboard or alarm delivery was found/implemented/tested. Runbook metric names are target requirements, not an existing SDK/endpoint. The captured JVM/pool/SQL observations give a baseline only; monitoring/alert work is still pending.

## 5. Evidence, privacy and follow-up

Final private report `capacity-verified/observation.json` SHA-256 `18555db1b65acae5b2af1b48f27b0f4a86f7ccc135ae2e5ca6da594bd9c357eb`; script/Java probe stay external temporary diagnostics. App/DB/volume and settings removed; two final process logs passed targeted credential/JWT/Cookie checks. All runtime artifact hashes match final manifest. Failed/corrected prior observations are retained separately, not hidden as passed benchmarks.

Required next: sustained ramps/soak, representative many-user and mixed OIDC/refresh/logout workloads, production CPU/memory/DB/network/proxy sizing, account/IP budget window boundaries, normalized secret-free low-cardinality domain metrics, authenticated private export/scrape, dashboards and exercised alert/recovery delivery. No productioncapacity/SLA/monitoring completion is inferred.
