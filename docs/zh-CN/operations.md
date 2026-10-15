# 运行与升级

[目录](README.md) · [English](../en/operations.md) · [恢复](recovery.md)

包根统一使用默认helper：

```sh
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.host-nginx.yml" "$@"; }
```

离线写入前关闭并排空本站 Nginx 入口，其他站点无需停止。保持 PostgreSQL 健康，停止 SSO 和其他写入进程；记录版本、镜像、Flyway、epoch、kid 及已验证备份，不记录秘密值。

## 检查、重启、停止/恢复

```sh
dc ps
dc exec -T sso curl --fail --silent --show-error --max-time 45 http://127.0.0.1:9091/actuator/health/readiness
dc restart sso
```

重启后等待 readiness。env、镜像或端口更改后使用 `dc up -d --no-deps --force-recreate sso`；restart 不加载这些新配置。保留密钥、pepper 和秘密，不重复 bootstrap。

计划停机：排空入口后 `dc stop sso`，整实例停机时再 `dc stop postgres`。恢复时先等待 PostgreSQL 就绪，再启动 SSO；核验 HTTPS、登录和指标后开放。`down --volumes` 仅用于明确可丢弃的测试实例。

## 离线管理员reset

1. 核对唯一管理员、授权维护窗口和恢复材料，停止所有写入进程。
2. 真实终端执行：

```sh
dc stop sso
dc --profile maintenance run --rm --no-TTY=false maintenance admin-reset-password
```

3. 核对显示的用户名和 UUID，输入 YES，再两次隐藏输入新密码；取消或非零退出码即停止。
4. 退出码 0 后启动 SSO 并检查 readiness。新密码为 **ACTIVE**，不是临时激活密码。验证新登录和旧会话拒绝，按用户指南 U07 重新生成并保存恢复码。

输出丢失时先查状态和审计；已提交重置后修复外围故障，不恢复旧凭据。管理员不能使用普通账号的 Web 人工恢复凭据。

Host JAR/PowerShell使用server-only外配置及精确制品：

```powershell
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
./scripts/reset-admin-password.ps1 -Profile production -JarPath (Join-Path $PWD $m.artifacts.server.path) -ExpectedSha256 $m.artifacts.server.sha256
```

## 升级

1. 核验新包、说明和扫描结果，演练受支持的已有数据升级；保留旧包、镜像、配置及 schema 兼容结论。
2. 分别备份数据库及外部配置/密钥，排空主机入口，停止 SSO 和其他写入进程。
3. 在**新包根目录**沿用原 env、项目名、数据库卷和密钥，设置新 SSO 镜像的精确 ID：

```sh
set -euo pipefail
python3 scripts/verify_docker_deployment.py --env-file /etc/las-sso/deployment.env --compose deploy/docker/compose.host-nginx.yml --topology host-nginx
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
dc up -d --wait --wait-timeout 120 sso
dc exec -T sso curl --fail --silent --show-error --max-time 45 http://127.0.0.1:9091/actuator/health/readiness
```

4. 核验 HTTPS、管理员、消费方新登录/刷新/退出、指标 401/200 及通知；保存版本和结果后逐步开放。

迁移只前向执行，V16 普通索引需演练耗时和磁盘空间。旧二进制回退必须已验证兼容新 schema；否则前向修复，或执行带 epoch/密钥屏障的授权恢复。PostgreSQL 大版本升级使用 pg_upgrade 或 dump/restore，不能只换镜像。

## TLS续期

安装已核验匹配的证书和私钥，配置主机 Nginx 读取权限。`nginx -t` 通过后才执行 `systemctl reload nginx` 或等效操作，新连接应看到新证书；核验续期和失败通知。TLS 续期不更换 OIDC 签名密钥。

## 历史与日检

```sh
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_backup_password)" exec psql -X -h 127.0.0.1 -U las_sso_backup -d las_sso -v ON_ERROR_STOP=1' < scripts/history-status.sql
```

报告只读，语句超时 10 秒、锁等待 500 毫秒，不输出 Token 值。覆盖保留期时用 `-v *_days=...` 传入一致参数。比较候选数、大小、关联数量趋势及新鲜清理指标；候选不表示一定会被删除。

清理默认每 5 分钟、每批 100 项；各阶段事务有界，繁忙记录使用 SKIP LOCKED，超大 family 受保护。有效 hint/RT、未完成或死信作业及永久审计/身份事实保留。终态会话/凭据至少保留 90 天，过期授权、孤儿 REVOKED RT 和适用的已完成 outbox 至少 30 天，不低于安全下限。

可清理积压时检查任务、锁、错误和吞吐量；有效或永久数据增长由容量/保留负责人处理。DELETE 不自动缩小文件，autovacuum 复用空间，FULL/reindex 需独立维护窗口。每日检查磁盘、连接池、时钟、证书、备份和通知，指标见[监控](monitoring.md)。
