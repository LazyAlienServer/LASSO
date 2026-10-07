# ADR-0012：生产配置与启动门禁

- 状态：已接受并实现，2026-10-07
- [English](../../adr/0012-production-configuration-startup-gates.md)

## 背景

SSO 已将 `local`/`test` 临时签名 key 与非开发持久 key 分开，也已有数据库 readiness。部署手册仍有空缺：非 local 进程可以带 HTTP/example issuer、开发秘密、不安全 Cookie 覆盖、未审查 Flyway 路径、无可用管理员或带开发 redirect/secret 的 ACTIVE 客户端启动。URI 存在和数据库健康都不能证明可以安全开放认证。

## 决策

1. 非开发进程必须显式启用 `production`，不能与 `local`/`test` 混用。生产校验在普通配置绑定前执行，只输出稳定类别码，不暴露秘密/路径/驱动原因。
2. 生产要求唯一 HTTPS issuer，路径必须等于 servlet context path；要求持久 key path/alias/password/kid、相互独立的高熵 pepper 与数据库/key密码、PostgreSQL JDBC、内嵌 `classpath:db/migration` Flyway、迁移校验/禁止 clean、JDBC session schema 为 `never`、含 DB readiness、粗粒度 health 和受限 actuator。`__Host-las_sso` 必须保持 Secure/HttpOnly/Lax/Path `/` 且无 Domain。pepper/CAPTCHA至少32个UTF-8字节，DB/key密码至少12字节，五者独立；长度不保证熵。HTTPS URI策略在生产客户端登记和启动时均拒loopback及保留example域名。
3. PKCS12 entry 必须是至少2048位且公私匹配的 RSA 对。签名配置失败只给稳定类别，并清理密码字符数组。
4. 生产 Web 启动在 Flyway 后做数据库快照校验：security epoch 存在；恰好一个可用 ACTIVE SSO 管理员，具有用户名/密码，且为普通 active credential 或未过期临时密码；有效注销 pending/held 阻止就绪。空客户端表允许存在，以便 bootstrap 后登记第一方客户端。已有 ACTIVE 客户端必须有 metadata、audience、PKCE、受支持 scope、`client_secret_basic`、Code+Refresh grant、精确 HTTPS redirect/post-logout/Back-channel URI，且不能使用已知 local example secret。DISABLED 客户端不阻止启动，也不会由门禁发放。
5. 三种维护命令要求 `production`、`WebApplicationType.NONE`、关闭 scheduling/session cleanup、关闭 monitoring 和交互 console。Bootstrap 在索取 console 前拒绝密码命令行参数。空库仍通过维护路径初始化；无管理员时 Web 不开放。

## 后果

Flyway初始化后仅接受已执行SUCCESS且checksum匹配的迁移；未来schema和pending/above-target/missing迁移不能默认为兼容。

这些条件不满足时，生产启动在 connector 接收流量前失败关闭。管理员可以在临时密码未过期且状态为 `PASSWORD_CHANGE_REQUIRED` 时启动；已有请求过滤器仍强制改密。恢复/换 key 仍须执行记录的 epoch、key 和消费方屏障；门禁不是恢复操作，不做跨系统对账。客户端登记、正式域名、TLS、秘密保管、备份和目标容量仍由运维负责。

配置校验使用当前环境/属性值，不能保证可信代理、公共 CA、真实客户端所有权或秘密管理器保管。空库不强制客户端，因为 bootstrap/管理流必须可用。多实例和消费方可靠撤销仍是独立决策。
