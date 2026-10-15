# 安全规则

[目录](README.md) · [English](../en/security.md)

## 凭据与浏览器边界

- 密码为 12–128 个 Unicode 码点、最多 512 UTF-8 字节，允许空格；按操作拒绝用户名、常见或重复密码。默认使用 Argon2id 摘要。
- 用户名大小写敏感，为 3–24 位 ASCII 字母、数字或下划线。
- 每批十枚 80-bit 恢复码，只显示一次；换批使旧码失效。自助恢复保留同批其他未用普通码，正常改密、管理员提升或重置使相应旧材料失效。
- 人工恢复限独立核验的普通账号，至少两项独立依据、最小记录、私密交付；管理员走离线维护。
- SSO Cookie为`__Host-las_sso`，Secure/HttpOnly/Lax、Path=/、无Domain；恢复Cookie为`__Host-las_recovery`，Secure/HttpOnly/Strict。
- Cookie API需CSRF及同源写，OIDC密码重认证不豁免CSRF；密码只在SSO输入。

## 时间边界

| 材料 | 默认规则 |
| --- | --- |
| 授权码 | 2分钟 |
| AT | 15分钟 |
| RT family | 创建起固定14天，轮换不延长 |
| 普通 SSO 会话 | 空闲 24 小时 / 绝对 14 天 |
| 记住设备的普通 SSO 会话 | 空闲 14 天 / 绝对 90 天 |
| 管理员 SSO 会话 | 空闲 45 分钟 / 绝对 10 小时；记住设备不延长 |
| 近期密码证据 | 严格小于15分钟，仅发起登录/session |
| OIDC密码challenge | 5分钟，浏览器/请求绑定，完成/消费原子 |
| 恢复session | 凭据消费后10分钟 |
| 人工恢复凭据 | 30分钟、单次 |
| bootstrap临时密码 | 24小时、首登强改 |
| 注销/hold | 原30天截止；hold最多90天，释放不重启等待期 |
| Starter本地绝对session | 最多14天，初期重启废业务登录 |

Token 签发/刷新、弹窗或 callback 成功本身不能证明重新输入密码。初始 ID Token 的签名 auth_time 按授权固定；消费方实现待执行动作与本地会话的关联及到期处理。

## 生产配置

非开发显式`production`，不混local/test。启动核canonical HTTPS issuer/context、持久RSA≥2048、合法kid、独立秘密、PG/内嵌Flyway保护、Cookie/health、唯一可用admin。可无client；已有ACTIVE client需已审查URI/scope/audience/PKCE/机密配置。

只有可信Nginx承接应用流量并覆盖代理头，management保持私有。[配置](configuration.md)给精确输入/哈希生成，[部署](deployment.md)给权限和顺序。

## 撤销与恢复

refresh 重用撤销所属 family 并产生高危证据；其他 client 的 RT 被拒绝且不修改所属授权。丢失刷新后继仍属未知：本地拒绝访问，保留已接受的已知 Token 作业，并评估残留授权。RFC7009 的 200 不证明未知后继消失；故意 reuse 不是补偿协议。历史 RT 撤销后继仍为可选、未实现的增强。

还原需要新 epoch/签名密钥屏障。保留永久身份和审计事实，结束消费方旧会话、缓存及证据，流程见[恢复](recovery.md)。

## 数据与记录

密码/pepper/client secret、key、code、Token、Cookie及queue密文不进Git/发行包/日志；只保有界安全类别/requestId与脱敏证据。live RT/hint、pending/dead作业不自动裁剪；终态session/credential至少90天，过期授权/RT/成功outbox至少30天。

V1用户名密码，无MFA/邮箱门槛，SSO角色不授业务权限。当前发行限制见仓库ROADMAP.md，包括RC继承镜像的pebble发现。
