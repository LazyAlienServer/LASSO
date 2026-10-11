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

### Generate a compatible hash privately

Linux needs `htpasswd` and `setfacl` (Debian/Ubuntu: apache2-utils and acl; install using the host's package procedure). Use an independent random password, typically 24–48 ASCII characters within bcrypt's 72-byte limit, retained in private collector/password-manager storage. SSO receives the hash; collector password_file receives the original password. Run in a private terminal with tracing disabled and HASH_FILE matching LAS_SSO_SECRET_DIR:

```sh
(
  set +x
  set -euo pipefail
  umask 077
  HASH_FILE='/etc/las-sso/secrets/monitoring-hash'
  test -d "$(dirname "$HASH_FILE")"
  BCRYPT_LINE="$(htpasswd -nBC 12 las-sso-metrics)"
  BCRYPT_HASH="${BCRYPT_LINE#las-sso-metrics:}"
  [[ "$BCRYPT_HASH" =~ ^\$2[aby]\$12\$[./A-Za-z0-9]{53}$ ]]
  printf '{bcrypt}%s' "$BCRYPT_HASH" > "$HASH_FILE"
  chmod 0600 "$HASH_FILE"
  setfacl -m u:10001:r "$HASH_FILE"
)
```

The tool prompts twice without echoing. Do not use `htpasswd -b` or print the hash into arguments. Default bcrypt cost 5 is rejected; `-C 12` is accepted. Remove the username and retain literal `{bcrypt}` as above. Failed generation stops before replacement. Existing-credential rotation follows M04, retaining private recovery material and updating the collector.

Accepted shape: `{bcrypt}`, then `$2a$`/`$2b$`/`$2y$`, cost 10–14 and 53 bcrypt alphabet characters. Plaintext, a whole `username:hash` line, quotes or spaces fail `PRODUCTION_MONITORING_CREDENTIAL_INVALID`. Refresh changed bind mounts with the selected helper's `dc up -d --no-deps --force-recreate sso` as needed.

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

## M01 Install scraping, rules and real notifications

**Prerequisites/location:** The controlled deployment directory of installed Prometheus/Alertmanager programs or containers. SSO Compose does not provision these services.

1. Copy the templates to external configuration storage. Identify the real collector→SSO, Prometheus→Alertmanager and Alertmanager→receiver addresses.
2. A container collector joins the actual private monitoring network and targets `sso:9091`. Host loopback works only when reachable from that collector. Container `127.0.0.1:9093/9094` refers to itself, so adapt the template addresses.
3. Prepare the password/hash in section 2 and a readable collector `password_file`; webhook authentication uses an independent credential. Review cross-host TLS/firewall settings; keep management private.
4. Set `rule_files`, targets, Alertmanager addresses, receiver, routing, `send_resolved` and escalation contacts. A placeholder receiver is not a notification channel.
5. Run the four checks/tests from section 3 in the actual configuration/mount environment. Only after all exit 0, start or reload the services and verify the new configuration was applied.
6. Check the las-sso target is UP, query `up`, snapshot availability and last-success time, and inspect rule-loading errors. A 401 requires checking collector credentials/hash rather than the OIDC client secret.

**Expected:** Actual scraping, loaded rules and fresh data. **Failure:** Syntax-only success does not prove credentials/files/network work; zero targets are not successful coverage.

## M02 Import Grafana and verify role/data

1. Create a datasource pointing to the actual Prometheus, test connectivity and record its UID.
2. Import the packaged dashboard and map the datasource. Preserve the old revision before replacing an existing dashboard.
3. Choose a time range with real activity and inspect every panel/query. Reasonable zero values differ from no data; investigate datasource, metric name and time selection before declaring health.
4. Log in separately as Viewer and verify desktop/mobile readability and refusal of saving, overwriting or datasource editing. The administrator's Cookie cannot prove Viewer permissions.
5. Record dashboard/datasource revisions, role and sanitized results, not authentication screenshots or Tokens.

**Expected:** Readable actual data; Viewer cannot write. **Failure:** Import success alone establishes neither data, permissions nor notifications.

## M03 Trigger, recover, acknowledge

**Prerequisites:** Isolated preproduction or an authorized maintenance window. Do not stop a production database or replay RTs merely to test alerts.

1. Agree time, category and impact with the on-call recipient. Check unintended silences/inhibition and retain the real rule windows.
2. Introduce the smallest reversible fixture failure, such as an unavailable collector target. Record its start and allow the configured `for`, evaluation and `group_wait` delays.
3. Verify pending→firing in Prometheus, active alert in Alertmanager and actual delivery. Ask the recipient to acknowledge time, category and environment.
4. Restore the target/configuration, verify UP/fresh snapshots, then wait for and acknowledge resolved delivery. If missing, check routing, `send_resolved` and receiver behavior.
5. Plan safe tests for critical database/disk/backup/certificate/clock coverage separately; password attacks or deliberate RT reuse are not required to fire every optional counter.
6. Remove temporary settings/silences, retain sanitized results and observe recovery. Genuine incidents require root-cause diagnosis.

**Expected:** Actual firing and resolved delivery. **Failure:** Firing in Prometheus is not delivery proof; successful scraping is not database readiness.

## M04 Collector credential rotation

1. Prepare an independent new password/hash, private recovery material and a coordinated application/collector window.
2. Update the external application hash and collector password file; check read permissions. Load the change using the reviewed process-recreation/reload procedure for the actual mounts.
3. Test new credential 200 and old credential 401 through protected configuration, not password arguments. Verify targets and snapshots recover.
4. Confirm notifications via M03, remove obsolete raw copies and update independent secret backup/configuration revision.

**Expected:** The collector uses the new secret and continues scraping. **Failure:** Repair hash/password mismatch, keep outage visible, and never enable anonymous management as fallback.
