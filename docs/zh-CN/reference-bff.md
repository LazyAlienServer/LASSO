# 参考BFF及可选队列

[目录](README.md) · [English](../en/reference-bff.md) · [接入](integration.md)

源码 local SSO 启动后运行 `:las-sso-reference-bff:bootRun`。发行包部署需独立登记机密 client、提供外部 HTTPS 配置，并将回环端口 8088 放在可信代理后：

```sh
java -jar examples/reference-bff/las-sso-reference-bff-{{VERSION}}.jar --spring.profiles.active=https --spring.config.additional-location=file:/etc/las-reference/bff.properties
```

默认链或application-security演示自有组合；初期登录/index/replay单JVM，restart废业务登录。端点/规则见[接入](integration.md)。

## durable-revocation profile

选定才用https,durable-revocation。模板config/reference-bff/durable-revocation.properties.example供应独立PG、32byte AES/base64及key ID。样例迁移reference V1/V2，不操作SSO身份DB。AES私密生成/保存，例如OpenSSL随机32bytes编码base64，不用signing/TLS替代。

已提交的加密作业可跨 worker 重启、租约和重试恢复；八次失败后成为死信。完成回执默认保留 30 天，每小时清理一批、最多 100 项；pending/dead/leased 不自动删除。提交前的不确定性由消费方负责；接受作业不等于远端完成或持久登录。

## 离线检查与恢复

停止所有生产者和 worker，包括旧进程。使用受保护的维护配置副本，设置 worker-enabled=false，核对当前密钥与 registry；只查有界元信息，不查询密文、nonce 或 Token：

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout='10s';
SET LOCAL lock_timeout='500ms';
SELECT id,registration_id,key_id,replay_count,attempt_count,available_at,lease_until,completed_at,dead_lettered_at,last_error_code
FROM reference_revocation.tasks ORDER BY created_at,id LIMIT 100;
SELECT count(*) AS incomplete FROM reference_revocation.tasks WHERE completed_at IS NULL;
SELECT key_id,changed_at FROM reference_revocation.key_state WHERE id=1;
SELECT occurred_at,action,task_id,actor,reason,replay_count FROM reference_revocation.operator_audit ORDER BY occurred_at DESC,id LIMIT 100;
COMMIT;
```

包根真实PowerShell终端：

```powershell
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.'reference-bff'.path
$h = $m.artifacts.'reference-bff'.sha256
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-reference/queue-maintenance.properties'
./scripts/reference-revocation-maintenance.ps1 -Command replay -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'receiver repaired' -TaskId $TaskId -ExpectedReplayCount $ReplayCount
```

1. 修复实际 TLS/endpoint/client/key 故障，填写精确 task、当前代次/key/actor 并确认 YES。退出码 0 表示递增 replay_count、开始新重试轮次并记录审计。stale/pending/completed 拒绝不得通过 SQL 绕过。
2. 恢复适用生产者和 worker，观察实际投递及回执。八次失败后保留死信调查，不无限重放；回执失败可能导致幂等重投，不保证 exactly-once。
3. 使用相同制品/key/actor/reason/YES 执行 `-Command purge-completed`，最多删除一批符合条件的完成回执；pending/dead 保留，删除回执不证明 family 已清理。
4. 轮换前停所有进程并**复查 incomplete=0**，分别备份队列和旧密钥。私密提供 `reference.revocation.next-key-base64` 和未使用的新 ID，执行 `-Command rotate-key ... -NextKeyId $NextKeyId` 并确认 YES。退出码 0 后安装新 active 配置、移除 next 输入；启动新进程验证 registry 和任务接受，旧 key 进程被拒绝。

保留操作者审计和备份读取能力。registry 提交后失败按新值修复，不回改；维护密钥仅来自外部配置，维护过程不发 Token 网络请求或 OIDC discovery。
