# 备份与旧快照恢复

[目录](README.md) · [English](../en/backup-and-recovery.md) · [运维](operations.md)

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
