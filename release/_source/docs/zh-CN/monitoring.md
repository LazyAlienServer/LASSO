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

### 私密生成兼容哈希

Linux需要`htpasswd`和`setfacl`（Debian/Ubuntu包apache2-utils、acl，按主机安装流程供应）。独立随机密码通常24–48位ASCII，保在bcrypt的72-byte以内；原密码在collector/密码管理器私密保管。SSO文件为哈希，collector password_file为原密码。私有终端关闭tracing，HASH_FILE须匹配LAS_SSO_SECRET_DIR：

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

两次隐藏输入，不用`htpasswd -b`，不显示哈希或放参数。默认cost5会拒绝，`-C 12`可接受；如上去用户名、保字面`{bcrypt}`。生成失败在替换前停止。已有凭据轮换按M04保私密恢复材料并更新collector。

格式：`{bcrypt}`后`$2a$`/`$2b$`/`$2y$`、cost10–14及53个bcrypt字母表字符。明文、整行`username:hash`、引号或空格触发`PRODUCTION_MONITORING_CREDENTIAL_INVALID`。按需用已选helper的`dc up -d --no-deps --force-recreate sso`刷新变更的bind mount。

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

## M01 安装采集、规则与真实通知

**前提/位置：** 采集器/Alertmanager所在受控主机或部署目录，已安装对应程序/镜像；SSO Compose不建立这些服务。

1. 包内deploy/monitoring复制到外部配置，保原包；明确collector→SSO、Prometheus→AM、AM→receiver地址。
2. 容器collector加入实际SSO私有monitoring网络、target sso:9091；host loopback需确实可达。容器127.0.0.1:9093/9094是自身，按真实拓扑改。
3. §2原采集密码/hash及collector password_file路径/权限，webhook另独立credential；跨主机review TLS/firewall，不公开9091。
4. 编辑rule_files/targets/alertmanagers、receiver/routing/send_resolved/升级联系人，不把模板localhost receiver当真实渠道。
5. 在实际配置/挂载环境运行§3四条check/test命令，各exit0再启动/重载，核reload成功不只container up。
6. Targets看las-sso UP，查询up和snapshot available/last_success，rule加载正常；401核collector用户名/password/hash，非OIDC secret。

**预期：** 真实采集/规则/新鲜数据。**失败：** syntax-only不验证文件/网络，0target不是通过证据。

## M02 导入Grafana及权限/数据核验

1. 配真实Prometheus datasource，测试连接/记UID。
2. Import包内dashboard并映射datasource；已存在先保旧修订，不无意overwrite。
3. 选含真实活动的时间范围，核每面板/查询数据或合理零；no-data查metric/source/time，不称健康。
4. 独立Viewer登录核桌面/手机可读，不能save/overwrite/改datasource；不用admin Cookie冒充Viewer。
5. 记dashboard/datasource修订及角色/非秘密结果，无认证截图/Token。

**预期：** 真数据可读、Viewer不可写。**失败：** Import成功不代数据/权限/告警。

## M03 触发、恢复及接收人确认

**前提：隔离预发布或明确授权窗口。** 不在未批准生产停DB或重放RT。

1. 值班人约定时间/类别/影响，核未被silence/inhibit，保规则原窗口。
2. 最小可回退fixture故障例如collector target暂不可达，记start，等待实际for/evaluation/group_wait延迟。
3. rule pending→firing、AM活动告警、真实receiver送达；接收者确认时间/类别/environment。
4. 恢复配置/服务UP/snapshot新鲜，等待resolved及真人确认；缺resolved核send_resolved/routing/receiver。
5. DB/disk/backup/cert/clock关键覆盖另选安全测试，不要求攻击密码/reuse RT触发所有可选counter。
6. 撤临时silence/config，留脱敏结果；真实故障查根因并核恢复观察。

**预期：** firing/resolved实际送达。**失败：** Prometheus firing不等通知成功，scrape200不等DB ready。

## M04 Collector凭据轮换

**预期：** 采集器使用新凭据持续采集，旧凭据拒绝，快照及告警恢复。

1. 新独立raw/hash/受限rollback，协调应用和collector窗口。
2. 更新外部app hash/collector password_file/权限；configtree更新需要审核的process recreate/reload，核实际mount语义。
3. 受保护配置验证新200/旧401，无密码argv；Targets/snapshot恢复。
4. M03通知、清旧raw副本、更新独立backup/非秘密修订。

**失败：** 修hash/raw不匹配，保监控中断可见，不开放匿名管理口。
