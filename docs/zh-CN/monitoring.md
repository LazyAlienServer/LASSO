# 指标与告警

[目录](README.md) · [English](../en/monitoring.md) · [凭据生成](configuration.md)

Compose为production,monitoring，health/metrics在私有9091，不在9000/公网Nginx。Actuator是运维前缀，不是管理员网页；liveness看进程，readiness包含DB。

## 立即看文本指标

私有终端、已选helper，不加-T：

```sh
dc exec sso curl --fail --silent --show-error --user las-sso-metrics http://127.0.0.1:9091/actuator/prometheus
```

提示时输监控**原密码**。SSO文件为`{bcrypt}`+hash，不用管理员/client secret。无认证401、正确Basic200。文本为当前快照，不保存图表历史。

## 持续采集

collector加入`<project>_monitoring`，容器localhost是自己，target用`sso:9091`；在采集器Compose固化external网络。挂载collector可读的私有原密码文件：

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

包Prometheus示例是host loopback，按实际容器网络改target/Alertmanager。跨host需私有TLS/firewall评估；host采集可另审loopback-only管理发布，不可无限制开放，默认validator明确要求管理不发布。

## 配置与核验

1. 外部复制deploy/monitoring，填实际target/rule挂载/Alertmanager/认证receiver；模板不创建collector/Grafana/收件人。
2. 实际文件运行promtool check config/check rules/test rules、amtool check-config，全部exit0再reload/start。
3. up=1、snapshot_available=1、`time()-las_sso_snapshot_last_success_seconds`在freshness窗内；scrape200不代新鲜DB gauge。
4. Grafana设置实际Prometheus datasource UID、导入dashboard、逐panel核真数据及Viewer只读；0与no-data不同。
5. 隔离/授权故障测pending→firing、实际收件确认、修复后UP/freshness及resolved；不缩rule延迟。按目标风险补disk/backup/clock/cert/resource。

## 处置与轮换

| 告警 | 首查 |
| --- | --- |
| scrape不可用 | listener/network/process/collector密码 |
| snapshot旧/不可用 | readiness/DB/pool/sampler，旧gauge无效 |
| logout积压/死信 | 有界outbox元信息/TLS/receiver/retry |
| RT reuse | audit/并发/泄漏，不故意重放真实RT |
| admin失败/hash慢 | rate/lock/audit、CPU/pool/workload |

轮换独立新原密码/hash、协调两端/recreate/reload，新200旧401、targets/实际通知恢复再退旧副本。restart counter归零，用rate/increase非durable账本；身份/Token/request/network ID不作label。
