# 排障

[目录](README.md) · [English](../en/troubleshooting.md)

restart前记version/image、UTC、route、安全code/requestId及health。有界日志私密看，不分享raw认证/配置抓包。

```sh
dc ps
dc logs --no-color --tail 100 sso
dc logs --no-color --tail 100 postgres
```

主机Nginx另查。`Restarting (1)`表示SSO主进程退出，readiness curl不能诊断启动异常；PG healthy不证明SSO凭据/schema/admin正确。

## 启动

| 类别 | 处理 |
| --- | --- |
| PRODUCTION_PROFILE_REQUIRED/CONFLICT | production，不混local/test |
| PRODUCTION_ISSUER_* | canonical HTTPS host/port/path匹配servlet context |
| PRODUCTION_KEY_ID_INVALID | 1–128 ASCII字母/数字/点/下划线/横线，去示例标记/引号/空格，env改后recreate |
| Unable to load persistent signing key | PKCS12路径/读者/RSA/alias/store+private密码 |
| PRODUCTION_SECRET_* | 独立合法私密文件，无示例/未解析占位符 |
| PRODUCTION_MONITORING_CREDENTIAL_INVALID | `{bcrypt}`+cost10–14，无用户名/引号/空格，原密码仅collector |
| PRODUCTION_ADMIN_INITIALIZATION_REQUIRED | 新DB离线bootstrap或授权reset/临时密码过期 |
| PRODUCTION_SCHEMA_INCOMPATIBLE | 精确兼容binary/迁移/checksum，不repair摘要 |
| DB权限/认证 | runtime/migrator/grants，已有卷不重初始化 |
| INTERACTIVE_CONSOLE_REQUIRED | 真实TTY、--no-TTY=false，密码/epoch不用-T |

精确准备见[配置](configuration.md)/[部署](deployment.md)。env变更按up --force-recreate，不只restart；按当前异常处理，不沿上一故障猜类别。

## 请求与监控

| 现象 | 检查 |
| --- | --- |
| 外TLS/502 | hostNginx/site/cert/upstream/loopback及SSO readiness |
| readiness503/liveness200 | DB/pool，修连接不反复restart |
| 登录循环 | canonical host/HTTPS/SecureCookie/forwarding |
| 写403 | actor角色/session、近期密码、CSRF/同源/contenttype |
| CAPTCHA/429 | 风险/Retry-After，等候保限额 |
| 强改 | bootstrap临时密码走U03，人工恢复走U08 |
| callback不符 | trusted issuer/CA/精确URI/完整路径 |
| refresh invalid_grant | 废本地登录，不故意重试旧RT |
| provider暂503 | 不可用，非封禁/近期证据 |
| 旧ID hint logout400 | history/pepper/client URI，旧hint丢失不可补造 |
| BFF restart废登录 | 初期单JVM，queue不复原HttpSession |
| metrics401/旧gauge | collector原密码/hash、私有listener、snapshot新鲜 |
| 一次性结果丢失 | 查commit再授权重发/轮换，不找旧明文 |

最小修复后重查原路径、负例、readiness及通知；未解决的保护路径关闭。restore见[恢复](recovery.md)，consumer见[接入](integration.md)。
