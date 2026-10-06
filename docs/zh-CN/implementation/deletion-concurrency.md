# 注销事务与并发契约

- 状态：2026-10-04 S3实现/受控PostgreSQL验证完成，真实部署待验。
- 英文对应：[Deletion transaction and concurrency contract](../../implementation/deletion-concurrency.md)。
- 决策：[ADR-0006](../adr/0006-user-first-deletion-batch-locking.md)，用户确认保留整批原子性。

## 1. 模块与原循环

DeletionRequestService负责申请/取消和凭据/墓碑执行，AdminDeletionRequestService负责暂缓/释放/审计，DeletionExecutionJob以advisory事务协调定时expiry/execute，共用用户/请求及资格version。

原取消user→request、管理员/expiry request→user。测试先用pg_blocking_pids确认实际阻塞：用户锁已持时等待暂缓/释放已有请求锁，expiry不在变更前跳过。原三项全部红，证明依赖倒置，不虚构强制deadlock victim或生产事故。

## 2. 交互变更

申请/取消仍用户先锁；申请时间移到取得用户锁后，等待不消耗30天。取消仅当前PENDING/HELD并清hold字段，version/event同事务。

管理员流程：

1. 按指定request ID无锁读候选owner；
2. 锁目标用户；
3. 锁/重检同request/owner；
4. 当前status(PENDING暂缓、HELD释放)及等待后的期限重检；
5. request/version/security/admin audit同事务更新。

取消先commit，管理员ACCOUNT_STATE_CONFLICT且无成功审计；管理员先commit，随后有效取消其状态。旧cancelled请求不改新申请；候选归属变化/消失拒绝。

直接execute先user再精确owned request，检查PENDING/execute_after<=now。wrong pair/notdue/held/cancelled不执行/version凭据不变。原管理员保护仍将due pending取消、不删管理员，作为历史/不一致状态的防御fallback；随后实现的[S4晋升门禁](admin-promotion-deletion.md)阻止支持流程中有有效请求的晋升。

## 3. 批次与两阶段原子

executeDue正limit、有限pending快照，distinct users按数据库UUID顺序SKIP LOCKED先取，再锁/重检请求。数据库UUID与Java UUID有符号排序不同；先取锁后处理仍按due/id优先。

定时run保留advisory事务、size100；processDueBatch：

- 无request锁选最多100过期HELD+100due PENDING；
- 一次稳定DB顺序锁整个预选user并集；
- 仅owned/unlocked且仍HELD、hold_until严格小于cutoff才release；
- 只从同预选request/已锁user重选due PENDING，含刚release，最多执行100；
- 第一阶段request变更开始后不加新user锁。

显式expiry入口用同user-first服务。user/request忙跳过、后续重试，归属/status/deadline重检；持续竞争可能延迟，无饥饿/时延保证。limit在跳过前选择，所以不填入任意新user，实际少于limit正常。

所有已处理/两阶段仍同外层事务整体rollback，无逐用户commit/claim/lease/自动事务重试。公开构造/方法仍可调，job Clock/cutoff与service Clock保留不同接缝；release用显式cutoff，执行用当前service time。正limit公共API不新限制100，定时本身仍100。

计数改**实际状态变更**非候选size；executeDue含管理员保护性取消，run=release+processed，同条release+execute计2。忙/变化计0，0不证明清空积压；异常整体回滚并传播，不报部分成功。

## 4. 已执行测试

DeletionConcurrencyIntegrationTest **24新增**，实际PostgreSQL18.4/Spring服务/事务：

- hold/release等user但未持request，独立SKIP probe；
- cancel/admin双向和delete/cancel双向，event/audit/version/credentials精确结果；
- busyuser/request重试，hold续期/取消防旧expiry，未commit hold阻删除，replacement/owner corrupt重检；
- direct owner/status/due、limits/真实count/幂等；
- 首version写前观察并集用户锁、advisory owner skip、并发batch不重复event/outbox；
- 真SQL trigger在第一target已删后第二故障两者rollback；作业phase2注入故障phase1也回滚；交互rollback；
- real user lock等待中前进Clock，暂缓过期拒绝且无version/audit。

latch/pg_blocking_pids驱动交错，bounded Future验证SKIP、不靠random sleep。eligibility spy除明示注入/观察外delegate真实；手动Clock service包真实TransactionTemplate。owner corruption/SQLfunctiontrigger只一次性库并清理、非production/migration。时间声明移动编译错误修正非原缺陷证据。

server目录Java21命令：

```powershell
.\gradlew.bat :test --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --no-daemon --console=plain
# 原3/3fail，扩大后green。

.\gradlew.bat :test --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

最后17:49–17:50Asia/Shanghai专项 **40(24+10+6)** 无失败/错误/跳过；2026-10-04约17:54–17:57强制全量 **227server/176starter** 零失败/错误/跳过，12actionable全执行，BFF重编译/NO-SOURCE。XML新24在09:54:51UTC、旧10在09:55:16UTC。红XML后覆盖，输出/状态地图§3.9保留阶段。

## 5. 边界/剩余

无schema/dependency/lockfile/认证/权限/保留策略改动。30天/90天/取消/审计/version/管理员fallback/事务outbox保持。真实browser/HTTP/TLS/proxy、多实例scheduler/crashrestore、竞争容量/饥饿及其他锁图未验，真实DBrace非生产stress。

只修测到的S3倒置。后续范围分别见[S4晋升](admin-promotion-deletion.md)/§3.10(255/176)、[S5墓碑](admin-tombstone-views.md)/§3.11(273/176)、[S6恢复](recovery-credential-entropy.md)/§3.12(314/176)、[D1Security](spring-security-consent-patch.md)/§3.13(323/176)、[A1/A2Servlet](servlet-path-security.md)/§3.14(最新419/176)。上方227/176保留S3。caller已有不同user/request锁仍须自守顺序；完整扫描/B1持久消费版本/B2时序/productionproxyTLSbrowser独立。未提交/托管发布/部署。
