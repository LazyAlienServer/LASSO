# LAS SSO Monitoring and Alerting

- Implemented/verified2026-10-06 in [delivery .20261006.6](../integration/development-delivery-2026-10-06-6.md); [Chinese](../zh-CN/operations/monitoring-and-alerting.md).
- SSO-only, optional collection. The starter/reference BFF do not gain a monitoring credential, port, database table or business permission claim.

## 1. Enable collection

The default profile keeps health/info exposure and no monitoring listener. Add `monitoring` to the intended application profile, for example `production,monitoring`; this does not itself provision valid production identity configuration.

| Setting | Behavior |
| --- | --- |
| `LAS_SSO_MONITORING_PORT` | Dedicated management port, default9091; enabled configuration rejects missing/nonpositive/same-as-app port |
| `LAS_SSO_MONITORING_ADDRESS` | Default127.0.0.1; change only for an explicitly private/firewalled collector network |
| `LAS_SSO_MONITORING_PASSWORD_HASH` | Required independent `{bcrypt}` hash, cost10–14; fixed collector username`las-sso-metrics` |
| `las.sso.monitoring.snapshot-interval` | Default15s, allowed1s–5m; example alerts assume default15s and regard >60s stale |

Provision a high-entropy random collector password outside repository/logs/CLI history. Supply only its compatible bcrypt hash to the app; collector receives the raw password through a restricted secret file. The hash is still sensitive, never a public example/default. Rotate both app hash and collector file together; the current fixed credential is not a multi-key or dynamic-reload protocol. Restart app for the changed hash, and verify scrape recovery. Property rendering redacts the hash; missing/raw/weak hashes reject enabled configuration.

On the separate management listener, **GET `/actuator/prometheus`** requires that independent Basic credential. No cookie or persistent security context is created, and no browser saved request is retained. Human login Sessions, OIDC client secrets and wrong/anonymous credentials do not authenticate a scrape. The collector identity cannot query first-party eligibility or human/admin business routes. Even the correct credential receives403 on the application port or non-GET request. Without monitoring enabled, accidental Prometheus exposure is explicitly denied. The added order0 chain targets Prometheus only; existing OIDC/browser/internal authorization remains authoritative.

A distinct Spring management listener relocates health endpoints too: use the management port for liveness/readiness. Details remain coarse/never, readiness includes`readinessState,db`, liveness excludesDB. Do not forward the private management listener from the public Nginx virtual host. Bind the public app behind its existing controlled proxy; network firewall, credential-failure throttling and transport protection belong to deployment. Same-host loopback examples use HTTP; off-host Basic/webhook credentials require validated HTTPS/mTLS or a protected transport. No remote JMX listener is enabled by this feature.

## 2. Actual metric contract

Micrometer names map to Prometheus underscores/`_total` or`_seconds`. Counters/timers are process-local and reset on restart; use `rate`/`increase` and restricted durable audit for investigations. They are not an authorization source or exactly-once business ledger.

