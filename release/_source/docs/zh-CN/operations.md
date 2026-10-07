# 运维

[目录](README.md) · [English](../en/operations.md)

## 日常检查

观察含数据库的 readiness、私有采集/snapshot 新鲜度、登录/管理员失败、refresh/reuse、退出积压/年龄/死信、连接池/存储和证书有效期。通过 `/admin/audit` 检查审计/安全事件，按时间/requestId/action 关联，避免 Token/Cookie/密码数据。CA 续期、备份有效性、真实通知接收与升级响应由运维负责。见[监控](monitoring.md)。

保留发行清单：版本、核验 SHA-256、源码 commit、不含值的配置修订、JDK/PostgreSQL/代理版本及迁移历史。重启不重新生成秘密。SSO 暂时不可用不是账号封禁，消费方应区分拒绝与临时故障。

## 维护命令

从包根执行，提供同一受保护数据库/密钥环境及仅服务端外部配置。停止 Web 写入者、关闭公网入口，先验证包，再读取精确值：

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
$m = Get-Content -LiteralPath './provenance/release-manifest.json' -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.server.path
$h = $m.artifacts.server.sha256
./scripts/reset-admin-password.ps1 -Profile production -JarPath $j -ExpectedSha256 $h
```

Reset 隐藏读取密码、针对当前管理员，并非任意用户重置 API。Bootstrap 的 `-Username`/`-UserId` 见[部署](deployment.md)。恢复另需 ExpectedEpoch/原因并明确关闭 scheduling，见[恢复](backup-and-recovery.md)。包内维护在 Java 启动前拒绝缺少/错误摘要，不使用通配符或“最新”程序。私密保管恢复输出并审计操作者/原因/结果。

## 升级与回退

1. 核验独立候选版本及其源码/测试/扫描覆盖；在隔离副本演练空库与旧版本升级。备份数据库并独立记录密钥/pepper/配置保管。
2. 排空入口、停止写入者/worker，记录 epoch 和 Flyway，安装新不可变目录。外部配置独立版本管理且不暴露值。启动单个 SSO，验证迁移/readiness/TLS/浏览器/消费方刷新退出，再启消费方、逐步开放。
3. 记录部署时间、摘要与验收。失败时继续关闭入口。旧二进制不能未经兼容性验证直接操作新 schema。恢复旧快照必须执行完整 epoch/新签名密钥/消费方屏障，单纯回退文件不够。

关注 V15 hint/history 增长与 authorization 删除、audit/outbox/security 保留和数据库膨胀。没有交付独立 history TTL/压缩器或完整容量策略，不按猜测删除数据。并发/outbox 应受控处理，不能随意改 processed 标志。

## 参考持久撤销

默认BFF重启丢登录。可选独立PostgreSQL参考V1–V2/AES32byte队列仅恢复已提交task，pre-submit仍需消费方intent/OP补偿。V2的reference-revocation-maintenance.ps1要求精确参考JAR/hash/currentkey/actor/reason/真实YES；replay精确deadtask/replay_count并核密文审计新8次campaign。完成回执默认30天/batch100每小时清，pending/dead/lease不删。换key拒未完成/same或复用ID/material，registry核key并阻旧进程；先停全部producer/worker及旧V1程序，维护小上下文无OIDC/Web/worker。新key以受控reference.revocation.next-key-base64供给非参数，commit后换active配置。DB/key独立备份，无持久登录/multikey/跨存储原子。

## 故障处置

包根参考队列操作（SPRING_CONFIG_ADDITIONAL_LOCATION选独立参考queue配置，不能SSO配置）：

```powershell
$m = Get-Content -LiteralPath './provenance/release-manifest.json' -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.'reference-bff'.path
$h = $m.artifacts.'reference-bff'.sha256
./scripts/reference-revocation-maintenance.ps1 -Command replay -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'receiver repaired' -TaskId $TaskId -ExpectedReplayCount $ReplayCount
./scripts/reference-revocation-maintenance.ps1 -Command purge-completed -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'completed receipt retention'
./scripts/reference-revocation-maintenance.ps1 -Command rotate-key -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'queue drained key rotation' -NextKeyId $NextKeyId
```

只选所需命令，换key需停producer/worker且未完成task为空。reference_revocation.tasks仅查task ID/key_id/replay_count/attempt_count/时间/类别，不查询ciphertext/Token。replay核预期代次/认证载荷/审计后才reset预算，worker仍核当前endpoint/client/issuer。未解决dead保留调查，必要时OP补偿，purge不解决dead。rotate时当前key保持，新材料仅外部临时供给，成功后装新active配置并去next输入。审计保留，定义权限保管/增长监控；旧V1程序绕过V2门禁，必须停。

凭据/密钥泄露时按范围关闭入口，通过支持的管理操作撤销会话/授权/客户端，在验证过的连续性方案下替换秘密，保留脱敏审计。签名密钥泄露/恢复协调全部消费方和旧 kid 缓存屏障。DB down 时 readiness503、liveness200，应恢复连接而非形成重启风暴。疑似攻击查看固定低基数指标和风险/审计结果，不通过关闭 CSRF/限流消除症状。人工恢复联系现有 QQ 群；仅确定联系渠道不代表已核实申请人或完成整套人工恢复流程。
