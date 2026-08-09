# LAS SSO 第一版设计

- 状态：第一版实施基线（允许开始编码，未达到生产上线条件）
- 日期：2026-08-01
- 适用范围：LAS 自营服务的统一身份认证
- 现有原型：`SAS-Test`

## 1. 结论摘要

LAS SSO 使用 Spring Authorization Server 构建独立身份中心，负责回答“用户是谁”，不管理各业务系统中的角色、白名单、稿件权限或 Minecraft 绑定。

第一阶段采用以下边界：

- 登录方式仅为用户名和密码。
- 不收集或验证邮箱。
- 不实现 MFA。
- 用户永久身份为 UUID v7。
- 注册必须通过自托管 ALTCHA 和服务端限流。
- LASLOG 与 AlienCommon 各自使用后端作为 BFF。
- 浏览器不接触 Access Token、Refresh Token 或 ID Token。
- SSO 使用独立 PostgreSQL 和数据库凭据；第一版不引入 Redis。
- OIDC 使用 Authorization Code，BFF 同时启用 PKCE。
- SSO UI 独立开发，但与认证后端部署在同一站点。
- Minecraft 绑定、白名单与服务器准入全部归 LASLOG。

### 1.1 配套详细设计

本文件只保存架构边界和已经确认的产品决策；可直接指导实现的字段、接口、协议和验收规则拆分到以下文档：

- [文档总目录](docs/README.md)
- [身份与数据模型](docs/architecture/identity-and-data-model.md)
- [账号与管理 API](docs/architecture/api-contract.md)
- [OIDC 与 BFF 接入契约](docs/architecture/oidc-and-bff-contract.md)
- [安全、密码与恢复](docs/architecture/security-and-recovery.md)
- [部署与运维手册](docs/operations/operations-runbook.md)
- [测试与验收计划](docs/implementation/test-plan.md)
- [实施就绪度与开工门禁](docs/implementation/implementation-readiness.md)
- [SSO UI 流程规格](docs/ui/ui-flow-spec.md)

如文档之间出现冲突，以本文件中的产品边界为准；详细文档必须随总纲决策同步更新。

## 2. 目标与非目标

### 2.1 目标

- 为 LASLOG、AlienCommon 和未来 LAS 自营服务提供统一登录。
- 允许用户在已有 SSO 会话时无须重复输入密码。
- 支持退出当前应用、退出全部应用和管理员强制下线。
- 为不同后端技术栈提供一致的接入规范。
- 为未来人工审核的合作网站预留标准 OIDC 接入能力。
- 在约 1,000 名用户、低并发条件下保持结构简单、稳定且可维护。

### 2.2 非目标

- 不在 SSO 中管理业务角色或权限。
- 不在 SSO 中保存 Minecraft UUID、QQ、白名单或封禁状态。
- 不向 Token 注入业务数据。
- 不支持任意第三方自助注册 OIDC Client。
- 第一阶段不支持邮箱、短信、TOTP、Passkey 或社交登录。
- 第一阶段不建设高可用集群、云 KMS 或 HSM。

## 3. 系统关系

```text
用户浏览器
  ├─> LASLOG Nuxt
  │     └─> LASLOG Spring Boot BFF
  │            ├─> LAS SSO
  │            ├─> LASLOG 数据库
  │            └─> Minecraft 网关/内部服务
  │
  ├─> AlienCommon 页面
  │     └─> AlienCommon Django BFF
  │            ├─> LAS SSO
  │            └─> AlienCommon 数据库
  │
  └─> LAS SSO UI
         └─> Spring Authorization Server
                └─> SSO PostgreSQL
```

每个业务系统独立保存自己的会话、用户映射和权限。业务系统不得直接连接 SSO 数据库。

## 4. 域名与 Issuer

正式域名尚未确定，设计阶段统一使用保留占位域名：

```text
SSO / OIDC Issuer: https://auth.las.example
LASLOG:             https://log.las.example
AlienCommon:        https://common.las.example
```

本地开发暂用：

```text
SSO:         http://localhost:9000
LASLOG:      http://localhost:8088
AlienCommon: http://localhost:8000
```

约束：

