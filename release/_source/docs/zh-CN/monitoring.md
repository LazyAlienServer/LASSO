# 监控：安装、验证与告警处置

[目录](README.md) · [English](../en/monitoring.md) · [排障](troubleshooting.md)

## 1. 先区分部署地址

| 方式 | 管理监听 / 采集目标 |
| --- | --- |
| Docker Compose | 容器内9091；可信采集器加入私有monitoring网络后用 `sso:9091` |
| 主机 JAR | 默认 `127.0.0.1:9091`，同机采集 |
| 跨主机采集 | 明确私有网络及验证TLS/mTLS，不复制loopback示例 |

**启用monitoring后，health也从9000移到9091。** 公共Nginx不代理管理端口；容器内 `localhost` 是容器自身。

Compose已启用 `production,monitoring`；主机方式需加独立 `config/server/monitoring.properties.example`。

## 2. 准备独立采集凭据

| 使用者 | 收到什么 |
| --- | --- |
| SSO应用 | `{bcrypt}` 哈希，cost10–14 |
| 采集器 | 原密码的受保护password_file |
| 用户名 | 固定 `las-sso-metrics` |

1. 用可信离线bcrypt工具生成独立高熵密码及哈希。
2. Docker将哈希写入 `secrets/monitoring-hash`；host用 `LAS_SSO_MONITORING_PASSWORD_HASH`。
3. 原密码仅放采集器秘密文件，不用人类/OIDC客户端凭据。

**预期：** 私有管理口GET Prometheus：无认证401，专用Basic认证200。Cookie、非GET、app9000不等价。

## 3. 安装模板

| 文件 | 要调整 / 核验 |
| --- | --- |
| `deploy/monitoring/prometheus.yml` | 实际targets、rules路径、password_file、挂载 |
| `las-sso-alerts.yml` / `.test.yml` | 七条规则及测试样例 |
| `alertmanager.yml` | 真实receiver、认证文件、routing、send_resolved、升级响应 |
| `grafana-dashboard.json` | 真实datasource、八面板十查询、Viewer不可写 |

这些配置不自动创建采集器、Grafana、通知接收器或秘密。

工具可在采集器环境执行：

```text
promtool check config prometheus.yml
promtool check rules las-sso-alerts.yml
promtool test rules las-sso-alerts.test.yml
amtool check-config alertmanager.yml
```

**预期：** 配置/规则校验exit0；真实启动后看数据，再触发和恢复告警。syntax-only检查不验证凭据文件或网络。

## 4. 告警首查表

| 告警 | 模板默认触发 | 首查 |
| --- | --- | --- |
| LasSsoScrapeUnavailable | up=0持续2分钟 | 进程/监听/网络/采集密码 |
| LasSsoSnapshotUnavailable | 不可用或旧于60秒，持续1分钟 | readiness/DB/pool/sampler |
| LasSsoLogoutBacklog | fresh snapshot最老pending>300秒，持续1分钟 | worker/retry/receiver |
| LasSsoLogoutDeadLetters | fresh snapshot死信>0，持续1分钟 | 私有outbox元信息/接收者 |
| LasSsoRefreshReuse | 5分钟内commit的reuse增长 | 安全审计/并发刷新/泄露 |
| LasSsoAdminLoginFailures | 5分钟≥5失败 | 审计/锁定/攻击 |
| LasSsoPasswordHashSlow | Argon2 match p95>1秒，持续5分钟 | CPU/内存/工作量 |

默认规则是模板，不是已批准SLA。补主机磁盘、备份新鲜度/失败、时钟、证书及资源告警。

## 5. 避免误读指标

| 情况 | 正确理解 |
| --- | --- |
| scrape成功 | 不证明DB snapshot有效 |
| snapshot_available=0或last_success旧 | 旧session/queue gauge不能表示当前健康 |
| sampler失败 | 不推进last_success |
| 默认snapshot15秒 | 模板规则允许新鲜度60秒；改interval须同步规则 |
| counter重启归零 | 用rate/increase；不是持久业务账本 |
| 参考撤销queue | 独立DB，不计入SSO outbox |

标签固定低基数，不放user/client/Token/request/network ID；事务敏感事实commit后发布。

## 6. 凭据轮换验收

1. 私密准备新原密码/哈希。
2. 更新app哈希和collector文件，协调重启。
3. 确认新密码200、旧密码拒绝、scrape恢复。
4. 真实值班人收到firing **和resolved**，保留脱敏时间/类别。

Grafana导入成功不等数据/权限成功；Viewer不可save/overwrite/改datasource，目标桌面/手机需核阅读。
