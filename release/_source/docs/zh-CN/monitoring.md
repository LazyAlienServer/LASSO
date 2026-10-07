# 监控

[目录](README.md) · [English](../en/monitoring.md)

## 私有监听与凭据

仅 Web 进程启用 monitoring，配置 `config/server/monitoring.properties.example` 和独立 LAS_SSO_MONITORING_PASSWORD_HASH。接受 `{bcrypt}`、cost10–14，通过可信离线 bcrypt 工具生成。应用只收哈希、collector 从私有文件收原密码；固定用户名 las-sso-metrics。不复用人类/OIDC凭据，包不含采集哈希/原密码/webhook bearer。

默认127.0.0.1:9091独立暴露 health/prometheus，**health 离开应用9000**。公网代理不转发管理端口。仅管理口无状态 GET `/actuator/prometheus` 接受独立 Basic；浏览器 Cookie/client凭据/non-GET/app port 不等价。仅暴露端点但未启 monitoring 仍拒绝。跨主机 Basic/webhook 使用验证 TLS/mTLS 或受保护传输；127.0.0.1仅同机样例，容器 localhost 是容器自身网络。

## 安装配置

deploy/monitoring/prometheus.yml 引用 `/etc/prometheus/las-sso-alerts.yml`、同机9091、私有 password_file；明确调整 mounts/targets/topology。复制规则并执行 promtool check config、check rules、test rules las-sso-alerts.test.yml。Alertmanager 样例为同机9094受保护 webhook、bearer credentials_file/send_resolved；替换真实 routing/receiver认证/升级响应，执行 amtool check-config。配置不会自动启动容器或提供秘密/通知接收器。

导入 grafana-dashboard.json，选择实际 Prometheus datasource，限制 dashboard/datasource 编辑。Viewer 不能 save/overwrite/改 datasource。八面板十查询需有真实数据，导入成功不够。历史桌面/移动 Viewer 检查不代替本环境权限/数据/宽度验证。

## 语义与处置

指标固定低基数，不以 user/client/Token/request/network ID 作标签。密码哈希 histogram 计真实计算；refresh reuse/队列等事务敏感事实 afterCommit 发布。Web-ready 独立15秒 sampler 计算缓存 DB session/queue gauges，scrape 不查 DB。snapshot_available=0或last_success过期意味着旧 gauge 无效；失败不推进 success 时间。interval允许1s–5m，但现有规则新鲜度60秒，维持15秒或同时调整规则。

| 告警 | 默认触发 | 首查 |
| --- | --- | --- |
| LasSsoScrapeUnavailable | up0持续2分钟 | 进程/监听/网络/采集密码/传输 |
| LasSsoSnapshotUnavailable | 不可用或超过60秒，持续1分钟且scrape有效 | readiness/DB/pool，不用旧零值断言健康 |
| LasSsoLogoutBacklog | fresh snapshot的最老pending>300秒持续1分钟 | worker/retry/receiver |
| LasSsoLogoutDeadLetters | fresh snapshot死信>0持续1分钟 | 私有outbox元信息，修复接收者后批准重放 |
| LasSsoRefreshReuse | 5分钟内已提交reuse增长 | 安全审计/并发刷新/泄露调查 |
| LasSsoAdminLoginFailures | 5分钟至少5失败 | 审计/锁定/攻击 |
| LasSsoPasswordHashSlow | Argon2 match p95>1秒持续5分钟 | 先查CPU/内存/工作量，再改密码参数 |

系统/JVM/HTTP/pool指标补充监控，不能代替readiness、生产容量或未实现的细分业务dashboard。向真实值班人员交付 firing **与resolved** 通知并保留脱敏时间/类别。规则是样例，不是已批准 SLA。参考队列在独立 DB，不被暗中算入 SSO outbox 指标。
