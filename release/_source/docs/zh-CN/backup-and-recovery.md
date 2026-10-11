# 备份与旧快照恢复

[目录](README.md) · [English](../en/backup-and-recovery.md) · [运维](operations.md)

**主机入口：** 用[N02/N05](host-nginx-deployment.md)独立helper，关闭主机本site入口后`dc stop sso`，不是内置`dc stop proxy sso`。B02独立restore helper用compose.host-nginx.yml及validator `--topology host-nginx`，仍只启PG再restore，Web关闭至同一epoch/key屏障完成。下方代码为内置替代，不误覆盖已选helper。

## 先确定恢复目标

| 项目 | 由负责人填写 |
| --- | --- |
| RPO | 可接受丢失时间 |
| RTO | 恢复时间上限 |
| 调度 / 保留 / 异机位置 | 频率、加密、独立保管 |
| 失败通知 / 恢复授权 | 真实渠道和操作员 |

Docker卷或同机dump不是异机备份。包不自动安装备份调度或PITR/WAL。

## 1. 备份清单

| 材料 | 核验 |
| --- | --- |
| PostgreSQL一致性backup | PG/schema/Flyway、版本、epoch、时间、hash |
| signing key/kid、pepper、外配置 | 独立加密保管、恢复可读 |
| 数据库/key密码、恢复码 | 分离保管，不与DB明文捆绑 |
| 消费方DB / 参考queue | 独立backup，queue匹配AES和endpoint/client |

备份密码用受保护文件/connectionservice，不进argv。custom `pg_dump`只是格式示例；必须验证实际隔离恢复。

旧快照不能重建backup后的改密、已用恢复凭据、账号/注销事实变化；恢复前记录影响。

## 2. 旧快照恢复步骤

### A. 隔离并停止writer

1. 排空公网入口，停止SSO/定时任务及相关消费方writer。
2. 恢复到独立、明确标识的目标DB；保留原backup。
3. 核schema兼容、唯一管理员、永久UUID/凭据/client/audit/注销事实。

### B. 记录恢复epoch

在受控DB会话只读查询：

```sql
SELECT security_epoch, updated_at, change_reason
FROM system_security_state WHERE id = 1;
```

**预期：** 单行epoch，供下一步expected检查。不能凭旧运维笔记猜当前值。

### C. 执行安全屏障

**Docker：** 选隔离目标的env/project，不使用仍开放的原项目；真实终端、同一精确镜像。

```sh
dc() { docker compose --env-file /etc/las-sso/restore.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
dc --profile maintenance run --rm --no-TTY=false maintenance rotate-security-epoch-after-restore \
  --expected-epoch='<restored-UUID>' --reason=POSTGRES_RESTORE_APPROVED
```

`restore.env`必须指向独立恢复project/DB/key；先按[Docker教程](docker-deployment.md)校验。命令中的UUID替换为步骤B实查值。

**JAR：** 包根、server-only配置，无monitoring/Web：

```powershell
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
./scripts/rotate-security-epoch-after-restore.ps1 -Profile production -JarPath (Join-Path $PWD $m.artifacts.server.path) -ExpectedSha256 $m.artifacts.server.sha256 -ExpectedEpoch '<restored-UUID>' -Reason 'POSTGRES_RESTORE_APPROVED'
```

**预期：** 操作员核目标并交互确认，事务新epoch/撤销安全状态，exit0。拒epoch不匹配/无console/Web或活跃jobs。

### D. 核结果

```sql
SELECT security_epoch FROM system_security_state WHERE id = 1;
SELECT count(*) FROM user_sessions WHERE status = 'ACTIVE';
SELECT count(*) FROM spring_session;
SELECT count(*) FROM oauth2_authorization;
SELECT count(*) FROM oauth2_refresh_token_history WHERE status <> 'REVOKED';
SELECT count(*) FROM oidc_password_challenges;
```

**预期：** epoch不同；后五项为0。pending outbox可保留，旧claim重置；审计/永久事实保留。

### E. 独立换key和消费方屏障

| 组件 | 必做 |
| --- | --- |
| SSO | 安装新持久signing key/kid，不能用恢复出的旧key开放 |
| 消费方 | 退休旧kid，清localSession/资格cache/密码证据 |
| 资格对账 | 新本地generation + fresh权威查询，拒迟到旧代响应 |

epoch不透明不可排序；version仅同epoch比较。不能随每个返回epoch反复切代。

### F. 验证再开放

| 检查 | 成功判据 |
| --- | --- |
| SSO | readiness、新Discovery/JWKS、新登录/UserInfo |
| 旧状态 | 旧Cookie/code/AT/RT拒绝 |
| 已上线消费方 | 新refresh/当前与全部退出/Back-channel通过 |
| 后台 | pending outbox / 到期注销按契约推进 |
| 运维 | 实测RPO/RTO、操作员签收、逐步开放 |

## 失败处理

