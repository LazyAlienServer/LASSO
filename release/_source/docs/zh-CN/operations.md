# 运维：检查、重启、管理员重置与升级

[目录](README.md) · [English](../en/operations.md) · [排障](troubleshooting.md)

## 选任务

| 任务 | 本页 / 专项页 |
| --- | --- |
| 日常检查 | 下表 |
| 重启或管理员重置 | 第 1–2 节 |
| 升级与回退 | 第 3 节 |
| 表增长 / 清理异常 | [历史清理](history-retention.md) |
| 人工恢复 / 注销争议 | [管理员操作](user-guide.md) |
| 参考 BFF 撤销死信 / key轮换 | [参考队列](reference-revocation.md) |
| 备份 / 旧快照恢复 | [恢复流程](backup-and-recovery.md) |

## 日常检查表

| 检查项 | 正常结果 | 异常去哪里 |
| --- | --- | --- |
| readiness | 包含 DB，返回 `UP` | [数据库 / 启动排障](troubleshooting.md) |
| 私有采集 / snapshot | up有效、快照可用且新鲜 | [监控](monitoring.md) |
| 登录 / 管理员失败、RT reuse | 可解释的风险事件 | 私有审计、并发/泄露调查 |
| outbox 年龄、pending/dead | 任务可推进、死信已跟踪 | [排障](troubleshooting.md) |
| pool、磁盘、历史增长 | 有余量、清理吞吐符合预期 | [历史清理](history-retention.md) |
| 证书、备份、通知 | 续期/备份成功，通知可达 | [备份](backup-and-recovery.md)、[监控](monitoring.md) |

保留版本、源码commit、制品SHA、非秘密配置修订、PG/JDK/代理版本、Flyway状态。

## 1. Docker 常用操作

**位置：包根。** `dc()` 与[部署教程](docker-deployment.md)一致：

```sh
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
dc ps
dc restart sso
dc ps
```

**预期：** SSO重新健康，key/kid不变。不要重生成secret；默认消费方BFF重启丢登录，顶层SSO重入。

## 2. 离线重置管理员

**前提：** 排空/关闭入口，停止Web写入者，备份/维护授权已准备。

```sh
dc stop proxy sso
dc --profile maintenance run --rm --no-TTY=false maintenance admin-reset-password
```

| 提示 | 操作 |
| --- | --- |
| 显示当前管理员 username/UUID | 核对唯一管理员 |
| `Type YES` | 确认重置当前管理员 |
| 两次新密码提示 | 隐藏输入，不录屏/管道 |

**成功结果：** exit0，新密码状态 `ACTIVE`；旧密码、会话、授权及恢复材料失效。Reset不是新的临时密码，不再要求bootstrap强改。

```sh
dc up -d sso proxy
dc ps
```

用新密码登录、核旧会话拒绝、重新生成和保管恢复码，再开放。

### JAR / PowerShell 方式

包根、真实终端、同一server-only外部配置：

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
./scripts/reset-admin-password.ps1 -Profile production -JarPath (Join-Path $PWD $m.artifacts.server.path) -ExpectedSha256 $m.artifacts.server.sha256
```

错误摘要在Java前拒绝；不用通配符选择“最新”JAR。Bootstrap见[Docker教程](docker-deployment.md)或[JAR教程](jar-deployment.md)。

## 3. 升级与回退

| 顺序 | 操作 | 成功判据 |
| --- | --- | --- |
| 1 | 验新包、制品、扫描；隔离演练已填数据升级 | 新版本证据匹配 |
| 2 | 核备份可恢复，记录epoch/Flyway/旧镜像及配置 | 回退材料齐全 |
| 3 | 停proxy/sso及相关writer | 无并发写入/worker |
| 4 | 选新精确镜像，离线database-migrate | 迁移exit0 |
| 5 | 重跑runtime权限SQL | Flyway历史只读 |
| 6 | 启动、readiness/TLS/管理员/已上线消费方冒烟 | 所有必要路径通过 |
| 7 | 记录版本时间和結果，逐步开放 | 通知/观察正常 |

- V16普通索引DDL需目标数据量的时间/磁盘演练。
- Flyway只前向；旧binary只有已验schema兼容才可回退，生产门禁拒未知futuremigration。
- 恢复旧快照执行[新epoch/key/消费方屏障](backup-and-recovery.md)，不是只换JAR。
- `down --volumes`删除数据库，不用于升级、重启或回退。

## 4. 事件记录模板

| 字段 | 填写内容 |
| --- | --- |
| 时间 / 环境 / 版本 | UTC、部署目标、image/JAR hash |
| 现象 | route、稳定错误类别、requestId |
| 影响与操作 | 停入口/恢复/授权记录引用 |
| 验证结果 | readiness、登录、旧状态拒绝 |
| 后续 | 负责人、复查时间 |

不记录密码、恢复码、Token、完整Cookie、私钥或队列密文。SSO故障是不可用状态，不等账号封禁。