| Prometheus metric | Semantics and bounded labels |
| --- | --- |
| `las_sso_login_attempts_total{result,reason}` | Form-login success handler after session/evidence setup; failure handler rejection, pre-auth network limit/CAPTCHA. Fixed result`success/rejected/limited/captcha_required`, reason`none/credentials/locked/disabled/expired/network/captcha/other`. CSRF rejection and dedicated reauthentication are not all counted as login attempts |
| `las_sso_admin_login_failures_total` | Known-admin password-failure recording, increment after credential-attempt transaction commits; no identity labels |
| `las_sso_password_hash_seconds_{bucket,count,sum}{operation,algorithm,result}` | Existing application encoder duration, preserving hash formats/upgrade/exception behavior; operation`encode/match`, algorithm`argon2id/bcrypt/other`, result`success/mismatch/error`. Includes human/client/maintenance hashing, not a separate per-user series; collector hashes deliberately use independent encoder |
| `las_sso_internal_authentication_total{result}` | Internal Basic authentication gate`success/invalid_credentials/limited/unavailable`; successful authentication does not imply eligibility ALLOWED |
| `las_sso_service_access_total{decision}` | Controller query result`allowed/denied/unavailable`; rejected requests before controller do not produce an eligibility decision |
| `las_sso_token_refresh_total{result}` | Refresh provider transaction outcome`success/rejected/error`; client-auth failures before that provider not included |
| `las_sso_refresh_reuse_total` | Committed ROTATED-token reuse detection; no increase on rollback, repeated revoked-token denial is not a new reuse event |
| `las_sso_registration_success_total` | Successful new HTTP registration after service commit, not replay; direct service fixtures/internal calls and all rejected registration reasons not covered |
| `las_sso_backchannel_delivery_total{result}` | Persisted one-row claim receipt transition`success/retry/dead`; guarded update returning0 is not counted; global placeholder events excluded from successful HTTP deliveries |
| `las_sso_sessions_active` | Cached DB count of ACTIVE sessions with idle+absolute expiry still future; no Session IDs/subjects |
| `las_sso_outbox_pending`, `las_sso_outbox_dead`, `las_sso_outbox_expired_claims` | Cached counts of Back-channel/global-logout events; pending excludes processed/dead, expired claims older than2minutes |
| `las_sso_outbox_oldest_age_seconds` | Cached age of oldest pending logout event, including scheduled retry/leased work;0when none |
| `las_sso_snapshot_available`, `las_sso_snapshot_last_success_seconds`, `las_sso_snapshot_failures_total` | Observation validity, last-success Unix seconds and failed polling attempts. DB failure retains last-known gauges but sets unavailable; stale/failed data must not be treated as current |

Boot/Micrometer also exports JVM/GC/process/http/pool metrics. Domain labels never contain usernames, UUID, IP,Token,Cookie,sid,requestID,clientID,URI or exception text. HTTP instrumentation uses normalized route templates, not raw query/header labels. Collector deployment may add bounded`job/instance`; do not put identities into either. Pre-created fixed counters can have zero-value series and are not evidence of an event.

The DB snapshot is independent of business scheduling: one daemon poller starts after web application readiness, never on non-web maintenance, and shuts down with the context. Single query has2s JDBC statement timeout; acquisition still follows configured pool timeout. Scrapes read immutable cached state, perform no count SQL. Queries count aggregate rows and may need separate production cardinality/query-plan/timeout tuning; a short empty/test DB run does not certify large-table polling cost. Health/export remain accessible with cached unavailable state during a DB outage.

## 3. Collector, rules and dashboard

Delivered inputs under `las-sso-server/deploy/monitoring/`:

- `prometheus.yml`: same-host15s collection,5s timeout, password_file, example private Alertmanager address;
- `las-sso-alerts.yml`: seven rules for scrape2m, snapshot stale/down1m, logout age>300s1m, dead letters1m, committed refresh reuse, admin failures and Argon2 p95>1s5m;
- `las-sso-alerts.test.yml`: `promtool test rules` verifies scrape/poll failure/recovery, stale-data suppression and backlog thresholds;
- `alertmanager.yml`: private webhook with bearer credentials_file, `send_resolved:true`, grouped by alertname/job/instance;
- `grafana-dashboard.json`: eight panels/tenPromQL expressions, Prometheus datasource selector, instance selector, availability/freshness/session/login/hash/queue/pool views. Sessions/queue panels suppress unavailable/stale snapshots.

These are same-host examples with no included credentials. Container deployments must supply their own private addresses/mounts/allowlists rather than use container loopback for other services. Restrict Prometheus, Alertmanager, Grafana and webhook APIs and retention separately. Provision the webhook URL/token through deployment; this does not deliver QQ/email/on-call paging to a production operator. Do not post any Token or private audit payload to alerts.

From the monitoring config directory with a chosen supported Prometheus/Alertmanager installation:

```text
promtool check config prometheus.yml
promtool test rules las-sso-alerts.test.yml
amtool check-config alertmanager.yml
```

