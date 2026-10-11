# 参考 BFF：持久撤销队列维护

[目录](README.md) · [English](../en/reference-revocation.md) · [参考应用](../../examples/reference-bff/README.md)

**仅适用启用 durable-revocation 的参考BFF。** 它不是SSO outbox，也不保存BFF登录会话。

| 项目 | 规则 |
| --- | --- |
| 数据库 | 独立PG、`reference_revocation`、参考V1–V2 |
| key | 独立32byte AES材料，受保护配置 |
| 恢复保证 | 仅已commit task；提交前不确定性及选定恢复策略由消费方负责 |
| 默认登录 | 单JVM重启丢登录 |
| 完成保留 | 默认30天，batch100/hour |
| pending/dead/lease | 不自动purge |

## 1. 准备维护

1. 停全部producer/worker，包含旧V1二进制。
2. 准备独立参考queue配置/key，不使用SSO server配置。
3. 真实PowerShell终端、包根读取精确参考JAR/hash：

```powershell
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.'reference-bff'.path
$h = $m.artifacts.'reference-bff'.sha256
```

`SPRING_CONFIG_ADDITIONAL_LOCATION`指向参考queue配置；维护小上下文无Web/OIDC discovery/worker。

## 2. 精确死信恢复

先核TaskId、当前replay_count、key ID、操作者和非秘密原因引用。

```powershell
./scripts/reference-revocation-maintenance.ps1 -Command replay -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'receiver repaired' -TaskId $TaskId -ExpectedReplayCount $ReplayCount
```

**预期：** YES确认后，代次/认证密文/审计校验提交，新8次campaign。Worker仍核当前endpoint/client/issuer。

## 3. 清完成回执

```powershell
./scripts/reference-revocation-maintenance.ps1 -Command purge-completed -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'completed receipt retention'
```

**预期：** 只删eligible completed；purge不解决dead。

## 4. 排空换key

| 顺序 | 操作 |
| --- | --- |
| 1 | 停全部进程，核未完成task为空 |
| 2 | 外部临时供应 `reference.revocation.next-key-base64` |
| 3 | 执行rotate-key、真实YES |
| 4 | 提交成功后安装新active配置，删除next输入 |
| 5 | 新进程启动，核registry/旧进程fencing |

```powershell
./scripts/reference-revocation-maintenance.ps1 -Command rotate-key -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'queue drained key rotation' -NextKeyId $NextKeyId
```

拒未完成任务、相同/复用ID或材料。无多keyring/跨存储原子登录退出。

## 故障证据

只查task ID、key_id、replay/attempt count、时间、错误类别；不取ciphertext/Token。

未修复dead保留调查，可需OP补偿；DB/key独立备份，操作审计保留并监控增长。

## R01 核队列目标并准备维护环境

**位置：包根、真实PowerShell终端。** 只操作启用durable-revocation的参考BFF库；las_sso身份库和SSO outbox不是目标。

1. 确认独立queue DB/backup/AES当前key及key ID，暂停全部producer/worker（包括旧程序）。停止Web只限参考BFF，不自动停止SSO或LASLOG。
2. 将`config/reference-bff/durable-revocation.properties.example`复制到受限外部位置，填写该库/凭据/key的外部注入，并令维护配置worker-enabled=false。不修改包内模板。
3. 在本终端设置外部配置地址，读取精确参考JAR/hash；若主机路径不同替换下方非秘密路径。

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-reference/queue-maintenance.properties'
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.'reference-bff'.path
$h = $m.artifacts.'reference-bff'.sha256
$CurrentKeyId = '<current-key-id>'
$Operator = '<operator-id>'
```

4. 受控只读DB会话检查有界task/key/audit元信息及未完成总数，下方不查Token/nonce/ciphertext；确认key配置及存储registry一致。前100行仅为样本，不代表队列全集。

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '500ms';
SELECT id, registration_id, key_id, replay_count, attempt_count,
       available_at, lease_until, completed_at, dead_lettered_at, last_error_code
FROM reference_revocation.tasks ORDER BY created_at, id LIMIT 100;
SELECT count(*) AS incomplete_tasks FROM reference_revocation.tasks WHERE completed_at IS NULL;
SELECT key_id, changed_at FROM reference_revocation.key_state WHERE id=1;
SELECT occurred_at, action, task_id, actor, reason, replay_count
FROM reference_revocation.operator_audit ORDER BY occurred_at DESC, id LIMIT 100;
COMMIT;
```

**预期：** 精确目标、当前key可认证、无并发writer。**失败：** 不匹配/缺key停下来，不能用SSO signing key或新随机AES顶替旧任务key。

## R02 修复原因后重放精确dead task

1. R01定位死信，记录TaskId/current replay_count/error；先修endpoint/TLS/client登记/DB及受支持密钥问题，不能仅重置次数。
2. 填`$TaskId`为该UUID、`$ReplayCount`为当前整数；运行§2命令，核提示目标及ExpectedKeyId，真实输入YES。
3. exit0后查询该task：replay_count递增、新campaign接受、审计存在。completed/pending/旧generation拒绝不强行改库。
4. 重启所需producer/worker，观察lease/attempt和完成；HTTP200后的完成回执提交失败可能幂等重投，不声称exactly once或未知family已清理。
5. 达到8次仍失败保dead/调查，按受支持scope人工处置，不无限循环重放或导出解密Token。

**预期：** 原有task可续作、结果如实记录。**失败：** 网络/密文/配置未修保未完成；response/body秘密不附工单。

## R03 清完成回执

**预期：** 只删一批符合期限的完成回执，并保操作审计；未完成工作保留。

1. 核completed-retention/cleanup-batch-size、最近成功时间/eligible数量及backup/审计保管。
2. R01离线环境运行§3purge，核key/actor/reason，确认YES。
3. exit0后比较eligible completed及operator audit；单批可能0或最多配置batch，pending/dead/lease必须保留。
4. 恢复worker，核自动hourly清理及审计增长。删回执不证明存在的远端授权已撤销，也不解决死信。

**失败：** purge事务/审计失败保原状态；不能为“排空”而删除未完成任务。

## R04 排空后轮换AES key

**预期：** 排空队列的新active key ID及审计同事务提交；新进程接受新key工作，旧key producer拒绝。

1. 在线修完/replay适用任务，按R01停全部producer/worker，再核**全部completed_at为空的任务数量为0**（包括dead/lease）。停进程后必须复查，避免producer在排空检查后重新入队。
2. 私密生成独立32-byte AES材料及全新不复用NextKeyId，在受限临时配置提供next-key-base64，current key保持；backup保queue/registry及受控旧key。
3. 运行§4rotate命令，ExpectedKeyId当前、NextKeyId新；YES、exit0表示registry/audit同事务提交。
4. 提交后将正常参考进程配置改new active key/id，删除next输入，启动新进程核读取registry/接受与执行新的测试任务。旧key producer应被fence，不尝试重开旧程序。
5. 记录key ID/审计/time，密钥材料不记录；旧key保留/退休按backup恢复政策，不马上破坏旧backup可读性。

**失败：** KEY_DRAIN_REQUIRED修未完成task；同ID/相同材料/旧ID拒绝。若命令提交后新配置失败，按当前registry协调完成配置，不回改registry绕fencing。
