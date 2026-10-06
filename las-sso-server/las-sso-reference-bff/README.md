# LAS SSO Reference BFF

这是 Starter 的可运行接入样例，不是 LASLOG 后端。默认使用服务端 HTTP Session 保存 OAuth 授权客户端，浏览器只接收安全会话快照，不接收 Token。

## 运行

先按[服务端 README](../README.md)启动本地 SSO，再从 `las-sso-server/` 运行：

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun
```

访问 `http://127.0.0.1:8088`，一次授权流程内保持同一主机名。默认客户端是 local-only `laslog-bff`，只用于隔离开发；外部环境必须供应正式客户端配置。

## 可选配置

| Profile | 用途与配置 |
| --- | --- |
| 默认 | Starter 自动构建安全链，进程内会话和撤销作业 |
| `application-security` | 应用自有安全链，显式调用公共 `LasSsoSecurityConfigurer`，仍保留业务路由授权 |
| `https` | 外部 `LAS_SSO_ISSUER`、`LAS_SSO_CLIENT_ID`、`LAS_SSO_CLIENT_SECRET`；Secure/HttpOnly/SameSite Lax 主机 Cookie，证书/可信代理另配 |
| `durable-revocation` | 可选独立 PostgreSQL 加密撤销队列，必须供应独立 DB、32-byte AES key 和 key ID，详见[持久撤销契约](../../docs/zh-CN/integration/reference-durable-revocation.md) |

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun --args="--spring.profiles.active=application-security"
```

`https` 和持久队列的秘密设置放外部受限文件，不作为命令行参数或提交到 Git。示例队列只保证已提交任务重启恢复，默认会话、索引、重放缓存和刷新合并仍是单 JVM；业务重启后重新登录。

## 验证与边界

```powershell
.\gradlew.bat :las-sso-reference-bff:test -PrequireDatabaseTests=true --no-daemon --console=plain
```

测试包含真实 PostgreSQL/HTTP 的加密、租约、重试和完成回执。默认/自有链的浏览器 TLS、刷新及两类退出已有[实际验收](../../docs/zh-CN/operations/grafana-mixed-load-2026-10-07.md)，验证范围对应文档中的原始制品版本。

消费方自己负责业务数据库、可靠撤销适配、权限、资格缓存和近期密码门禁。样例不提供多实例、跨重启保留登录或跨存储原子保证，也不自动配置生产证书、域名或防火墙。
