# 备份与恢复

[目录](README.md) · [English](../en/recovery.md)

确定 RPO/RTO、备份频率、保留期、加密方式、异机位置和失败收件人。同机 dump 或数据库卷不是异机备份。数据库、签名密钥/pepper/配置及密码/恢复码分别保护；参考 BFF 和消费方存储独立备份。

## 创建一次备份

在包根目录使用 Bash 和[部署](deployment.md)中的**源实例** `dc` 函数，预先准备受保护的备份目录并停止写入进程。排空主机入口后执行：

```sh
set -euo pipefail
set -o noclobber
umask 077
BACKUP_DIR='/var/backups/las-sso'
test -d "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/las-sso-$(date -u +%Y%m%dT%H%M%SZ).dump"
dc stop sso
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_backup_password)" exec pg_dump -h 127.0.0.1 -U las_sso_backup -d las_sso --format=custom' > "$BACKUP"
test -s "$BACKUP"
dc exec -T postgres pg_restore --list < "$BACKUP" > "$BACKUP.list"
sha256sum "$BACKUP"
```

dump/list 均退出 0 才继续，失败或截断输出不得替代有效备份。Bash 传递二进制，PowerShell 5.1 文本管道不适用。加密并异机传输，核验远端摘要及解密权限；源实例恢复 readiness 后再开放。配置实际调度、保留和失败通知；列出归档不等于验证还原。

## 独立空目标还原

使用独立 restore.env、项目名、数据库卷和目标数据库密码。保留受支持的原 pepper，准备**新**签名密钥对和 kid。只初始化 PostgreSQL 角色，不预建应用表；保留源实例和备份。改用目标实例的 `dc` 函数执行：

```sh
set -euo pipefail
dc() { docker compose --env-file /etc/las-sso/restore.env -f "$PWD/deploy/docker/compose.host-nginx.yml" "$@"; }
python3 scripts/verify_docker_deployment.py --env-file /etc/las-sso/restore.env --compose deploy/docker/compose.host-nginx.yml --topology host-nginx
dc up -d --wait --wait-timeout 120 postgres
RESTORED_DUMP='/var/backups/las-sso/selected-restored.dump'
test -s "$RESTORED_DUMP"
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_migrator_password)" exec pg_restore -h 127.0.0.1 -U las_sso_migrator -d las_sso --no-owner --no-acl --exit-on-error --single-transaction' < "$RESTORED_DUMP"
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
```

任一步失败即停止。核验 Flyway/checksum、migrator 对象所有权、保留的 UUID/管理员/凭据/client/审计/注销事实，以及版本和扩展兼容性。旧快照不能重建备份后改密、用码或注销变化，应记录数据损失影响。

## Web前安全屏障

受控DB会话查询**目标**：

```sql
SELECT security_epoch, updated_at, change_reason FROM system_security_state WHERE id=1;
```

使用实际查得的 expected epoch 和同一精确镜像，关闭 Web/jobs 及并发写入，在真实交互终端执行：

```sh
dc --profile maintenance run --rm --no-TTY=false maintenance rotate-security-epoch-after-restore \
  --expected-epoch='<restored-epoch>' --reason=POSTGRES_RESTORE_APPROVED
```

输入 `ROTATE-EPOCH-AFTER-RESTORE`。退出码 0 表示新 epoch 和安全重置已提交，然后查询：

```sql
SELECT security_epoch FROM system_security_state WHERE id=1;
SELECT count(*) FROM user_sessions WHERE status='ACTIVE';
SELECT count(*) FROM spring_session;
SELECT count(*) FROM oauth2_authorization;
SELECT count(*) FROM oauth2_refresh_token_history WHERE status<>'REVOKED';
SELECT count(*) FROM oidc_password_challenges;
```

预期 epoch 已改变，后五项计数为 0。永久事实和审计保留；pending outbox 可保留，旧投递占用已清除。无交互终端、取消或 expected 不符必须失败。主机 JAR 使用包内 rotate-security-epoch-after-restore.ps1、production/server-only 配置及精确 JarPath/ExpectedSha256。

## 验证、协调与切换

1. 安装新签名密钥对和 kid。消费方停用旧 kid，结束旧本地会话/密码证据/缓存，在新 generation 下对账并拒绝旧在途回复；不排序不透明 epoch。
2. 启动目标 Web，核验 readiness/discovery/新 JWKS、新登录/UserInfo/刷新/退出、旧 Cookie/code/AT/RT 拒绝、待通知/注销任务及实际消费方。
3. 实测数据损失边界和恢复时间，对照 RPO/RTO；负责人确认后切换流量，明确保留源实例及备份。

提交前失败时保持入口关闭。epoch 提交后修复外围配置并保留新 epoch；再次还原需明确目标和当前值。只清理声明为可丢弃的项目；更换二进制不能跨不兼容 schema 或恢复后的安全状态回退。
