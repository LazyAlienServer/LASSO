# Metrics and alerts

[Index](README.md) · [中文](../zh-CN/monitoring.md) · [Credential generation](configuration.md)

Compose runs production,monitoring. Health and metrics are on private9091, not9000/public Nginx. Actuator is an operations prefix, not the administrator website. Liveness checks process; readiness includes DB.

## View text metrics now

In a private terminal using the selected deployment helper (no `-T`):

```sh
dc exec sso curl --fail --silent --show-error --user las-sso-metrics http://127.0.0.1:9091/actuator/prometheus
```

Enter the monitoring **original password** when prompted. SSO's file holds `{bcrypt}`+hash; neither administrator password nor client secret applies. Unauthorized401, correct Basic200. Text output is a snapshot, not historical graphs.

## Continuous collection

Connect the collector to `<project>_monitoring`. Container localhost points to itself; target `sso:9091`. Persist the external network attachment in the collector's Compose. Mount a protected collector-readable original-password file:

```yaml
scrape_configs:
  - job_name: las-sso
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    basic_auth:
      username: las-sso-metrics
      password_file: /run/secrets/las-sso-monitoring-password
    static_configs:
      - targets: ["sso:9091"]
```

The shipped Prometheus example assumes host loopback; adapt targets/Alertmanager URLs for real container networks. Cross-host transport needs reviewed private TLS/firewall. An optional host collector may use a reviewed loopback-only9091 publication, never an unrestricted port; the default validator intentionally expects management unpublished.

## Configure and verify

1. Copy `deploy/monitoring/` externally; set actual target, rule mounts, Alertmanager and authenticated receiver. Templates do not create any collector/Grafana/receiver.
2. Run `promtool check config`, `check rules`, `test rules` and `amtool check-config` against actual external files; all exit0 before reload/start.
3. Query `up{job="las-sso"}`=1, `las_sso_snapshot_available`=1 and `time()-las_sso_snapshot_last_success_seconds` within the configured freshness window. Scrape200 alone does not establish fresh DB gauges.
4. Grafana: actual Prometheus datasource UID, import grafana-dashboard.json, verify each panel and Viewer read-only behavior. Zero and no-data are different.
5. Isolated/authorized failure drill: verify pending→firing, actual recipient acknowledgement, repair, UP/freshness and resolved delivery. Preserve real rule delays. Add disk/backup/clock/certificate/resource coverage for target risks.

## Respond and rotate

| Alert | First checks |
| --- | --- |
| Scrape unavailable | Listener/network/process/collector password |
| Snapshot stale/unavailable | Readiness/DB/pool/sampler; last gauges are invalid |
| Logout backlog/dead letters | Bounded outbox metadata/TLS/receiver/retry |
| Refresh reuse | Audit/coordination/exposure; never deliberately replay a real RT |
| Administrator failures/hash slow | Rate/lock/audit and CPU/pool/workload |

Rotation: independent new original/hash, coordinated app/collector update and recreate/reload; verify new200/old401, recovered targets and real notifications, then retire old private copies. Counter resets on restart; use rate/increase, not a durable ledger. Identity/Token/request/network IDs never become labels.