| 失败点 | 处理 |
| --- | --- |
| epoch事务失败 | 保持关闭，核目标/当前epoch/DB/权限 |
| 已成功后key/消费方失败 | 保留新epoch协调修复，不回计数器 |
| 第二次restore | 新目标或明确当前epoch；不重试旧expected |

数据库事务不能原子更新外部key/cache。恢复不是复制DB或回退JAR；历史演练不代本目标backup验证。

## B01 创建、检查并异机保管一次备份

**前提：** 包根/Bash、源project dc已核验；las_sso_backup角色、保护外部目录/加密工具/异机存放已配置。它是一次逻辑备份，不安装调度/WAL。

1. 记录源version/schema/epoch、UTC开始、目标文件路径及授权窗口；停止proxy/sso和其他writer使运维快照边界清楚，PG保持healthy。
2. 预先用拥有者创建受限外部目录，选**尚不存在**的dump路径。在同一个Bash中使用已核验的源`dc`函数运行下方代码，首条失败即停止。Bash二进制重定向可用，Windows PowerShell5.1不要用文本pipeline传二进制。

```sh
set -euo pipefail
set -o noclobber
umask 077
BACKUP_DIR='/var/backups/las-sso'
test -d "$BACKUP_DIR" || exit 1
BACKUP="$BACKUP_DIR/las-sso-$(date -u +%Y%m%dT%H%M%SZ).dump"
test ! -e "$BACKUP" || exit 1
dc stop proxy sso
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_backup_password)" exec pg_dump -h 127.0.0.1 -U las_sso_backup -d las_sso --format=custom' > "$BACKUP"
test -s "$BACKUP" || exit 1
dc exec -T postgres pg_restore --list < "$BACKUP" > "$BACKUP.list"
sha256sum "$BACKUP"
```

3. 只有pg_dump/pg_restore list两者exit0才称文件生成成功；失败/截断文件标失败，不覆盖上一份有效backup。代码提前停止时记录失败，按已批准的源服务恢复流程恢复入口。清单只在私有目录检查，含库对象元信息。
4. 用运维已选择的加密机制保护dump，传到独立异机位置，验证远端加密归档hash/读取及解密权限；key/pepper/配置/恢复码按清单分离加密保管。不要在报告写密钥。
5. 记录开始/完成、PG/schema、hash/归档位置引用、结果；源服务恢复readiness后开放。配置定时调度/保留和失败告警，再做B02实际恢复，不能以list成功替代。

**预期：** 一致dump、可访问异机加密副本、源恢复。**失败：** 备份角色permission denied先核默认grants/迁移所有者，不临时给业务runtime超级权限。密码只从受保护文件进入单个pg_dump进程，不在argv。

## B02 恢复到独立空库，再执行安全屏障

**预期：** 隔离目标还原数据且所有者/权限正确；切流量前完成新epoch/key及旧状态/消费方屏障验证。

**前提：** 明确可丢弃的隔离目标，原source保持不受影响；dump解密后在受限目录。按Docker教程准备独立restore.env/project/PG卷及秘密/key，**只初始化PG角色，不先迁移应用空表**，否则dump对象冲突。

1. 包根定义目标dc；运行部署验证器，核project、镜像、端口/路径与源不混；只`dc up -d postgres`等healthy。确认目标public尚无应用schema、不会覆盖现有数据。
2. 将已校验dump作为二进制输入，目标migrator为所有者；没有drop/clean指向源库的步骤。

```sh
set -euo pipefail
dc() { docker compose --env-file /etc/las-sso/restore.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
python3 scripts/verify_docker_deployment.py --env-file /etc/las-sso/restore.env --compose deploy/docker/compose.yml
dc up -d --wait --wait-timeout 120 postgres
RESTORED_DUMP='/var/backups/las-sso/selected-restored.dump'
test -s "$RESTORED_DUMP" || exit 1
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_migrator_password)" exec pg_restore -h 127.0.0.1 -U las_sso_migrator -d las_sso --no-owner --no-acl --exit-on-error --single-transaction' < "$RESTORED_DUMP"
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
```

3. 每条命令核exit0；迁移只用于还原后校验/允许前向升级。按§2 B查询**目标**restored epoch，核UUID/Flyway/唯一管理员/永久事实及migrator所有权。
4. 不启Web，按§2 C执行expected-epoch真实TTY屏障及D的计数核验；再按E准备新signing key/kid、原受支持pepper和消费方退休/cache/generation屏障。
5. 完成F隔离冒烟/旧状态负例/新登录/通知与后台推进；测backup起点到恢复数据的RPO、故障开始到可服务的RTO，保结果后才讨论切流量。
6. 演练结束使用**隔离目标**的停/清理流程；真实恢复则记录最终服务目标和新配置，源和旧备份不静默删除。

**失败：** 还原事务失败保目标关闭，查schema/权限/版本，不能对源执行clean重来；屏障已提交后按当前新epoch修外围，不回退安全计数器。不同PG大版本/扩展需明确兼容验证。
