# Monitoring

[Index](README.md) · [中文](../zh-CN/monitoring.md)

## Private Listener And Credentials

Enable `monitoring` only on the web process, supplying `config/server/monitoring.properties.example` and a dedicated `LAS_SSO_MONITORING_PASSWORD_HASH`. Accepted format is `{bcrypt}` with cost10–14. Generate through a trusted offline bcrypt utility; app receives hash only, collector receives raw password through a private secret file. Fixed user is `las-sso-metrics`. Never reuse human/OIDC credentials. No collector hash, raw password or bearer webhook value is shipped.

Default management address127.0.0.1/port9091 exposes health/prometheus separately; **health moves away from app9000**. Public proxy must not route management. Only independent stateless GET `/actuator/prometheus` on management listener accepts collector Basic. Browser Cookie/client credentials/non-GET/app port are not equivalent; exposure without enabled monitoring remains denied. For cross-host Basic/webhook, provision validated TLS/mTLS or protected transport; `127.0.0.1` examples are same-host, and container localhost is its own container namespace.

## Install Configuration

`deploy/monitoring/prometheus.yml` references `/etc/prometheus/las-sso-alerts.yml`, same-host9091 and a private password_file; adjust mounts/targets/topology explicitly. Copy the packaged rule file, run `promtool check config`, `promtool check rules`, and `promtool test rules las-sso-alerts.test.yml`. Alertmanager template uses a protected same-host9094 webhook with bearer credentials_file/send_resolved; replace it with actual routing, receiver auth and escalation, validate with `amtool check-config`. These configs do not launch containers or provision secrets/receivers.

Import `grafana-dashboard.json`, select your Prometheus datasource and restrict dashboard/datasource edits. Viewer access must not save/overwrite/change datasource. Eight panels include ten queries; observe actual data rather than a successful import alone. Historical desktop/mobile Viewer checks are not a substitute for your Grafana access/data/width validation.

## Semantics And Response

Metrics use fixed low-cardinality outcomes, no user/client/Token/request/network IDs in labels. Password hash histogram measures actual hashing work. Commit-sensitive refresh-reuse/queue facts publish after commit. A web-ready independent15-second sampler computes cached DB session/queue gauges; scrape itself does not query DB. `snapshot_available=0` or stale last_success invalidates last-known queue/session values; failed sampling never advances success time. Snapshot interval accepts1s–5m, but shipped freshness rules assume at most60s; retain15s or tune rules together.

| Alert | Default trigger | Initial action |
| --- | --- | --- |
| LasSsoScrapeUnavailable | up0 for2m | Process/listener/network/collector password/transport |
| LasSsoSnapshotUnavailable | unavailable or older60s, for1m while scrape works | Readiness/DB/pool; do not infer queue healthy from stale zeros |
| LasSsoLogoutBacklog | oldest pending >300s for1m with fresh snapshot | Worker/retry/receiver connectivity |
| LasSsoLogoutDeadLetters | dead >0 for1m with fresh snapshot | Restricted outbox metadata and receiver repair before approved replay |
| LasSsoRefreshReuse | committed reuse increase in5m | Restricted security audit and concurrent-refresh/compromise investigation |
| LasSsoAdminLoginFailures | >=5 failures in5m | Audit/account lock/abuse review |
| LasSsoPasswordHashSlow | match Argon2 p95 >1s for5m | CPU/memory/workload before password parameter changes |

System/JVM/HTTP/pool metrics complement these but do not replace readiness, production capacity tests or unimplemented per-operation dashboards. Deliver real firing **and resolved** notification to the on-call recipient; preserve sanitized timestamps/categories. Package rules are examples, not an agreed SLA. Reference queue metadata uses its own DB and is not silently counted in SSO outbox metrics.
