# 运维：检查、重启、管理员重置与升级

[目录](README.md) · [English](../en/operations.md) · [排障](troubleshooting.md)

**已选主机入口：** 按[N05](host-nginx-deployment.md)用compose.host-nginx.yml helper；先控制主机入口再只停/启SSO，验证器加`--topology host-nginx`。下方`proxy`属于内置替代，不将host helper改回compose.yml；主机续期用`nginx -t`后主机reload，权限按实际主机读者。

## 选任务

| 任务 | 本页 / 专项页 |
| --- | --- |
| 日常检查 | 下表 |
| 重启或管理员重置 | 第 1–2 节 |
| 升级与回退 | 第 3 节 |
| 表增长 / 清理异常 | [历史清理](history-retention.md) |
| 人工恢复 / 注销争议 | [管理员操作](administrator-guide.md) |
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

## O01 每日检查、停止与恢复

**前提/位置：** 已安装实例，包根/Bash，先定义第1节dc；确认env指向要操作的project。

1. `dc ps`核三服务healthy、选定镜像及只发布proxy。查私有readiness，curl加`--max-time 45`以覆盖DB连接等待，记录HTTP码；liveness另查，不互相代替。
2. collector核up及snapshot新鲜，查主机disk/backup/证书/clock状态；按专项手册看积压，不公开原日志。
3. 计划停止先通知受影响用户、关/排空入口，`dc stop proxy sso`；需停整实例再`dc stop postgres`。数据保留，不用down --volumes。
4. 恢复先`dc up -d postgres`、核healthy，再`dc up -d sso proxy`、等healthy；核HTTPS登录/Discovery、monitoring恢复后开放。
5. 仅SSO重启按第1节restart；使用原key/pepper/秘密，不自动运行bootstrap。BFF自身重启丢登录与SSO会话保持是两项政策。

**预期：** 同project/volume/key，健康/通知恢复。**失败：** DB503先恢复DB/pool，进程未起核门禁；不要无限restart。`restart`不载入改动的Compose环境/镜像，配置更新要用经审核的up/recreate流程。

## O02 离线管理员reset完整顺序

**预期：** 离线reset只提交一次，管理员用新ACTIVE密码登录，旧状态拒绝，恢复材料重新保管。

1. 核唯一管理员UUID、维护授权/窗口，记录当前version/Flyway/epoch/key ID；准备已验证恢复的backup。
2. 包根定义dc，停止proxy/sso及其他指向此库的writer；PG保持healthy。确认无并发维护进程。
3. 第2节真实TTY执行reset，核显示的用户名/UUID，输入YES、两次新密码；取消/错确认均应nonzero，不继续开放。
4. exit0后记录audit引用/时间；启动sso/proxy，核readiness，管理员用新ACTIVE密码登录（不是再次临时强改）。
5. 按U07生成/保存恢复码；隔离验证旧密码/会话拒绝，已上线消费者按自身流程新登录，确认通知/日志正常再结束窗口。

**失败：** 输出丢失时先通过受控查询/审计核是否提交，不用另一次reset猜测结果。no-console修终端；密码策略修输入。已提交后网络/通知失败修外围，不恢复旧哈希/epoch。

## O03 软件更新：保留配置与数据的执行流程

**预期：** 精确新软件沿用原DB/key/config，迁移、runtime权限及受支持冒烟路径通过。

**失败：** 保入口关闭，按下方回退/前向修复条件处置；不得跳过失败的迁移或授权步骤。

**前提：** 已取得新完整包、精确镜像、旧包/镜像/非秘密配置备份及迁移兼容结论；隔离库完成本次升级演练。**位置：** 新解压包根/Bash。实际secret/key/TLS/config在包外；同一`LAS_SSO_PROJECT`和现有postgres卷必须保持，不更换到空项目。

1. 运行`python3 scripts/verify-package.py`，核新manifest/source/JAR/hash；用新包镜像工具构建或加载经验证新镜像。记录旧/新SSO image ID，DB/proxy未更新时保持原ID。
2. 审查新release notes、Compose/properties差异与新增migration；正常软件更新不轮换secret/key，不重新bootstrap。新增配置只按本版说明合并到原受保护配置，不以example覆盖。
3. 按[备份步骤](backup-and-recovery.md)做一致性dump/外配置独立备份，核可恢复/磁盘余量；安排入口关闭，停原proxy/sso/全部相关writer。
4. 使用新包Compose及原project/external env，将`LAS_SSO_IMAGE`更新为新精确ID；保旧值在非秘密变更记录，核原DB image/volume/network一致。验证器exit0才继续。

```sh
set -euo pipefail
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
python3 scripts/verify_docker_deployment.py --env-file /etc/las-sso/deployment.env --compose deploy/docker/compose.yml
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
dc up -d --wait --wait-timeout 120 sso proxy
dc ps
dc exec -T sso curl --fail --silent --max-time 45 http://127.0.0.1:9091/actuator/health/readiness
```

5. 每条迁移/权限命令核exit0再下一步；无新migration时仍验证已有checksum。`dc ps`等healthy，再核SSO用户/管理员及已开放client的新登录、刷新、当前/全局退出、collector401/200及通知。
6. 保存version/image/schema/config revision/结果，不存secret；逐步开放并观察错误/资源/队列。无需每次重做DNS/秘密，环境变化另验。

**失败/回退：** migration或新服务失败保持入口关闭。读实际Flyway与提交情况，不能直接swap旧binary：旧程序可能拒future schema。只有已验新schema兼容时恢复旧镜像+对应Compose/config；否则修前向候选或经授权执行隔离backup恢复和新epoch/key/consumer屏障。PG大版本升级是独立dump/restore或pg_upgrade工作，不能把PG镜像一换当完成。

## O04 TLS证书续期与重载

**前提：** 运维已经配置真实CA续期方式，确认新证书SAN/chain/期限及私钥匹配；仅更新外部TLS目录，不替换OIDC signing.p12。

1. 在受保护外部目录按CA流程生成新fullchain/privkey，保权限/ACL读者UID101；先验证链和hostname，保存旧证书回退材料（私钥仍私密）。
2. 新文件安装后在proxy运行`nginx -t`；通过再reload，失败不reload。

```sh
set -euo pipefail
dc exec -T proxy nginx -t
dc exec -T proxy nginx -s reload
```

3. 从真实客户端新建TLS连接，核新有效期、无证书例外、HTTPS登录/Discovery正常，CA自动续期job/失败告警生效。
4. 容器挂载读不到新文件时核路径/inode/权限，按确认窗口recreate proxy；不要关闭CA校验或chmod644私钥。

**预期：** 新证书已实际提供，OIDC issuer/signing key未改变。**失败：** 语法/权限失败保原worker和旧证书，修复后再测；健康wget忽略证书的fixture探针不是续期验收。