- `.example` 只能用于设计和配置模板，不能用于生产签发 Token。
- 生产环境必须全部使用 HTTPS。
- 正式 Issuer 投入使用后不得随意更换。
- 各应用使用自己的主机级 Cookie，不设置整个主域共享 Cookie。
- 回调地址必须完整、精确登记，生产环境禁止通配符。

## 5. 身份与账号模型

### 5.1 用户标识

```text
user_id          UUID v7，永久不变
username         登录名，全局唯一
display_name     显示名，可以重复
avatar           可选头像
account_status   ACTIVE / FROZEN / DELETED
deletion_status  NONE / PENDING / HELD / CANCELLED / EXECUTED
```

规则：

- OIDC `sub` 使用 UUID v7。
- Spring Security `Authentication.getName()` 和 Spring Authorization Server `principalName` 也必须使用同一 UUID v7；用户名只作为 `preferred_username`。
- 业务系统使用 `(issuer, sub)` 映射本地用户。
- 用户名和显示名均不得作为永久身份。
- 第一阶段不保存邮箱。

### 5.2 用户名

- 允许 ASCII 大写字母、小写字母、数字和下划线。
- 长度为 3–24 个字符。
- 用户名大小写敏感，按用户输入的原始大小写保存和精确匹配；例如 `Alice` 与 `alice` 是两个不同用户名。
- 不进行大小写折叠，也不使用数据库 `citext` 或 `lower(username)` 唯一索引。
- 用户名允许修改，每 90 天最多一次。
- 仅改变字母大小写也属于一次用户名修改，并计入 90 天冷却。
- 每次变更记录旧值、新值、时间和操作者。
- 历史用户名按精确大小写永久保留，不得分配给其他账号；其他大小写组合仍按独立用户名处理。
- 显示名允许使用 Unicode，也允许重复。

该规则会允许视觉上相近的大小写变体账号。所有内部授权、审计和业务映射仍必须使用 UUID v7；界面、客服和人工恢复不得仅凭忽略大小写后的用户名判断账号归属。

### 5.3 一人一账号

“一人一个 LAS 账号”是社区政策，不是系统能够证明的事实。

取消邮箱验证后，系统只能通过以下手段降低批量注册：

- ALTCHA；
- IP、会话和时间窗口限流；
- 用户名唯一约束；
- 异常账号人工冻结；
- LASLOG 对 Minecraft 小号、封禁规避和资格滥用进行业务处置。

系统不使用侵入式设备指纹，也不声称能够识别现实中的自然人。

## 6. 注册

注册流程：

1. 浏览器获取一次性 ALTCHA challenge。
2. 用户填写用户名和密码。
3. 浏览器完成 ALTCHA 工作量证明。
4. SSO 验证 challenge、有效期、用途和防重放状态。
5. SSO 检查 IP 和注册频率。
6. SSO 按大小写敏感规则校验并检查用户名。
7. SSO 在数据库事务中创建 UUID v7 用户。
8. SSO 生成一次性恢复码并仅展示一次。

注册接口必须依赖数据库唯一约束处理并发用户名抢占，不能只依赖应用层预检查。

初始限流值应配置化。可以从较保守的每 IP 每小时 5 次、每日 20 次开始，根据共享网络误伤情况调整。

## 7. 人机验证

第一阶段使用 ALTCHA 开源核心：

- 使用官方 Java 库的 ALTCHA PoW v2 生成和验证 challenge；第一版算法为 `PBKDF2/SHA-256`，cost 5000，并可在实测后配置化调优。
- Widget 静态资源由 SSO 同源提供。
- PostgreSQL `captcha_challenges` 保存 challenge 的用途、有效期和已使用状态，并通过条件更新保证只消费一次。
- Challenge 仅能使用一次，建议 2–5 分钟过期。
- Challenge 必须绑定用途，例如 `REGISTER`。
- 注册始终要求验证。
- 登录仅在连续失败、明显异常或触发限流后要求验证。

人机验证只提高自动化成本，不能代替限流、数据库约束和人工治理。

## 8. 密码与账号恢复

### 8.1 密码规则

- 最少 12 个字符，最多 128 个字符。
- 允许空格和密码短语。
- 不强制大小写、数字和符号组合。
- 拒绝常见或已知泄漏密码。
- 不要求定期修改密码。
- 密码哈希优先使用 Argon2id；如果采用 BCrypt，成本参数必须按部署硬件校准。
- 永不记录或返回密码明文。

