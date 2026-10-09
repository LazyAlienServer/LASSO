# 参考 BFF：持久撤销队列维护

[目录](README.md) · [English](../en/reference-revocation.md) · [参考应用](../../examples/reference-bff/README.md)

**仅适用启用 durable-revocation 的参考BFF。** 它不是SSO outbox，也不保存BFF登录会话。

| 项目 | 规则 |
| --- | --- |
| 数据库 | 独立PG、`reference_revocation`、参考V1–V2 |
| key | 独立32byte AES材料，受保护配置 |
| 恢复保证 | 仅已commit task；提交前需消费方intent/OP补偿 |
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
