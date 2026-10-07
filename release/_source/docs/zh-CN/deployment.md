# 部署

[目录](README.md) · [English](../en/deployment.md)

## 1. 准备环境

使用 JDK21、PostgreSQL（验收基线为18.4）、私有应用/数据库网络与唯一 HTTPS Issuer。由运维确定正式域名、消费方精确回调/退出 URI、CA/续期、防火墙、存储/容量和故障联系人；预发布与生产使用独立数据库和客户端。包不自动创建这些资源。完整生产启动校验/无管理员维护模式仍待实现，因此人工检查和初始化成功前必须关闭入口。

在包根运行 `python scripts/verify-package.py`，读取 `provenance/release-manifest.json` 并保存可信的服务端摘要。将整个不可变版本目录安装至 `/opt/las-sso/{{VERSION}}`，创建低权限 `las-sso` 用户和受保护的 `/etc/las-sso/` 配置目录。运行用户不应能重写发行文件。

## 2. 数据库、秘密与签名密钥

创建专用 PostgreSQL 数据库/账号，按 Flyway V1–V15 准备迁移权限。开放入口前完成数据库认证/网络限制和备份。JDBC 示例为 `jdbc:postgresql://db.internal:5432/las_sso`；跨主机使用验证过的 TLS。身份数据库不能与业务应用或参考撤销队列共用。

将 `config/server/server.properties.example` 复制为 `/etc/las-sso/server.properties`，通过仅 root/服务可读的环境文件或秘密管理机制提供：

| 变量 | 含义 |
| --- | --- |
| `LAS_SSO_DB_URL`、`LAS_SSO_DB_USER`、`LAS_SSO_DB_PASSWORD` | 实际 JDBC 目标和凭据 |
| `LAS_SSO_ISSUER` | 精确唯一 HTTPS 地址；Discovery/客户端的协议、主机、端口、路径均一致 |
| `LAS_SSO_SIGNING_KEY_PATH`、`LAS_SSO_SIGNING_KEY_PASSWORD`、`LAS_SSO_SIGNING_KEY_ALIAS`、`LAS_SSO_SIGNING_KEY_ID` | 持久 PKCS12 RSA 密钥对，密钥库/私钥同密码，稳定非空 kid |
| `LAS_SSO_RECOVERY_PEPPER` | 独立高熵恢复哈希秘密 |
| `LAS_SSO_TRANSIENT_SECURITY_PEPPER` | 独立高熵瞬态/HMAC 秘密，也影响历史 hint 查询 |
| `LAS_SSO_CAPTCHA_HMAC_SECRET` | 独立高熵验证码秘密 |

使用可信秘密管理器/CSPRNG 生成秘密，重启保持原值并保护保管/备份。不能使用 local 值、示例客户端秘密或共用人类密码。可用 keytool 交互隐藏输入创建 PKCS12 RSA3072/RS256 密钥对（`-genkeypair -keyalg RSA -keysize 3072 -storetype PKCS12`）；路径、alias、有效期自行确定，私钥密码与库密码一致。公有 JWKS 不含私钥。应用加载单个配置密钥，不提供无缝多密钥轮换管理，须规划停机/缓存到期与[恢复屏障](backup-and-recovery.md)。

## 3. 入口关闭时初始化

离线维护使用 JDK21、同一数据库/密钥/秘密及明确外部配置。初始化前停止 Web 写入者。在包根 PowerShell 执行：

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
$manifest = Get-Content -LiteralPath './provenance/release-manifest.json' -Raw | ConvertFrom-Json
$jar = Join-Path $PWD $manifest.artifacts.server.path
$sha = $manifest.artifacts.server.sha256
./scripts/bootstrap-admin.ps1 -Username '<chosen-login-name>' -Profile production -JarPath $jar -ExpectedSha256 $sha
```

现有账号改用 `-UserId '<existing-UUID>'`，不同时传 Username。真实密码由 Java console 隐藏读取，不能作为参数/环境/历史记录保存；交互与一次性恢复输出应私密保管。包内 bootstrap/reset 默认 `production`，JAR 与预期摘要必填。`production` 是运维选用的名字，不是自动配置生产环境的特殊 profile。正式环境不启用 `local`/`test`。Linux 可安装 PowerShell，或用同样的 Java non-web 维护命令显式传 profile/config/JAR，并保留摘要及交互控制台检查，无须源码目录。

## 4. 启动与代理

包根手动启动：

```text
java -jar application/las-sso-server-{{VERSION}}.jar --spring.profiles.active=production --spring.config.additional-location=file:/etc/las-sso/server.properties
```

Linux 审查并安装 `deploy/systemd/las-sso.service`，调整 Java/安装/配置路径，保护环境文件；执行 `systemd-analyze verify`、`systemctl daemon-reload`、`systemctl start las-sso`。升级使用维护窗口，服务无须特权账号或可写程序目录。日志进入受控 journal/存储，不记录认证载荷。

审查 `deploy/nginx/sso-site.conf.example`，替换域名/证书，将 `deploy/nginx/las-forwarding.conf` 安装为 `/etc/nginx/snippets/las-sso-forwarding.conf`，reload 前执行 `nginx -t`。不可信调用者不能直连9000。代理清理其他转发元信息并重写唯一 host/proto/port/client address；保留 OIDC/API 路径，不剥离前缀。样例拒绝未知主机并隐藏 `/actuator/`，不代替实际 CDN/负载均衡信任链设计。`__Host-las_sso` 为 Secure/HttpOnly/Lax、HTTPS host-only Cookie，不设置 Domain。

## 5. 验证、登记客户端、开放

检查私有 readiness200/UP、liveness200、Flyway V1–V15、唯一 Discovery/JWKS 无私钥以及真实 TLS 浏览器 Cookie。启用 `monitoring` 后健康端点迁至私有管理端口，否则在应用端口。执行：

```text
python scripts/smoke.py --issuer https://auth.example.com --readiness-url http://127.0.0.1:9000/actuator/health/readiness
```

该工具检查协议/健康，不是登录或完整部署验收。开放前完成管理员登录、强制改密、精确客户端登记及全部[验收](acceptance.md)。在 `/admin/clients` 登记独立 Confidential Client，秘密一次性私密交付，再按[接入](external-integration.md)联调。

可选监控：提供独立哈希，使用 `production,monitoring` 与配置位置 `file:/etc/las-sso/server.properties,file:/etc/las-sso/monitoring.properties`。明确改写服务 ExecStart；维护进程仅用服务端配置/profile。见[监控](monitoring.md)。
