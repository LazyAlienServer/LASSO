# 参考 BFF 持久撤销 profile

- [English](../../integration/reference-durable-revocation.md)／[ADR-0009](../adr/0009-reference-bff-durable-token-revocation.md)／[.4交付](development-delivery-2026-10-05-4.md)。
- sample实现位于reference/revocation，非Starter API或消费业务迁移。

## 显式启用

profile durable-revocation 可与 application-security组合，外部配置无默认Secret/key：

| Property | 要求 |
| --- | --- |
| reference.revocation.jdbc-url | 独立jdbc:postgresql DB，不用SSO身份库 |
| reference.revocation.username/password | 参考队列账号；sample迁移需建schema/table权限 |
| reference.revocation.key-id | 稳定1–64 letters/digits/period/underscore/hyphen |
| reference.revocation.key-base64 | 独立随机32字节AES key标准Base64，重启保留 |
| reference.revocation.allow-loopback-http | 默认false，开发显式true仅literal127.0.0.1/IPv6 loopback |
| reference.revocation.worker-enabled | 默认true；false接受但暂停投递用于维护/验证 |

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun --args="--spring.profiles.active=durable-revocation --spring.config.additional-location=$ReferenceQueueConfigUri"
```

可信confidential登记advertisedendpoint/client_secret_basic。使用受保护外部file/env不log/Git。profile启动明确Flyway参考revocation-db/V1迁移/history在reference_revocation；缺配置/DB/key/schema不符fail。默认无datasource/schema，原内存sink，DB依赖仅ref bootJAR非starter导出。sample DriverManagerDataSource短事务，非productionpool容量决策。

## 提交投递

submit独立事务commit加密材料后返回，localSession已退出。失败handler只类别不恢复/误报remote成功；submitcommit前crash仍丢。taskUUID/身份endpoint/hint/keyId认证AAD，clientSecret只当前内存resolve。issuer/client/endpoint变不能迁移旧Token，单Secret轮换可用。

DB时间/SKIPLOCKED/两分钟lease/claimtoken fencing，领取计attempt即使HTTP前进程死；HTTP/status/payload/config失败持久随机retry，8次dead。第8claim过期LEASE_EXHAUSTED不第9request。仅200、禁redirect；200后completion DB失败留lease恢复，允许RFC7009幂等重复非exactly-once，成功擦ciphertextnonce。

## 观察恢复

独立参考库安全元数据查询：

```sql
SELECT id, registration_id, attempt_count, available_at, lease_until,
       completed_at, dead_lettered_at, last_error_code
FROM reference_revocation.tasks
ORDER BY created_at;
```

监控oldestpending/leaseexpired/dead/worker类别，不导出解密Token/ClientSecret。completed/dead不自动清，环境定义retentionalert；加密dead仍需权限/key保护。

dead终态需operator先诊断clientendpointkeyDB再显式恢复，本版无replayAPI/自动reset/cleanup/keyrotation。旧任务未排空就换唯一key不可读；DB/key分离备份，旧DB恢复可重复已投递/漏较新任务，不宣称灾备连续性。

## 验证边界

37参考测试actualPostgreSQL18.4/HTTP/profile，真实Edge/SSO默认自有两模式接受→停/新JVM→原RT拒绝；lease/retry用fixtureSQL提前到期非每指令crash演练。仅queue持久，本地Session/Token/index/replay/sidmarker仍进程内，restart失登录；并发claim不等完整distributedBFF。productionTLSproxy/keyrotation/poolcapacity/operatorrecovery/业务接入独立。