### 8.2 恢复码

- 注册完成后生成 8–10 个随机一次性恢复码。
- 恢复码只显示一次，服务端只保存哈希。
- 每个恢复码使用后立即失效。
- 用户可在重新输入密码后重新生成，重新生成会废除全部旧码。
- 用户忘记密码时，可用用户名和恢复码设置新密码。
- 密码恢复后撤销其他会话、Refresh Token 和授权。

如果用户同时失去密码和恢复码，只能进入管理员人工恢复流程。管理员不得查看或直接设置用户密码，只能签发短期、单次的恢复凭据。人工身份核验规则仍需在运营方案中定义。

## 9. 登录、记住设备与会话

### 9.1 登录

第一阶段仅支持：

```text
username + password
```

成功登录的 OIDC 认证方式声明：

```text
amr = ["pwd"]
acr = "urn:las:loa:1"
```

第一阶段不存在更高认证等级。

### 9.2 记住设备

由于没有 MFA，“记住设备”只表示持久会话，不表示设备完成过第二因素。

- 非记住设备：空闲 24 小时，绝对期限 14 天。
- 记住设备：距最后活跃 14 天，绝对期限 90 天。
- 达到绝对期限后必须重新输入密码。
- 用户可以查看并撤销活动会话。
- 修改或恢复密码、全局退出、管理员强制下线、账号冻结会撤销相关会话。

### 9.3 管理员会话

- 管理员不允许使用 90 天记住设备会话。
- 空闲期限固定为 45 分钟。
- 绝对期限固定为 10 小时。
- 用户恢复、OIDC 客户端及其客户端密钥管理等敏感操作要求 15 分钟内重新输入过密码。
- 所有管理操作写入审计日志。

该方案明确接受“无 MFA 时密码泄漏即可导致账号被完全接管”的剩余风险。

### 9.4 第一阶段管理员账号

第一阶段只设置一个 SSO 管理员账号，由一名服务器管理员持有。

该账号集中拥有：

- 用户查询、冻结、解除冻结和强制下线；
- 人工恢复凭据签发；
- OIDC Client、回调地址、Scopes 和客户端密钥管理；
- 安全事件与管理员审计记录查看；
- 异常注册和注销申请处理。

约束：

- 管理员身份绑定 UUID v7，不绑定可修改的用户名。
- 管理员账号不得多人共享。
- 管理员可以作为普通 LAS 用户登录业务系统，但业务权限仍由各业务系统单独授予。
- 第一阶段不建设网页端管理员角色分配功能。
- 首个管理员通过部署期命令按 UUID v7 授予，不提供公开提权接口，也不把初始密码写入数据库迁移。
- 首个管理员使用运维工具交互式设置临时密码；临时密码最长 24 小时有效，首次登录后必须立即更改。在更改完成前只能访问改密和退出功能。
- 唯一管理员不能通过管理页面删除、冻结自己或移除自己的管理员身份。
- 管理员必须离线保存恢复码；密码和恢复码同时丢失时，只能由服务器运维运行受审计的维护命令重置临时密码。
- 维护命令必须复用应用相同的 `PasswordEncoder`、密码策略、事务和审计服务；密码通过隐藏的交互式输入读取，不允许命令行参数、环境变量、日志或标准输出携带明文。
- 管理员密码被维护命令重置后，系统必须增加 `credentials_version`，撤销该账号的全部 SSO 会话、Authorization 和 Refresh Token，废除旧恢复码，并设置 `PASSWORD_CHANGE_REQUIRED`。管理员完成首次改密后重新生成恢复码。
- PowerShell 包装脚本统一命名为 `scripts/reset-admin-password.ps1`，只负责定位配置并调用 Java 维护命令；脚本本身不得实现哈希算法或直接更新数据库。
- 签名私钥、数据库、备份和部署权限不属于该网页账号，由服务器运维身份管理。

## 10. BFF 模式

### 10.1 LASLOG

```text
Nuxt 浏览器
  -> HttpOnly 会话 Cookie
  -> LASLOG Spring Boot BFF
  -> OIDC Token / 内部 API
```

