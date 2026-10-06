# 恢复 epoch 屏障流程

- [English](../../operations/restore-epoch-barrier.md)／[ADR-0010](../adr/0010-executable-restore-epoch-barrier.md)／[代次契约](../implementation/service-access-epoch-contract.md)。

这是仅运维使用的命令，不是HTTP端点。运行时必须停止SSO Web和会写恢复库的调度任务。

## 流程

1. 代理摘流量，停止SSO/BFF writer，分离记录backup/key保管。
2. 将PostgreSQL快照恢复到隔离target，核验PG/schema版本及连接身份。
3. 查询并记录`system_security_state.security_epoch`。
4. 选择精确JAR和SHA-256，不使用目录wildcard或其他交付。
5. 执行：

```powershell
.\scripts\rotate-security-epoch-after-restore.ps1 `
  -Profile production `
  -JarPath $ServerJar `
  -ExpectedSha256 $ServerJarSha256 `
  -ExpectedEpoch $RestoredEpoch `
  -Reason "POSTGRES_RESTORE_2026-10-06"
```

脚本强制non-web/关闭scheduling；epoch不符/缺失、无console、Web模式或调度开启均拒绝。成功只输出old/new epoch和时间。

6. 核验新epoch不同；active SSO/HTTP Session、Authorization、未撤销RT history为0；short challenge/rate/CAPTCHA/recovery/idempotency为0；pending outbox可保留但claim必须释放。users/credentials/clients/consent/audit/deletion事实按restore review保留。
7. 独立更换持久签名key/kid并更新BFF JWKS/cache策略，不要用旧key开放流量。
8. 每个依赖方清本地Session/qualification，fresh query建立当前epoch屏障，拒旧响应，不按字符串/UUID/时间挑epoch。
9. 开SSO scheduling，验readiness/Discovery/JWKS；启动BFF，做新登录、旧Token刷新拒绝、当前/全局退出和outbox投递后再开代理。

## 回滚/限制

屏障失败保持摘流量，修复或换新target，不能用旧epoch重复执行。命令只保证SSO库事务，不能原子改变BFF外库/cache/key分发/业务系统。2026-10-06隔离演练使用两PG库、真实pg_dump/restore、两持久PKCS#12、真实PowerShell/WindowsPTY，验证旧cookie/code/AT/RT拒绝、新key/kid、新epoch低counter、UUID/password/admin/consent保留、旧Session/Auth/RT/short state清理、outbox保留释放、fresh login、generation屏障fixture和global logout；非production backup/key/proxy/真实consumer cache。
