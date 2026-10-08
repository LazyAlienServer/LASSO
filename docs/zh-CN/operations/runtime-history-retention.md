# 运行历史保留与清理

[English](../../operations/runtime-history-retention.md) · [ADR-0015](../adr/0015-runtime-history-retention.md) · [P05测量](../implementation/history-growth-2026-10-08.md)

## 生命周期矩阵

| 数据 | 条件与保留 | 负责人 |
| --- | --- | --- |
| users/usernames/deletion/consent | P05不自动删，UUID墓碑/占用名永久，其余另确认策略 | 领域/运维 |
| admin/security/operator audit | 不自动删除/静默archive，测增长并定核验存储/权限/期限 | 运维非cleanup |
| spring_session/attributes | 原Spring Session JDBC expiry cron（通常每分钟）/attribute FK级联；不替repository/lifecycle，旧积压需观察，原批量删除不是固定batch | Spring/运维 |
| user_sessions | 无再次请求也将ACTIVE过idle/absolute标EXPIRED；终态实际expiry/revoke后90天可删，存活auth引用sid不删，旧sid仍拒 | history/请求检查 |
| authorization/security | 全已知Token/子RT expiry/family absolute都过30天；未知expiry/必要metadata缺失/无safe deadline保留；存行不延长JWT有效 | history |
| IDhint history | 存活auth全部hash保留，不单TTL/count；auth回收/撤销cascade | auth/FK |
| RT history | live CURRENT/ROTATED保reuse；仅REVOKED无parent且expiry/revoke都过30天可删 | 原trigger/history |
| recovery_codes | used/revoked较晚终态过90天，unused有效保留 | history |
| admin_recovery_credentials | 原expiryjob撤销/审计，use/revoke/expiry都过90天再删，不绕expiry审计 | expiry/history |
| outbox | 支持的成功global/backchannel processed过30天，无claim/dead；pending/leased/dead/未知event不删 | history/delivery |
| rate/captcha/recovery/idempotency/reauth | 原expiry/重试窗不缩，5min每表1000、一事务；SKIP LOCKED/重检 | transient |
| reference_revocation | 独立参考V1–V2，完成30天/batch100每小时；pending/dead/lease留P03处理 | 参考运维 |

## 配置与执行

las.sso.history.batch-size默认100（1–1000）；session-retention/credential-retention=P90D，authorization/refresh-history/outbox-retention=P30D。整天最高3650、最低90/30不能降低。cleanup-initial-delay/cleanup-interval默认PT5M，允许1秒–1天，未测目标不能盲缩间隔/增batch。全局scheduling=false关闭app清理/投递/注销，offline无独立history daemon。

各history阶段独立commit，10秒事务/5秒statement/500ms锁timeout；事务advisory避免并发执行，忙行跳/重检。auth最多扫10×batch、删batch个parent，RT→parent→security锁后重查，savepoint释放跳过部分锁。单auth>10000 RT跳过报告；parentcascade可变很多行，不是total child/WAL固定上限。timeout回滚该phase，scheduler继续其他phase。删到期历史不自动发remote logout。

V16加expiry/terminal/outbox部分索引及delete trigger所需RT authorization索引，普通Flyway非concurrent online建索引。代表数据预演/预留时间空间/停流量，P02拒未来/未完schema，旧P02二进制不能未验退回V16。空库/V15upgrade测试非生产备份恢复。

## 安全观察

显式reviewed DB运行source/package scripts/history-status.sql，psql ON_ERROR_STOP=1，自定义期限时传实际days：

```text
psql --dbname=<protected-connection-service> -v ON_ERROR_STOP=1 -v session_days=90 -v credential_days=90 -v authorization_days=30 -v refresh_days=30 -v outbox_days=30 -f scripts/history-status.sql
```

使用受控service/pgpass/秘密机制，不password参数/URI。只读10秒/500ms，数量/fanout/size/deadtuple，无Token/hash/userID。candidate估计，真实job还排未知/live引用/锁/子deadline；大报告timeout需查成本，不移除timeout。

Prome las_sso_history_cleanup_rows_total、completed_runs_total、skipped_runs_total、failures_total、duration_seconds_count/sum、oversized_authorizations_total、busy_authorizations_total（均前缀las_sso_history_cleanup_）。七phase固定，无身份配置label，rows commit后。skip非completed，completed0可能无eligible/保护/忙候选；oversized/busy是遇到次数非distinct人数/family。snapshot仅聚合未完成outbox，scrape仍缓存不SQL。

比较eligible积压趋势/吞吐：默认history每phase理论28800 parent/天（100×288），锁/fanout/error更少；六transient各1000/5min最多288000行/天；参考100/hour最多2400receipt/天。可持续清理需大于新eligible到达，受控测量调，不massdelete live history/dead。

## 增长与处置

SSO login增user-session/JDBC及attributes；OIDC增parent/security/首RT/hint。成功refresh通常增RT transition/新hint，重试/重复save不必新增hash。14天family、15minAT/提前60秒正常持续活跃约1440次refresh/family；忙/恶意consumer可更频繁，示例模型不是上界。hint/RT乘活跃auth/client/browser数，code/新family独立deadline。last_seen 5min更新，但每请求仍session校验锁。

反复busy/oversized/未知expiry为积压处置，数量报告/可信元信息调查，停有关writer选择保reuse/hint的reviewed处理；不truncate child/改status绕门禁。P05不虚构online Token频率准入policy。审计/注销需独立archive核验/人/策略，删除前确认；offsite加密备份有自己的残留期限。

DELETE产生dead tuple/WAL，通常不归还OS空间；看pg_stat_user_tables/autovacuum/analyze/n_dead_tup/disk/WAL/replica/bloat。普通VACUUM (ANALYZE)空间复用，VACUUM FULL/reindex/partition另停机/空间/backup计划，不禁autovacuum/repair schema/在EXPLAIN输出密码Token。

pepper换代影响RT/hint/recovery/transient识别，无无缝多pepper/rehash，须规划失效/保管/恢复epoch/consumer屏障。refresh/issue time不造近期密码。P05隔离验证，目标硬件规模/soak/backup/archive/消费仍P01/P08–P14。