### 10.2 AlienCommon

```text
浏览器
  -> HttpOnly 会话 Cookie
  -> AlienCommon Django BFF
  -> OIDC Token / 业务 API
```

约束：

- 浏览器不得获取 Access Token、Refresh Token 或完整 ID Token。
- BFF 使用 `Secure`、`HttpOnly` 和合适的 `SameSite` Cookie。
- 所有改变状态的请求启用 CSRF 防护。
- 每个业务系统拥有独立 Client ID、客户端凭据、Cookie 和会话。
- 不建立所有业务共用的中央 BFF。
- SSO 不在每次业务请求时被调用。
- 每个需要身份的 BFF 请求检查 `auth_fresh_until`；它等于当前 Access Token 到期时间。到期后必须成功刷新或重新走顶层 Authorization Code，不能仅凭仍未过期的本地 Cookie 继续。

## 11. OIDC 客户端与协议

### 11.1 第一方客户端

第一阶段客户端：

```text
laslog-bff
aliencommon-bff
```

它们都是 Confidential Client：

- Authorization Code；
- PKCE S256；
- 精确回调地址；
- 服务端保存客户端凭据；
- 禁止 Implicit Flow；
- 禁止 Resource Owner Password Credentials；
- 禁止浏览器保存客户端密钥。

### 11.2 Scopes 与 Claims

第一阶段支持：

```text
openid
profile
```

可以提供：

```text
sub
preferred_username
name
picture
updated_at
auth_time
acr
amr
sid
```

第一阶段不提供：

```text
email
Minecraft UUID
QQ
白名单
封禁状态
管理员或业务角色
稿件权限
```

LAS 自营客户端由管理员预先批准 scopes，不显示用户授权页。未来人工审核的合作网站首次登录和新增 scope 时必须显示授权确认页，并允许用户撤销授权。

### 11.3 Token 初始时长

```text
Authorization Code: 1–3 分钟，只能使用一次
ID Token:           5–10 分钟
Access Token:       10–15 分钟
Refresh Token family: 从首次授权起绝对 14 天，每次使用轮换但不顺延
```

Refresh Token 重复使用时，应撤销对应 Token family 和业务会话。刷新事务必须使用数据库行锁，并重新检查账号状态、`credentials_version`、全局 `security_epoch` 和客户端 `config_version`。

如果 Refresh Token 到期但 SSO 记住设备会话仍然有效，BFF 可以重新发起 Authorization Code 流程；用户可以无须输入密码完成重新授权，但不能依赖隐藏 iframe。

## 12. 退出与强制下线

### 12.1 退出当前应用

- 先删除当前 BFF 会话并清除 Cookie，即使 SSO 暂时不可用也立即完成本地退出。
- 通过可靠 outbox/后台任务尽力撤销当前客户端的 Refresh Token 和授权。
- 保留 SSO 主会话。
- 用户再次进入应用时，可以借助 SSO 会话无感重新登录。

### 12.2 退出全部应用

- 撤销 SSO 主会话。
- 撤销该用户的所有授权和 Refresh Token。
- 向已登记 BFF 发送 Back-channel Logout。
- BFF 根据 `sid` 清除本地会话。
- 通知失败进行有限次数重试。
- 正常情况下数秒生效，故障时由短期 Token 和会话检查保证最多约 15 分钟延迟。

管理员强制下线使用相同机制。

## 13. 业务系统本地用户

业务系统采用 JIT Provisioning：

1. 验证 OIDC 登录结果。
2. 以 `(issuer, sub)` 查询本地用户。
3. 不存在时创建本地用户记录。
4. 默认授予普通用户或无敏感权限状态。
5. 用户名、显示名和头像只作为可更新资料快照。
6. 业务角色、权限、封禁和内容历史保存在业务数据库。

SSO 用户停用或删除后，业务系统保留历史内容和审计记录，但禁止再次登录。

## 14. Minecraft 边界

Minecraft 不属于 SSO 身份核心：

- SSO 不保存 Minecraft UUID。
- Minecraft UUID 不进入任何 OIDC Token。
- 用户必须先登录 LASLOG，再在 LASLOG 中完成 Minecraft 绑定。
- Velocity 或 Minecraft 网关只调用 LASLOG 的专用服务接口。
- Minecraft 白名单、封禁、解绑、资格和准入由 LASLOG 管理。

