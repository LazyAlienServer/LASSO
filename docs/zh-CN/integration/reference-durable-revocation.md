# 参考 BFF 持久撤销

[English](../../integration/reference-durable-revocation.md) · [ADR-0009](../adr/0009-reference-bff-durable-token-revocation.md) · [P03 ADR-0013](../adr/0013-reference-revocation-operator-recovery.md)

参考样例的PostgreSQL/schema/migration不是Starter数据库API或LASLOG迁移。默认Session/授权客户端/index/replay/sid标记/刷新协调仍单JVM，重启丢业务登录，再顶层SSO重入。

## 启用与配置

使用durable-revocation，可合并https/application-security。保护外部配置，不以参数/日志/Git传真实key/密码：

| 属性 | 要求 |
| --- | --- |
| reference.revocation.jdbc-url、username、password | 独立PostgreSQL，不能SSO身份库；Flyway需参考schema迁移权限 |
| reference.revocation.key-id |1–64字母/数字/点/下划线/连字符，轮换用全新不复用ID |
| reference.revocation.key-base64 |独立随机32-byte AES、标准Base64，重启持久不变 |
| reference.revocation.allow-loopback-http |默认false，true仅隔离literal127.0.0.1/IPv6 loopback fixture |
| reference.revocation.worker-enabled |默认true，false暂停投递和自动清理，不停止接受 |
| reference.revocation.completed-retention |默认P30D，1–365天，仅完成回执，不删pending/leased/dead |
| reference.revocation.cleanup-batch-size |默认100，1–1000，首次一小时后每小时一批 |

启动迁移内嵌参考revocation-db V1–V2，非SSO V1–V15。DB/key/schema缺失失败，不回退memory。V2增加恢复代次/key registry/操作审计；首次升级未完成key ID须相同且保留密文认证成功后才能建registry。空队列也核key持有证明，同ID异key/未经轮换新key启动拒绝。

## 接受、失败与投递

submit正常返回只表示接受，不是远程撤销。参考REQUIRES_NEW提交后返回，AES-256-GCM绑定task/registration/issuer/client/endpoint/hint/key-id，数据库不存Client Secret。投递用可信当前登记secret、核snapshot身份/endpoint；完成擦ciphertext/nonce。仅HTTP200确认RFC7009，不跟跳转。

先本地退出再submit。LasSsoRevocationSubmission.REQUEST_ATTRIBUTE仅此退出请求保存ACCEPTED/FAILED/NO_TOKEN，消费方success handler/受控监控可观测失败，不含Token。默认内存sink现在对无效/未advertise端点/Confidential输入抛错，不静默返回；仍不持久作业。submit异常/登记缺失标FAILED，不报远程成功，不恢复本地登录。

**pre-submit crash窗口**：队列无法恢复未提交Token。采用可靠退出的消费方需明确补偿：破坏性边界前在自有存储独立持久化会话/授权关联恢复意图，随后对账不确定提交或通过支持的OP授权/会话撤销；若自动retry需Token，消费方Adapter自行加密/到期保留并验证。参考不原子合并Session移除/queuecommit。日志不是丢失Token的补偿；账号级撤销影响其他Session，需明确目标审查，不自动升级为routine全局退出。

每次claim一个到期task，DB时钟/2分钟lease/唯一fence、每campaign最多8claim/随机有界退避；第8次过期为LEASE_EXHAUSTED。HTTP200后receipt失败可到期幂等重投。队列写/claim/key操作短advisory锁，5秒lock timeout/15秒transaction timeout，不持锁网络请求；不等于完整分布式BFF。

## 离线操作

排空并停止全部producer/worker，确定精确参考DB/JAR/hash，经SPRING_CONFIG_ADDITIONAL_LOCATION给受控配置，不用SSO维护JAR/config。脚本启动独立non-Web上下文，不依赖OIDC Discovery/registration/worker；真实console输入YES。actor/reason是审计元信息不是认证，权限由主机/DB访问和可信制品约束。

只查询元信息：

```sql
SELECT id, registration_id, key_id, replay_count, attempt_count,
       available_at, lease_until, completed_at, dead_lettered_at, last_error_code
FROM reference_revocation.tasks ORDER BY created_at;
SELECT key_id, changed_at FROM reference_revocation.key_state WHERE id = 1;
SELECT occurred_at, action, task_id, actor, reason, replay_count, previous_attempts,
       affected_count, old_key_id, new_key_id
FROM reference_revocation.operator_audit ORDER BY occurred_at;
```

修复endpoint/client/DB且载荷可认证恢复后，精确task/当前generation：

```powershell
./scripts/reference-revocation-maintenance.ps1 -Command replay -JarPath $ReferenceJar -ExpectedSha256 $ReferenceSha256 -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'receiver repaired' -TaskId $TaskId -ExpectedReplayCount $ReplayCount
```

同事务审计/replay_count加1/新campaign attempt归0；pending/completed/旧generation/不可读拒绝。并发重复不能重置两次，不改endpoint/client/issuer，worker仍核当前登记。新周期最多8次，无自动dead reset。审查后恢复worker并监控完成；不手动设completed，不导出/解密Token。purge-completed清一批，自动retention仅非空批次审计；审计保留，pending/dead需處置而非到期删除。

## 排空换key

1. 停全部producer/worker（含旧V1二进制），当前key解决pending/dead/lease；任何completed_at为空都阻止轮换。不可恢复载荷为事件，需OP补偿及另定处置，不SQL删行绕门禁。
2. 独立生成新AES和全新ID，当前key继续配置；新材料以受控reference.revocation.next-key-base64供给，不参数/日志。备份registry/queue与两key，访问受限。
3. rotate-key指定精确artifact/hash、ExpectedKeyId/NextKeyId/Actor/Reason，核旧proof、排空、registry/审计同commit。同material新ID/退休ID复用拒绝，失败原key仍权威。
4. 换配置key-id/key-base64、去next临时配置，重启验新submit/delivery；旧proof不能submit/claim/ack。绕V2的旧程序必须停止。按备份恢复方案保旧key；恢复队列可重复撤销/丢后续任务，灾备需独立演练。

## 证据与边界

原37测试/真实Edge+SSO已提交重启撤销为历史[.4](development-delivery-2026-10-05-4.md)。P03增加真PG/HTTP V1→V2、replay/audit rollback/retention/key/race及offline context/console fixture，结果见[P03](../implementation/revocation-recovery-2026-10-07.md)。目标TLS/池/容量/key保管/通知/消费方采用及跨存储原子独立；SSO Back-channel outbox是另库/流程，本工具不能操作。
