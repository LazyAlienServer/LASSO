# 备份与恢复

[目录](README.md) · [English](../en/backup-and-recovery.md)

## 备份保管

运维明确 RPO/RTO、保留、加密、恢复授权与实测频率。包不交付生产定时任务或备份。事务一致备份 PostgreSQL（演练可用 pg_dump custom format，生产 PITR/WAL 另作容量策略），记录 DB/schema/Flyway/版本/epoch/时间并验证独立恢复。数据库密码不放参数，使用受保护凭据。签名密钥、pepper、配置/采集/客户端秘密独立加密保管并准备恢复访问；仅有 dump 不代表可恢复认证状态。

消费方 DB/队列独立备份。参考已提交任务需匹配 AES key 与 endpoint/client 配置；恢复队列可能重复已完成撤销、漏掉较新任务。默认业务登录在进程重启后丢失。旧快照不能重建备份后改密/凭据使用/注销事实，开放前需审查。

## 旧快照恢复必需屏障

1. 排空公网入口，停止 SSO、定时任务和消费方写入者。仅恢复到隔离且明确标识的目标，保留原备份/保管记录；审查 PostgreSQL/schema兼容、当前管理员、永久 UUID/凭据/客户端/审计/注销事实。
2. 受控 DB 会话查询并记录恢复代次：

```sql
SELECT security_epoch, updated_at, change_reason FROM system_security_state WHERE id = 1;
```

3. 核验精确包/JAR/hash，供应目标 DB/仅服务端配置与外部秘密，无 monitoring/web profile。包根交互 PowerShell 执行：

```powershell
$m = Get-Content -LiteralPath './provenance/release-manifest.json' -Raw | ConvertFrom-Json
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
./scripts/rotate-security-epoch-after-restore.ps1 -Profile production -JarPath (Join-Path $PWD $m.artifacts.server.path) -ExpectedSha256 $m.artifacts.server.sha256 -ExpectedEpoch '<restored-epoch>' -Reason 'POSTGRES_RESTORE_APPROVED'
```

脚本强制 non-web/scheduling=false；命令拒绝 epoch 不匹配/空值、非交互、Web模式/活跃任务。操作者确认目标后，SSO 事务换 epoch/撤销瞬态安全状态。成功提交后不能继续以旧 epoch 重试。

4. 新 epoch 必须不同，active user_sessions、spring_session、oauth2_authorization、未撤销 RT history 与重认证/瞬态挑战清空。pending outbox 可保留但旧 claim 需重置。检查：

```sql
SELECT security_epoch FROM system_security_state WHERE id = 1;
SELECT count(*) FROM user_sessions WHERE status = 'ACTIVE';
SELECT count(*) FROM spring_session;
SELECT count(*) FROM oauth2_authorization;
SELECT count(*) FROM oauth2_refresh_token_history WHERE status <> 'REVOKED';
SELECT count(*) FROM oidc_password_challenges;
```

5. 单独安装**新持久签名密钥/kid**，在所有消费方 JWKS/校验/缓存边界退休旧 kid。不能使用恢复出的旧密钥开放。V1 无批准的同 key 恢复方案；epoch 事务不能原子更新外部 key/消费方存储。
6. 消费方清本地会话/资格/密码证据，经 fresh 权威对账建立当前本地 generation，再拒迟到旧 generation 响应。不能按 epoch/version/time 字典顺序选代；较低恢复 version 仅在新 epoch 内有效。真实消费方须实现屏障，不能无条件接受返回代次。
7. 启 SSO/任务，验 DB readiness/Discovery/新 JWKS/新登录/UserInfo；启动消费方，验旧 Cookie/code/AT/RT 拒绝，新 refresh/当前/全部退出/Back-channel 成功，审查 pending outbox/到期注销行为。消费方签收后逐步开放。

## 失败与覆盖

事务失败时继续关闭入口，检查目标/epoch/DB/权限后再试。后续消费方/key失败保留新 epoch 协调修复，不单独回退计数器。二次恢复使用新目标或明确当前 epoch。既往隔离 PostgreSQL/TLS/key/PTY 演练仅历史证据，不是本环境灾备认证。保存脱敏签收结果并演练实际备份/密钥保管及消费方屏障。