现有 `SAS-Test` 中的 Minecraft Controller、Service 和 JWT Claims 均不得迁入正式 SSO。

## 15. 数据存储

### 15.1 PostgreSQL

建议实体：

```text
users
usernames
password_credentials
recovery_codes
admin_recovery_credentials
registered_clients
authorizations
consents
user_sessions
system_security_state
account_deletion_requests
admin_audit_events
security_events
signing_key_metadata
outbox_events
```

约束：

- SSO 数据库和凭据独立。
- 业务应用禁止直接查询或修改 SSO 表。
- 所有结构变更使用版本化数据库迁移。
- 密码、恢复码和客户端密钥不能以明文存储。

### 15.2 PostgreSQL 短期安全状态

第一版不部署 Redis。短期状态与永久账号数据使用同一个 PostgreSQL 实例，但必须放在独立表中：

- `rate_limit_buckets`：固定窗口原子计数，主键为范围、脱敏键摘要和窗口起点；
- `captcha_challenges`：challenge 摘要、用途、有效期和单次消费时间；
- `recovery_sessions`：受限恢复流程的 token 摘要、有效期、消费状态和失败次数；
- `registration_idempotency`：注册幂等键、请求 HMAC 摘要、结果用户和 24 小时有效期，不保存恢复码明文；
- OIDC Authorization、会话、Logout outbox 继续使用各自持久化表，不混入上述短期表。

限流必须使用单条 `INSERT ... ON CONFLICT ... DO UPDATE` 原子递增；一次性状态必须使用带“未消费且未过期”条件的原子更新。后台任务每 5 分钟用 PostgreSQL transaction advisory lock 抢占一次清理权，按表每批最多删除 1000 条过期记录。多实例只允许一个实例执行当轮清理。

短期表可以在事故恢复时清空，代价是用户重新获取 challenge、重新进入恢复流程或重新累计限流窗口；永久账号、授权和审计数据不得依赖这些表恢复。

按首期约 1000 用户、通常 1–2 并发的规模，这一方案避免了额外组件和跨存储一致性成本。代价是 PostgreSQL 成为登录与短期安全状态的共同故障域；必须监控连接池等待、限流 UPSERT 延迟、锁等待、短期表行数和清理失败。只有当实测数据库争用持续影响登录延迟，或需要跨地域共享极高频短期状态时，才单独评审引入 Redis，不预先在代码契约中绑定它。

## 16. 签名密钥

- 禁止每次启动随机生成生产 RSA 密钥。
- 使用持久化 RSA 私钥。
- 私钥以受限权限的 PKCS#12 或 PEM 文件挂载。
- 密钥密码由部署环境注入，不进入仓库。
- JWKS 只公开公钥。
- 每把密钥拥有稳定唯一的 `kid`。
- 建议每年轮换一次。
- 轮换期间同时发布新旧公钥。
- 旧公钥保留到所有旧 Token 过期后再移除。
- 私钥保存加密离线备份。
- 生产启动时找不到密钥必须失败，不能静默生成新密钥。

第一阶段不引入云 KMS 或 HSM。

## 17. UI 与部署

SSO UI 使用独立代码仓库开发，但生产环境和认证后端同源：

```text
https://auth.las.example/           登录、注册和账号页面
https://auth.las.example/oauth2/*   OAuth2/OIDC 端点
https://auth.las.example/api/*      账号与安全接口
```

可以由反向代理分别路由静态 UI 和 Spring 服务，也可以把前端构建产物交给 Spring/静态服务器提供。

开始 UI 编码前应先完成[第一版 UI 流程规格](docs/ui/ui-flow-spec.md)中的低保真页面状态和安全交互，尤其是 OIDC 登录上下文、恢复码一次性展示、强制改密、近期认证、两种退出、注销等待期和管理员危险操作。不要求先完成 Logo、品牌视觉、插画或高保真设计；后端工程、数据模型、OIDC 和 BFF 契约实现可以与线框设计并行。

第一阶段推荐部署：

