# JAR / systemd 部署

[目录](README.md) · [English](../en/jar-deployment.md) · [Docker 首发方案](docker-deployment.md)

**适用：** 已选择主机 Java 进程的环境。应用需要 JDK21、PostgreSQL、独立低权限账号和 TLS Nginx。

## 1. 验证与配置

**位置：包根。**

```text
python scripts/verify-package.py
```

复制 `config/server/server.properties.example` 到受保护外部位置。

| 外部值 | 要求 |
| --- | --- |
| `LAS_SSO_DB_URL/USER/PASSWORD` | 实际 PG 目标和专用角色 |
| `LAS_SSO_ISSUER` | 唯一 HTTPS 地址、路径匹配 servlet context |
| `LAS_SSO_SIGNING_KEY_*` | 持久 RSA≥2048 PKCS12、同密码、alias/kid |
| 三 pepper / CAPTCHA 秘密 | 独立、至少32 UTF-8字节 |
| DB/key 密码 | 独立、至少12字节；长度不等熵 |

模板详情：[配置索引](../../config/README.md)。主机 app/management 保持私有 loopback；容器模板不能互换。

## 2. 离线迁移

先停 Web 写入者；配置迁移角色、同库/key/secret，仅使用 server 配置。

```text
java -jar application/las-sso-server-{{VERSION}}.jar --spring.profiles.active=production --spring.config.additional-location=file:/etc/las-sso/server.properties --spring.main.web-application-type=none --las.sso.maintenance.command=database-migrate --las.sso.scheduling.enabled=false --spring.session.jdbc.cleanup-cron=- --las.sso.monitoring.enabled=false
```

**预期：** 内嵌 V1–V16 全部完成，exit0，无 HTTP / jobs。保留 Flyway validation / clean protection。

## 3. bootstrap

真实 PowerShell 终端、包根：

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
$m = Get-Content ./provenance/release-manifest.json -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.server.path
./scripts/bootstrap-admin.ps1 -Username InitialAdmin -Profile production -JarPath $j -ExpectedSha256 $m.artifacts.server.sha256
```

**预期：** 两次隐藏输入成功，唯一管理员临时密码；首登强改。现有用户用 `-UserId`，不同时传用户名。

Linux 可直接用同 JAR：将第2步的 `database-migrate` 替换为 `admin-bootstrap`，保留其他非 Web / 关闭 jobs 参数，并添加 `--username=InitialAdmin`。

## 4. 启 Web / Nginx

切到已审查的 runtime 角色，再运行：

```text
java -jar application/las-sso-server-{{VERSION}}.jar --spring.profiles.active=production --spring.config.additional-location=file:/etc/las-sso/server.properties
```

| 模板 | 操作 | 预期 |
| --- | --- | --- |
| `deploy/systemd/las-sso.service` | 调整路径/User/环境，`systemd-analyze verify` 后安装 | 低权限、只读程序 |
| `deploy/nginx/sso-site.conf.example` | 正式域名/证书，安装 forwarding include，`nginx -t` | canonical HTTPS、未知Host拒、无actuator代理 |
| 可选 monitoring | `production,monitoring` 加独立配置/凭据 | health迁私有9091，见[监控](monitoring.md) |

## 5. 验结果

1. 私有 readiness `UP`；未启 monitoring 时9000，启用后9091。
2. 正式 HTTPS Discovery / JWKS、Secure Cookie。
3. 管理员首登改密、重新登录和恢复码保管。
4. 执行[开放前验收](acceptance.md)。

失败入口：[排障](troubleshooting.md)。systemd 在当前 Windows 构建机未执行，目标 Linux 必须验证。
