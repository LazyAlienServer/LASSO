# P05 运行历史增长与清理

[English](../../implementation/history-growth-2026-10-08.md) · [ADR-0015](../adr/0015-runtime-history-retention.md) · [生命周期/运维](../operations/runtime-history-retention.md)

## 策略与实现

用户确认终态session/普通人工凭据摘要90天，全过期auth/orphan REVOKED RT/成功outbox30天，存活auth全部hint保护，审计/注销/身份事实不自动删。RuntimeHistoryCleanup有限phase/独立有界事务/SKIP LOCKED/重检，RT→auth顺序/释放部分锁。原短期清理跳忙行/timeout。原Spring Session JDBC expiry/属性cascade另验，不替换。监控排成功历史、用实测active-session expiry谓词。

V16加RT auth delete trigger、REVOKED expiry、active/terminal session、parent Token deadline、凭据终态、live/success outbox索引。无应用依赖/Starter/reference行为升级；P03参考保留复用，审计/pending/dead仍各负责。

## 合成SQL测量

工作站隔离PG18.4，1000合成user/100000session/10000auth/200000 RT与200000hint/各100000 outbox、reauth、admin audit。初源P04 JAR `2d11bc988aa77cecb653480dbc1f3d9d913c7e3e8d9c65803916eb88405288f7`（V1–V15），候选索引仅fixture，actual V16后另测。EXPLAIN ANALYZE/BUFFERS/WAL，warm合成非endpoint容量/生产SLA。首次seed漏metadata.updated_at修fixture，不product问题。

| 查询 | 前 | 候选结果 | 解释 |
| --- | --- | --- | --- |
|100parent DELETE/RTtrigger/hintcascade |1089.184ms |36.407ms | 缺RT authorization_id索引主导trigger |
|100active过期session候选 |16.711ms |0.271ms | partial expression索引避大scan/sort |
|原snapshot vs未完成outbox |24.169ms |7.734ms | 避99000成功历史扫描，后加索引无进一步明显收益 |
|1000expired challenge删除 |16.692ms |14.592ms | 已有expiryindex，改重点并发/timeout不宣称speedup |
|100过期family候选 |4.890ms |4.589ms | 单family候选索引无足够收益，final用parent deadline+familyguard |

baseline表+索引bytes：session25.83MB/auth1.95MB/RT41.89MB/hint32.85MB/outbox17.91MB/challenge10.33MB/audit16.44MB。省真实principal/Token字符串/Spring attributes且短payload，不能仅此定生产存储；20refresh/auth例中hash历史已占主要量。

## 当前Java服务测量

最终bootJAR类/依赖，明确seed V15后actual V16upgrade，无公开endpoint。35次真phase（各5）/batch100。expiry100：cold287.018ms后22.302–26.078ms；auth100：588.557–699.053ms含JDBC多lock/recheck/cascade；outbox100：16.374–18.686ms；空/保护phase12.430–23.655ms。5000 liveauth/100000 livehint不变；seeded V15建actual V16索引368.618ms，非生产停机估计。只读status/普通VACUUM (ANALYZE)通过。SQL36ms delete-only与Java约0.6秒范围/transport不同，不互换。

默认100/5min每phase上限28800 parent/天，实际到达需低于扣锁/error/fanout后的disposal。审计/未知expiry/oversized刻意保留，所以是安全生命周期+可见运维边界，不宣称全存储有界。没有另定准入时refresh/hint频率可任意高，按批准保live关联/观察fanout增长。真实payload/user/并发/硬件/hoursoak留P01/P13。

## 验证

真PG新增覆盖期限/未知expiry/family/childdeadline、终态sid拒、支持成功outbox/审计身份保护、凭据边界、batch/idempotency、SQLfault/5秒timeout rollback、parent更新/RTbusy race/释放部分锁、advisory、独立scheduledphase进度、超family保护。已有MockMvc真实refresh/reuse/初hintlogout两协议间运行cleanup，原JDBC expiry/cascade另查。fixture dates/pg_sleep为受控非所有livecrash交错。

最终强制JDK21/Docker全量 **585server+196 Starter+50 reference=831项**，失败/错误/跳过零；四组跟踪browser交互回归另于JUnit。当前server SNAPSHOT SHA-256 `72124a0d8668aeecdd425f164e03b9c3c2cd87ebe314caadfe2049cdd9f82293`，基线b901104加P05工作区。真实production JAR/hidden bootstrap/进程验证：scheduling=false过期session/旧success不变，true回收且pending/dead/freshsuccess留；1秒poll仅fixture、loopback HTTP加HTTPS issuer仅进程调度非TLS/browser/consumer。fixture JVM/PG/key/config清理。

V15→V16真实Flyway populated fixture核checksum/一次迁移/保auth+hint/重复无变化。SQL5秒timeout/rollback/并发锁仅所测非全系统无deadlock。首JDBC Session测试用了不可访问implementation type，改公开SessionRepository/Session，未改生产Session行为。无生产写/公开部署/新版本包/archive删除/完整目标容量批准，旧release字节不改，新字节需新交付ID。

最终537候选文件Gitleaks8.30.1/redact100扫描零发现；仓库515 UTF-8/text、1246本地链接/一个声明兄弟文档链接、三依赖锁/Wrapper checksum/16连续迁移通过。原0.1.0-dev.20261007.1包仍70文件/104链接及来源校验一致，fixture容器无运行残留。测量/build/scan原始报告保源码外私有，不称新依赖/image/DAST扫描、生产部署或目标容量批准。
