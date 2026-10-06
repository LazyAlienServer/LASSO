# 开放流量前执行恢复 epoch 屏障

状态：2026-10-06已实现并通过隔离PostgreSQL/真实`pg_dump`/`pg_restore`/持久key演练；生产备份、key保管与依赖方屏障仍独立。

[English](../../adr/0010-executable-restore-epoch-barrier.md)。

## 背景

ADR-0007要求旧PostgreSQL恢复后、开放流量前生成新的不透明`security_epoch`。此前只有runbook和restore-shape fixture，没有可执行维护屏障，操作员可能对错误恢复代次执行，或保留Spring Session、Authorization、RT family、短期恢复状态和旧outbox领取状态。

## 决策

新增`SecurityEpochRecoveryService`及非Web命令`rotate-security-epoch-after-restore`，要求：

- production/restore配置与`spring.main.web-application-type=none`；
- 显式关闭scheduling；
- 从恢复库读取的精确`--expected-epoch`；
- 有界`--reason`；
- 交互输入`ROTATE-EPOCH-AFTER-RESTORE`确认。

同一事务内锁定并匹配旧epoch后：生成新epoch；撤销active user_sessions/删除JDBC Spring Session；删除Authorization并由既有trigger撤销RT history；显式撤销剩余family；删除未完成OIDC challenge及限流/CAPTCHA/recovery/idempotency短状态；保留clients/users/credentials/audit/consent/pending outbox；释放旧outbox claim；记录old/new epoch安全事件。

epoch不符、缺失/blank或事务失败全部回滚；旧epoch重复执行拒绝。命令不生成/轮换签名key，运维需另行更换key/kid并完成验证器/cache屏障后开放流量。

## 影响

恢复后全部SSO登录和Authorization有意失效；不恢复快照之后的账号事实，也不知道依赖方外部撤销库。保留consent/client便于受控重新登录，保留outbox避免静默丢失退出/注销投递，可能重复投递，receiver须幂等。无新migration/HTTP端点。依赖方仍须执行ADR-0007 fresh-generation屏障；production凭据、备份/key/proxy drain/时序仍运维验收。
