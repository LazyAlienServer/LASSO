# Monitoring: install, verify, respond

[Index](README.md) · [中文](../zh-CN/monitoring.md) · [Troubleshooting](troubleshooting.md)

## 1. Choose the correct address

| Deployment | Management / scrape target |
| --- | --- |
| Compose | Internal port 9091; trusted collector on the private monitoring network uses `sso:9091` |
| Host JAR | Default `127.0.0.1:9091`, same-host collector |
| Cross-host | Reviewed private transport and verified TLS/mTLS |

**Monitoring moves health from port 9000 to 9091.** Public Nginx must not proxy it. Container localhost refers to that container.

Compose enables `production,monitoring`; host layout adds its separate monitoring properties template.

## 2. Independent credentials

| Recipient | Material |
| --- | --- |
| SSO | `{bcrypt}` hash, cost 10–14 |
| Collector | Raw password in protected password_file |
| Username | Fixed `las-sso-metrics` |

1. Generate independent high-entropy password/hash using a trusted offline tool.
2. Supply Docker `secrets/monitoring-hash` or host `LAS_SSO_MONITORING_PASSWORD_HASH`.
3. Raw password goes only to collector, not human/OIDC credentials.

**Expected:** GET Prometheus on the private management listener returns 401 without authentication and 200 with collector Basic. Cookies, non-GET methods and application port 9000 are different paths.

## 3. Install templates

| File | Adjust / verify |
| --- | --- |
| `deploy/monitoring/prometheus.yml` | Targets, rules path, password_file, mounts |
| `las-sso-alerts.yml` / `.test.yml` | Seven rules and fixtures |
| `alertmanager.yml` | Actual receiver/authentication/routing/send_resolved/escalation |
| `grafana-dashboard.json` | Actual datasource, eight panels/ten queries, Viewer read-only |

Templates do not provision collectors, Grafana, recipients or secrets.

```text
promtool check config prometheus.yml
promtool check rules las-sso-alerts.yml
promtool test rules las-sso-alerts.test.yml
amtool check-config alertmanager.yml
```

**Expected:** Exit 0; then verify actual data and firing/resolved notifications. Syntax-only checks do not prove credential files or transport work.

## 4. First response by alert

| Alert | Template trigger | Check |
| --- | --- | --- |
| LasSsoScrapeUnavailable | up=0 for 2 minutes | Process/listener/network/password |
| LasSsoSnapshotUnavailable | Unavailable or stale >60 seconds, for 1 minute | Readiness/DB/pool/sampler |
| LasSsoLogoutBacklog | Fresh oldest pending >300 seconds, for 1 minute | Worker/retry/receiver |
| LasSsoLogoutDeadLetters | Fresh dead count >0, for 1 minute | Private outbox metadata/receiver |
| LasSsoRefreshReuse | Committed reuse increases within 5 minutes | Audit/concurrent refresh/exposure |
| LasSsoAdminLoginFailures | ≥5 failures in 5 minutes | Audit/lock/abuse |
| LasSsoPasswordHashSlow | Match p95 >1 second, for 5 minutes | CPU/RAM/workload |

Example rules are not an agreed SLA. Add disk/backup freshness-failure/clock/certificate/resource alerts.

## 5. Read metrics correctly

| Observation | Meaning |
| --- | --- |
| Successful scrape | Does not prove valid DB snapshot |
| snapshot unavailable/stale | Last gauges are not current health |
| Sampler failure | Does not advance last_success |
| Default 15-second sampling | Rules assume 60-second freshness; tune together |
| Restart resets counters | Use rate/increase, not durable ledger |
| Reference queue | Separate DB, not SSO outbox |

Fixed low-cardinality labels exclude identity/Token/request/network IDs. Transaction facts publish after commit.

## 6. Credential rotation acceptance

1. Prepare new raw password/hash privately.
2. Update app hash/collector file and coordinate restart.
3. Confirm the new credential returns 200, the old one is rejected and scrape recovers.
4. Actual on-call receives firing **and resolved**; retain sanitized times/categories.

Dashboard import is not data/access validation. Viewer cannot save/overwrite/change datasource; verify target desktop/mobile readability.