Missing external credential files are expected to fail runtime startup; syntax-only validation is not credential/transport acceptance. Alerts do not cover production disk, backup freshness, clock skew or every business flow. They do not restart services or automatically replay dead tasks. Snapshot validity must be checked before trusting queue/session values. Choose a snapshot interval ≤60s when using these unchanged example freshness rules.

## 4. Verification

Java21/Docker full build **21:42:33–21:48:35 Asia/Shanghai**, **504server+193starter+37reference=734**, zero failures/errors/skips,20tasks executed. Seventeen new cases: configuration6, transaction/encoder3, disabled-exposure1, real dual-portHTTP/PG7. Existing refresh/Back-channel tests assert counters against actual rotation/reuse/dead receipt results. Default and application-owned TLS/Edge BFF flows repeated on final .6 JARs **22:11–22:12**; existing cookie/reauth/CSRF/exit/forwarded-IP boundaries passed. Starter/public APIs/schema/policy unchanged; Prometheus runtime adapter1.17.1 and seven dependency rows added to server lock only.

Actual private process acceptance **22:01:03–22:06:30**: final-hashSSOJAR+PG18.4+Prometheus3.6.0+Alertmanager0.28.1+private bearer webhook. Separate network, host mappings loopback, no app collector credential in webhook/Prometheus logs. Uses local identity profile (ephemeral signing key) and monitoring profile; production persistent-key/TLS admission is separately preserved by the BFF exercise. Scrape5s/evaluation2s/snapshot1s and notification1s/2s are explicit fixture overrides; **rule expressions/for1m/2m remain exactly repository rules**.

Validated independent collector authentication/port/method/session separation, actual login+hash/internal query/session gauges, JVM/pool Prometheus series, all10dashboard expressions. A fixture6minute-old logout event triggered backlog after1m, marking it processed produced resolved; stopped/restartedDB produced unavailable snapshot/readiness503/liveness200 and firing/resolved; wrong collector password_file produced scrape-down after2m, restoring it produced resolved. All six notifications reached the actual webhook with only alertname/job/instance/severity labels. Grafana file/queries are validated, not a live imported/rendered dashboard. Reuse/admin/hash/dead-letter rules have source/config/regression coverage, not every rule independently fired end-to-end in this exercise.

Initial attempts used the old app-port readiness URL after management migration, then invalid fixture2sinterval/5stimeout; corrected as tooling, logs preserved. Semgrep remote rule download had transient TLS EOF, so scan reused the previously SHA-256-verified identical local rulepacks with metrics disabled; source remained local. Final SCA241lock/157JARpackage rows0reportedvuln, Semgrep232paths1retained redirect falsepositive0errors, Gitleaks source/JAR/history0matches, all subject to prior coverage limits. Five final monitoring and eight final TLS container logs passed targeted secret patterns.

Private runtime summary SHA-256 `f3c72c6ff14a1b074d53ed67260331a40fccf7ff725ae346d6e573b86e940983`; [manifest](../integration/development-delivery-2026-10-06-6.json) identifies302source/303scan scope/18artifact-config hashes and locks. Dedicated containers/network/DBvolumes, temporary app/hash/collector/webhook-secret files removed; acceptance ports free. Private reports/cache/logs remain temporary evidence. No commit/push/productionDB/productionmonitoring deployment or global certificate/hosts change.

## 5. Remaining

Subsequent .7 actual Grafana12.2.0 import/private datasource/Viewer desktop+mobile render/save-write denial and two five-minute TLS multi-subject workloads passed; [paired record](grafana-mixed-load-2026-10-07.md). This replaces the earlier "query-only" limitation at that isolated layer, not production Grafana auth/retention/soak. Refresh→logout400 was discovered and repaired separately; .6 alert evidence above remains historical.

Production private transport/credential rotation and failure-throttling, actual datasource/Grafana import/render/permission/retention, real on-call routing with retry/failure escalation, registration rejection/recovery/token-issue/transient-cleanup metrics and external disk/backup/clock probes remain. Representative multi-user OIDC/refresh/logout mixed sustained load, largeDB polling and monitoring overhead/soak remain. This completes the delivered monitoring subset and three real alert/recovery paths, not every runbook metric or production monitoring readiness.
