# 历史清理与表增长排查

[目录](README.md) · [English](../en/history-retention.md) · [监控](monitoring.md)

## 保留速查

| 数据 | 默认可清条件 |
| --- | --- |
| 终态 session | 终态超过90天，仍被存活SID引用者保护 |
| 已用/废普通和人工恢复摘要 | 满足终态/到期条件超过90天 |
| 授权 parent / RT family | 所有已知Token和family截止时间均过期30天 |
| 无parent的REVOKED RT | expiry和revoke均过30天 |
| 支持的成功outbox | 成功30天、无claim/dead |
| live hint/RT、pending/leased/dead/未知event | 不因TTL/count裁剪 |
| audit、身份/用户名/consent、注销/墓碑事实 | P05不自动删除或归档 |

未知expiry/不安全deadline保留，旧hint不重建。业务/参考队列保留是独立政策。

## 1. 只读报告

**Docker位置：包根，已定义`dc()`。** 密码从现有备份文件进入单个psql进程环境，不进argv。

```sh
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_backup_password)" exec psql -X -h 127.0.0.1 -U las_sso_backup -d las_sso -v ON_ERROR_STOP=1' \
  < scripts/history-status.sql
```

主机连接可用受保护connection service：

```text
psql --dbname=<protected-connection-service> -v ON_ERROR_STOP=1 -f scripts/history-status.sql
```

**预期：** count/fanout/size/deadtuple，无Token或用户值；10秒statement、500ms锁超时。

自定义期限时向psql传 `-v session_days=...`、`credential_days`、`authorization_days`、`refresh_days`、`outbox_days`，与实际配置一致。

## 2. 清理运行边界

| 参数 / 行为 | 默认 / 限制 |
| --- | --- |
| 周期 / phase batch | 5分钟 / 100个parent或对象 |
| 单phase | REQUIRES_NEW；10秒事务、5秒statement、500ms锁 |
| 忙行 | SKIP LOCKED/recheck；忙跳不算成功 |
| 失败 | 当phase回滚，其他phase继续 |
| 授权含 >10000 RT | 跳过并报告 |
| 级联 | parent batch不等child-row/WAL固定上限 |
| 维护 | `las.sso.scheduling.enabled=false`关闭jobs |

理论28800parent/天不是实测保证；核“可清到达量 < 实际处置量”。

## 3. 按症状排查

| 症状 | 先查 | 处理 |
| --- | --- | --- |
| eligible持续增 | scheduling、周期、清理failure/busy | 找失败/锁；按目标测量调batch |
| 表增但eligible少 | live授权、未知expiry、永久事实 | 容量/归档规划，不massdelete |
| oversized增长 | 单授权RT fanout | 跟踪异常流量/准入，保护复用检测 |
| DELETE后文件不缩 | deadtuple/autovacuum/WAL | 普通VACUUM ANALYZE复用空间 |
| hint突然不可查 | pepper变化/授权撤销/升级前丢失 | 核来源与失效策略，不能伪造hint |

固定指标前缀 `las_sso_history_cleanup_*`：rows、completed/skipped runs、failures、duration、oversized/busy。rows在commit后计数。

`VACUUM FULL`、reindex、分区及审计archive需独立停机/空间/备份方案；不能通过删除live安全历史释放空间。