```text
Reverse Proxy
Spring SSO
PostgreSQL
持久化签名密钥挂载
```

约 1,000 名用户不需要多实例集群。应优先完成：

- PostgreSQL 定时加密备份；
- 签名私钥独立备份；
- 恢复演练；
- 服务健康检查；
- 数据库连接、短期状态表增长、清理失败和登录失败监控；
- 审计日志保留与清理策略。

SSO 暂时不可用时，已有 BFF 会话可以在本地有效期内继续工作，但不能建立新登录或刷新到期授权。

## 18. 公共接入规范与适配库

不建立跨语言的大型统一认证库，而是维护：

```text
LAS OIDC Integration Contract
las-sso-spring-boot-starter
las-sso-django
```

适配库统一提供：

```text
login
callback
logoutCurrent
logoutGlobal
currentUser
requireLogin
requireRecentAuthentication
session
backChannelLogout
```

前端只接触业务 BFF 暴露的本地接口，例如：

```text
GET  /api/session
POST /api/logout
POST /api/logout/global
```

适配库必须包装成熟 OIDC Client，不自行实现协议或 JWT 密码学。

## 19. 自助注销

- 用户重新输入密码后申请注销。
- 进入 30 天等待期。
- 等待期内用户可以重新认证并取消。
- 到期后删除密码、恢复码、会话和授权。
- UUID v7 永不复用，并保留最小墓碑记录。
- 历史稿件、处罚、白名单和审计由各业务系统独立保留或匿名化。
- 被冻结或存在业务争议的账号如何处理，由各业务系统的保留政策决定。

## 20. 现有 `SAS-Test` 的定位

`SAS-Test` 仅作为协议验证原型：

可以保留的方向：

- Spring Authorization Server；
- OIDC Discovery；
- Authorization Code；
- 服务端 OAuth2 Client 示例；
- 自定义登录页面的基本思路。

不得直接进入生产的部分：

- 内存用户和客户端；
- 明文密码与客户端密钥；
- 启动时随机生成的 RSA 私钥；
- Token 页面明文展示；
- 自定义非标准退出端点；
- Minecraft 绑定接口；
- `mcUuids` 和 `qqId` Claims；
- 无过期、无认证的四位 Minecraft 绑定码；
- 无测试的现状。

正式实现应以新生产结构为目标，按模块迁移可复用的协议验证经验，而不是继续在原型结构上堆功能。

## 21. 第一阶段交付顺序

1. 按[实施就绪度](docs/implementation/implementation-readiness.md)完成阶段 0：正式工程、依赖/迁移工具锁定、真实 PostgreSQL 测试环境，以及持久化 Authorization、UUID principal、Back-channel Logout 三个技术验证。
2. 实现持久化用户、密码、恢复码、账号/注销双状态、唯一管理员初始化和离线密码重置命令。
3. 实现登录、会话版本校验、近期认证、ALTCHA 注册与限流。
4. 实现持久化 OIDC Client、Authorization 和 Consent，固定本地 Issuer、签名密钥和 JWKS。
5. 实现 AT 15 分钟、RT family 绝对 14 天、轮换/重用检测、并发锁和安全版本检查。
6. 先完成 LASLOG Spring BFF 的完整纵向链路和契约测试。
7. 按同一契约实现 AlienCommon Django BFF。
8. 实现本地优先的当前应用退出、全局退出、Back-channel Logout 幂等重试和 15 分钟 freshness 上界。
9. 按低保真流程实现账号中心、会话管理、自助注销和管理员审计 UI。
10. 执行恢复后 `security_epoch`/签名 key 全局重置演练、浏览器 E2E、性能和生产安全检查。

## 22. 上线前仍需确定

以下事项阻塞生产上线，但不阻塞按实施就绪度文档开始阶段 0/1 编码：

- 正式主域名和固定 Issuer。
- LASLOG、AlienCommon 的正式回调地址。
- 部署服务器与反向代理方案。
- PostgreSQL 的备份、监控、短期表清理告警与保留周期。
- 人工账号恢复的身份核验流程。
- 管理员操作审计保留时间。
- 密码哈希参数在生产硬件上的基准测试结果。
- 正式依赖版本及其支持周期。
- 未来合作网站的审核标准与隐私条款。
