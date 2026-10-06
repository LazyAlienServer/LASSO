# LAS SSO 身份与数据模型

- 状态：第一版实施规格
- 依赖总纲：[LAS SSO 第一版设计](../../LAS_SSO_DESIGN.md)
- 数据库：PostgreSQL
- 临时状态：PostgreSQL 独立短期状态表

## 1. 模型原则

1. `users.id` 是 UUID v7，并直接作为 OIDC `sub`。
2. 用户名、显示名和头像都是可变资料，不能作为外键或业务永久标识。
3. SSO 不保存邮箱、Minecraft UUID、QQ、业务角色、白名单或稿件权限。
4. 业务系统只保存 `(issuer, sub)` 映射，不读取 SSO 数据库。
5. PostgreSQL 同时保存永久事实和短期状态；两类数据使用独立表、不同清理规则，短期状态不得成为永久事实的唯一来源。
6. 密码、恢复码、人工恢复凭据和客户端密钥只保存不可逆摘要。
7. 所有时间使用 UTC `TIMESTAMPTZ`，接口统一输出 RFC 3339。
8. 所有业务写入通过版本化迁移和事务完成，不以手工 SQL 作为正常操作路径。

## 2. 状态枚举

### 2.1 账号状态 `account_status`

| 值 | 含义 | 是否允许登录 |
|---|---|---|
| `ACTIVE` | 正常账号 | 是 |
| `FROZEN` | 管理员冻结 | 否 |
| `DELETED` | 已删除，仅保留墓碑 | 否 |

不设置“未验证邮箱”或“待激活”状态。注册事务成功后账号直接进入 `ACTIVE`。

注销不是账号状态的替代品。同一账号可能既被冻结又处于注销等待/暂缓，因此单独使用 `deletion_status`：

```text
NONE
PENDING
HELD
CANCELLED
EXECUTED
```

登录和操作权限同时检查 `account_status` 与 `deletion_status`。`PENDING`/`HELD` 只允许进入注销查看、取消和退出流程；冻结状态仍优先拒绝自助恢复到正常业务登录。注销执行完成后 `account_status=DELETED` 且 `deletion_status=EXECUTED`。

### 2.2 密码状态 `credential_state`

| 值 | 含义 |
|---|---|
| `ACTIVE` | 可正常认证 |
| `PASSWORD_CHANGE_REQUIRED` | 仅允许登录后立即改密 |
| `DISABLED` | 凭据已删除或账号不可认证 |

管理员初始临时密码使用 `PASSWORD_CHANGE_REQUIRED`。唯一管理员离线维护重置由服务器持有者在交互式终端直接设置最终密码，成功后状态为 `ACTIVE`，同时撤销旧安全状态。

### 2.3 会话与授权状态

`session_status` 与 `authorization_status` 均使用：

```text
ACTIVE
REVOKED
EXPIRED
```

`EXPIRED` 可以由读取时计算并异步清理；安全判断不能依赖清理任务已经运行。

## 3. PostgreSQL 表

以下是逻辑字段。实现可以沿用 Spring Authorization Server 的字段命名，但约束和语义不得改变。

### 3.1 `users`

| 字段 | 类型 | 约束/说明 |
|---|---|---|
| `id` | `UUID` | 主键，应用生成 UUID v7 |
| `display_name` | `VARCHAR(64)` | Unicode，可重复 |
| `avatar_resource_id` | `VARCHAR(128)` | 可空；第一版只允许服务端头像资源允许列表中的 ID |
| `account_status` | `account_status` | 非空 |
| `is_sso_admin` | `BOOLEAN` | 非空，默认 `false` |
| `profile_version` | `BIGINT` | 非空，资料变更时递增 |
| `service_access_state_version` | `BIGINT` | 非空，从 1 开始；服务访问资格相关状态改变时递增 |
| `service_access_changed_at` | `TIMESTAMPTZ` | 最近一次服务访问资格状态改变时间 |
| `created_at` | `TIMESTAMPTZ` | 非空 |
| `updated_at` | `TIMESTAMPTZ` | 非空 |
| `frozen_at` | `TIMESTAMPTZ` | 可空 |
| `deleted_at` | `TIMESTAMPTZ` | 可空 |

数据库约束：

- 建立 `is_sso_admin = true` 的唯一部分索引，保证最多一个网页管理员。
- `DELETED` 用户必须有 `deleted_at`，显示名和头像必须清空或匿名化。
- 不允许物理删除 `users.id`；删除后该行作为 UUID 墓碑。

`service_access_state_version` 和 `service_access_changed_at` 只描述 SSO 对第一方服务访问资格的最新确认版本，不是业务角色或权限版本。业务系统不得据此推导项目、服务器、白名单或内容权限。

### 3.2 `usernames`

| 字段 | 类型 | 约束/说明 |
|---|---|---|
| `id` | `UUID` | 主键，UUID v7 |
| `user_id` | `UUID` | 外键 `users.id` |
| `username` | `VARCHAR(24) COLLATE "C"` | 非空，保留原始大小写，正则 `^[A-Za-z0-9_]{3,24}$` |
| `valid_from` | `TIMESTAMPTZ` | 非空 |
| `valid_to` | `TIMESTAMPTZ` | 当前用户名为空 |
| `changed_by_user_id` | `UUID` | 操作者；注册时等于 `user_id` |
| `change_reason` | `VARCHAR(64)` | `REGISTER`、`SELF_SERVICE`、`ADMIN_CORRECTION` |

数据库约束：

- `username` 使用确定性的大小写敏感比较并全表永久唯一，历史用户名不能按完全相同的大小写重新分配。
- 禁止 `citext`、`lower(username)` 索引或应用层大小写折叠；`Alice` 与 `alice` 是两个不同键。
- 每个用户最多一条 `valid_to IS NULL` 的记录。
- 普通用户自助修改时，上一条用户名的 `valid_from` 距当前必须至少 90 天。
- 仅改变大小写也要关闭旧记录、插入新记录并进入新的 90 天冷却。
- 管理员更正仍保留完整历史并写管理员审计，不删除旧记录。

登录查询只匹配当前用户名；历史用户名不允许登录，避免账号转移后的歧义。

### 3.3 `password_credentials`

| 字段 | 类型 | 约束/说明 |
|---|---|---|
| `user_id` | `UUID` | 主键、外键 |
| `password_hash` | `TEXT` | PHC 格式或 Spring `DelegatingPasswordEncoder` 格式 |
| `state` | `credential_state` | 非空 |
| `credentials_version` | `BIGINT` | 非空，从 1 开始；改密/恢复时递增 |
| `password_changed_at` | `TIMESTAMPTZ` | 非空 |
| `temporary_expires_at` | `TIMESTAMPTZ` | 仅临时密码使用，最长 24 小时 |
| `failed_attempts` | `INTEGER` | 账号级持久失败计数；IP/范围窗口使用 `rate_limit_buckets` |
| `locked_until` | `TIMESTAMPTZ` | 可空；短期锁定 |

任何会话都保存建立时的 `credentials_version`。当前版本不匹配时，即使 Cookie 尚未过期也必须拒绝。

资格version的V13用户counter只在同全局security_epoch内单调；2026-10-05用户确认ADR-0007后internalquery公开stateEpoch复用该标识，不加schema。restore旧counter须换epoch再开流量；同snapshot读取epoch/user/credential/deletion，包括unknowncurrentepoch，blank/missingepoch失败不可用。禁止把跨epochcounter或changedAt直接排序，消费方必须恢复屏障废旧cache/inflight，见[ADR英文](../adr/0007-scope-service-access-versions-to-security-epoch.md)／[中文](../zh-CN/adr/0007-scope-service-access-versions-to-security-epoch.md)及[query契约](../implementation/service-access-epoch-contract.md)。

### 3.4 `recovery_codes`

| 字段 | 类型 | 约束/说明 |
|---|---|---|
| `id` | `UUID` | 主键 |
| `user_id` | `UUID` | 外键 |
| `code_hash` | `BYTEA` | 带服务器 pepper 的 HMAC 摘要 |
| `generation` | `INTEGER` | 每次整批重发时递增 |
| `created_at` | `TIMESTAMPTZ` | 非空 |
| `used_at` | `TIMESTAMPTZ` | 可空 |
| `revoked_at` | `TIMESTAMPTZ` | 可空 |

同一恢复码只能通过原子条件更新从“未使用”变为“已使用”。重新生成时，在同一事务内废除该用户全部旧码。

S6（2026-10-04）新普通/人工码采用16随机字符80bit，存储仍32byte HMAC-SHA-256，无format/version列迁移、无pepper或规范化变更。旧三组60bit只保留原有效性，不重写或续期；rotation/reissue同事务旧撤销＋新四组生成，失败回滚旧状态。独立旧摘要fixture验证与新码恢复/期限/消费/审计见[English](../implementation/recovery-credential-entropy.md)／[中文](../zh-CN/implementation/recovery-credential-entropy.md)。

### 3.5 `admin_recovery_credentials`

该表保存管理员为普通用户签发的短期、单次人工恢复凭据，不保存管理员自己的维护命令密码。

| 字段 | 类型 | 约束/说明 |
|---|---|---|
| `id` | `UUID` | 主键 |
| `target_user_id` | `UUID` | 被恢复用户 |
| `issued_by_user_id` | `UUID` | 唯一管理员 |
| `credential_hash` | `BYTEA` | 带 pepper 的 HMAC 摘要 |
| `expires_at` | `TIMESTAMPTZ` | 建议 30 分钟，最长 24 小时 |
| `used_at` | `TIMESTAMPTZ` | 可空 |
| `revoked_at` | `TIMESTAMPTZ` | 可空 |
| `reason` | `TEXT` | 必填，供审计 |
| `created_at` | `TIMESTAMPTZ` | 非空 |

签发前必须完成近期密码确认。接口响应仅一次返回明文凭据。

### 3.6 `user_sessions`

| 字段 | 类型 | 约束/说明 |
|---|---|---|
| `id` | `UUID` | 内部主键 |
| `sid` | `VARCHAR(128)` | OIDC 会话标识，Issuer 内唯一且不可预测 |
| `user_id` | `UUID` | 外键 |
| `status` | `session_status` | 非空 |
| `remembered` | `BOOLEAN` | 是否选择持久会话 |
| `admin_session` | `BOOLEAN` | 登录时是否具有 SSO 管理员身份 |
| `credentials_version` | `BIGINT` | 建立会话时的密码版本 |
| `security_epoch` | `VARCHAR(64)` | 建立会话时的全局安全 epoch |
| `created_at` | `TIMESTAMPTZ` | 非空 |
| `last_seen_at` | `TIMESTAMPTZ` | 非空；最多每 5 分钟落库一次 |
| `idle_expires_at` | `TIMESTAMPTZ` | 非空 |
| `absolute_expires_at` | `TIMESTAMPTZ` | 非空 |
| `last_password_auth_at` | `TIMESTAMPTZ` | 近期认证判断 |
| `revoked_at` | `TIMESTAMPTZ` | 可空 |
| `revoke_reason` | `VARCHAR(64)` | 可空 |
| `ip_prefix_hash` | `BYTEA` | 可空，最小化后的安全信号 |
| `user_agent_summary` | `VARCHAR(256)` | 截断后的展示信息 |

不建立永久设备指纹。“记住设备”只由该会话的过期策略表达。每次使用会话都校验当前账号状态、`credentials_version`、`security_epoch`，管理员请求还要校验数据库角色和 `admin_session=true`。

当前实现的 OIDC 密码 challenge 使用 V14 `oidc_password_challenges`（id、expires_at、completed_at、consumed_at、subject、sid）做数据库条件更新；原授权参数和浏览器关联保存在 Spring Session JDBC。它不是可复用登录凭据，也不保存密码。完成及消费必须在未到期、未完成/未消费条件下原子成功，过期记录由短期状态清理任务删除；每个浏览器只保留一个待验证事务。

### 3.7 `registered_clients`

| 字段 | 类型 | 约束/说明 |
|---|---|---|
| `id` | `UUID` | 内部主键 |
| `client_id` | `VARCHAR(128)` | 全局唯一、不可猜测不是安全要求 |
| `client_name` | `VARCHAR(128)` | 展示名称 |
| `client_type` | `VARCHAR(32)` | `FIRST_PARTY` 或 `REVIEWED_PARTNER` |
| `status` | `VARCHAR(16)` | `ACTIVE`、`DISABLED` |
| `require_pkce` | `BOOLEAN` | 第一版固定 `true` |
| `require_consent` | `BOOLEAN` | 第一方 `false`，合作方 `true` |
| `authorization_grant_types` | `TEXT[]` | 第一版只允许 `authorization_code`、`refresh_token` |
| `authentication_methods` | `TEXT[]` | 第一版 BFF 只允许 `client_secret_basic` |
| `scopes` | `TEXT[]` | 第一版 `openid profile` |
| `resource_audiences` | `TEXT[]` | 允许签发的 API audience |
| `secret_hash` | `TEXT` | 当前唯一活动 Client Secret 的 `PasswordEncoder` 摘要 |
| `secret_expires_at` | `TIMESTAMPTZ` | 可空；到期后客户端认证失败 |
| `config_version` | `BIGINT` | 非空，从 1 开始；安全相关配置变化时递增 |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | 非空 |

回调地址和退出地址使用子表，避免不可审计的 JSON 整体覆盖：

- `registered_client_redirect_uris(client_id, uri, environment)`
- `registered_client_post_logout_uris(client_id, uri, environment)`
- `registered_client_backchannel_logout_uris(client_id, uri, session_required)`

第一版每个客户端只保留一把活动密钥，与 Spring Authorization Server 的 `RegisteredClient` 模型保持一致。轮换会原子替换摘要、增加 `config_version` 并立即使旧密钥失效，因此必须安排短维护窗口。密钥原文只在创建或轮换响应中显示一次。

### 3.8 `authorizations` 与 `consents`

实现应通过 Spring Authorization Server 的 `OAuth2AuthorizationService` 和 `OAuth2AuthorizationConsentService` 持久化，不直接从 Controller 修改。

至少保存：

- 授权 ID、用户 ID、客户端 ID、授权类型、已批准 scopes、`sid`；
- 建立授权时的 `credentials_version`、全局 `security_epoch` 和 `client_config_version`；
- 当前实现还在初始 Authorization 属性中保存可信密码认证时间快照（Unix 秒）；用于签名 ID Token `auth_time`，Code 兑换/刷新及后续密码认证不重写；无证据的旧授权不补造；
- Authorization Code 的值/元数据、用途、到期和使用时间；
- Refresh Token 的值/元数据、到期、使用时间、`token_family_id`、`parent_token_id`、`family_created_at` 和 `family_absolute_expires_at`；
- 授权状态、撤销时间和撤销原因；
- 合作方 consent 的 scope 集合、创建时间和最后更新时间。

第一版使用 Spring Authorization Server 的标准 `OAuth2AuthorizationService` 持久化格式。该模型可能保存框架完成授权和刷新所需的可用 Token 值，因此 SSO 数据库按高敏秘密存储处理：独立账号和网络、最小权限、加密磁盘/备份、禁止查询日志和响应日志。密码、恢复码、管理员恢复凭据和 Client Secret 仍只保存摘要。若以后要求数据库中所有 OAuth 凭据只存摘要，应作为单独安全项目实现并完成协议兼容测试，不能在第一版中半自定义 Repository。

Access Token 使用 15 分钟签名 JWT。可按框架持久化其元数据/值，但业务 Resource Server 不通过数据库在线查询 AT 状态；撤销的最坏传播上界由 BFF freshness 检查和 AT 到期共同限制。

Refresh Token 轮换必须在单个事务中：

1. 对 Authorization/Token family 行加数据库行锁；
2. 再次检查用户账号状态、`credentials_version`、全局 `security_epoch`、客户端状态和 `config_version`；
3. 原子标记旧 Token 已使用并检查是否重复使用；
4. 检查当前时间没有超过 family 首次授权起算的 14 天绝对期限；
5. 创建同一 family 的新 Token，但不延长 family 绝对期限；
6. 重复使用或版本不匹配时撤销整个 family，并写安全事件和退出 outbox。

### 3.9 `account_deletion_requests`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `UUID` | 主键 |
| `user_id` | `UUID` | 每个用户最多一个活动申请 |
| `requested_at` | `TIMESTAMPTZ` | 申请时间 |
| `execute_after` | `TIMESTAMPTZ` | 固定为申请后 30 天 |
| `cancelled_at` | `TIMESTAMPTZ` | 可空 |
| `executed_at` | `TIMESTAMPTZ` | 可空 |
| `status` | `deletion_status` | 非空 |
| `hold_until` | `TIMESTAMPTZ` | 可空；管理员暂缓最长 90 天 |
| `held_by_user_id` | `UUID` | 可空；唯一管理员 |
| `hold_reason` | `TEXT` | 可空；管理员内部依据，仅供管理和审计使用 |
| `hold_public_summary` | `VARCHAR(500)` | 可空；暂缓时展示给用户的安全摘要 |

申请、取消、暂缓、释放和执行都必须写安全事件。暂缓到期自动回到 `PENDING`，不能成为无限期保留开关。执行时先撤销所有会话和授权，再删除凭据与个人资料，最后将 `users.account_status` 设为 `DELETED`、申请状态设为 `EXECUTED`。

S5（2026-10-04）管理员详情以保留身份为读取基础，密码凭据可选LEFT JOIN；凭据被删除不代表用户UUID不存在。无凭据时状态/改密时间/锁定时间为null，既有当前用户名仍沿用保留规则、显示名“已注销用户”，不重建个人资料/凭据。终态只读不推进资格/写事件，写操作保持终态拒绝；18项数据库/MockMvc/模板验证和删除未提交时的受控独立读取通过，非多次列表/详情统一快照。见[English](../implementation/admin-tombstone-views.md)／[中文](../zh-CN/implementation/admin-tombstone-views.md)。

### 3.10 审计与事件

`admin_audit_events` 是不可修改的管理员操作日志：

```text
id, occurred_at, actor_user_id, actor_session_id,
action, target_type, target_id, request_id,
source_ip_prefix_hash, result, reason, details_json
```

`security_events` 保存登录、恢复、凭据变更、Token 重用、账号状态等安全事件：

```text
id, occurred_at, user_id, event_type, severity,
request_id, source_ip_prefix_hash, client_id, details_json
```

`details_json` 必须使用字段白名单，不能写入密码、恢复码、客户端密钥、Authorization Code、Token、Cookie 或完整 IP。

`outbox_events` 与业务事务同库提交：

```text
id, aggregate_type, aggregate_id, event_type,
payload_json, created_at, available_at, processed_at,
attempt_count, last_error_code
```

第一版事件至少包括：

```text
USER_FROZEN
USER_UNFROZEN
USER_DELETION_PENDING
USER_DELETED
CREDENTIALS_CHANGED
GLOBAL_LOGOUT_REQUESTED
AUTHORIZATION_REVOKED
```

每个 BFF 的发送状态可另存 `outbox_deliveries`。失败重试必须幂等，不能回滚已经完成的账号冻结或退出。

### 3.11 `signing_key_metadata`

数据库只保存 `kid`、算法、公钥指纹、启用时间、停签时间和公钥移除时间。私钥内容、文件路径和解密密码都不进入数据库或网页管理员 API。

### 3.12 `system_security_state`

系统只保留一条当前安全状态：

```text
id = 1
security_epoch
updated_at
change_reason
```

`security_epoch` 是不可预测的随机 128 bit 以上值或等价高熵标识。每个 SSO 会话和 Authorization 在创建时保存当前值；读取会话、授权和刷新时必须匹配。灾难恢复、全量强制重新登录或无法证明撤销事实未回退时更换该值，使恢复出来的旧凭据统一失效。

## 4. 关键事务与并发约束

2026-10-07补V15 `oauth2_id_token_hint_history(token_hash bytea PK, authorization_id FK ON DELETE CASCADE, created_at)`，原key-pepper命名域HMAC-SHA256/authorization索引，不保存rawhistoryToken。原授权save事务先保previouscurrent再save/newhint，显式id_tokenlogoutfallback仍绑定currentauth验证；删除auth清history。保留旧current升级兼容但不重造升级前已丢hint，无独立TTLcompaction，长期auth/history容量另验。配对设计与验证：[English](../implementation/refresh-logout-hint-history.md)／[中文](../zh-CN/implementation/refresh-logout-hint-history.md)，决策[ADR-0011](../adr/0011-preserve-id-token-logout-hint-association.md)。

### 4.1 注册

在一个事务内创建 `users`、首条 `usernames`、`password_credentials` 和整批 `recovery_codes`。用户名冲突由数据库唯一约束决定，接口返回通用冲突错误。

### 4.2 修改用户名

1. 锁定当前 `users` 和活动 `usernames` 行。
2. 检查 90 天冷却。
3. 插入新用户名；永久唯一索引解决并发抢占。
4. 关闭旧用户名有效期。
5. 递增 `profile_version`，写安全事件。

任何一步失败都回滚。

### 4.3 改密或恢复

在一个事务内更新密码哈希、递增 `credentials_version`、废除相关恢复凭据、撤销会话和 Authorization，并写入 outbox。事务提交后异步发送 Back-channel Logout。

2026-10-03 S1 的等效原子失效实现使用现有表与账号行锁，不给 `recovery_sessions` 新增版本字段：

- 普通/强制改密、恢复创建/完成先显式锁定目标 `users` 行，再访问用户名、密码、恢复凭据或恢复会话。维护晋升/reset 保留维护 advisory lock，再按目标用户→关联状态的顺序执行。
- 恢复创建先解析候选 UUID，取得用户锁后按同一 UUID、当前精确用户名及恢复凭据的未使用/未撤销/有效期条件重新核验；凭据消费与恢复会话创建同事务提交。
- 恢复完成先通过 token 摘要解析候选 UUID，取得用户锁后重新检查同一用户的恢复会话未消费、未到期和账号可用，再修改密码。锁等待后重新取得时间；候选读取不是认证成功。
- 普通/强制改密及晋升/reset 在更新凭据的同一事务中设置全部未消费恢复会话的 `consumed_at`，并撤销普通恢复码和人工恢复凭据。恢复先创建则后续改密将其失效；改密先提交则旧恢复凭据不能再创建会话。
- 恢复完成消费该用户全部待完成恢复会话，仍保留同批其他未使用普通恢复码，沿用既定恢复政策；不把普通改密的整批废弃规则套用到自助恢复。

`PasswordRecoveryInvalidationIntegrationTest` 的28项验证上述受控锁/回滚/审计；随后S3注销锁序另有§4.6证据，不等于全部生命周期锁图或生产验收。

### 4.4 唯一管理员

授予管理员前必须：

- 在维护模式中执行；
- 锁定目标用户；
- 确认目标为 `ACTIVE`，且没有有效的 `PENDING`/`HELD` 注销申请；有申请则拒绝，用户先自行取消后再重试，不能由提升隐式取消（[ADR-0002](../adr/0002-require-deletion-cancellation-before-admin-promotion.md)）；
- 确认当前不存在其他 `is_sso_admin = true`；
- 依赖唯一部分索引作最终保证；
- 记录运维审计事件。

授予或转移管理员身份时必须同时撤销目标用户全部既有会话和 Authorization。目标只能重新输入密码建立 `admin_session=true` 的短会话；普通或记住设备会话不能因数据库角色变化自动获得管理权限。所有管理员端点同时验证当前数据库角色和会话的 `admin_session=true`。

网页 API 不提供授予、转移或移除管理员身份的端点。

注销条件在授予事务内、任何身份/密码变更前复核，并与注销申请、取消和执行按一致锁序串行化。注销申请先提交则提升拒绝且不改变角色、凭据、申请或既有安全状态；提升先提交则后续注销申请按现有管理员禁止注销规则拒绝。已取消等终态历史不阻止符合其余条件的账号提升。

2026-10-04 S4已实现：维护advisory lock→ACTIVE用户→有效PENDING/HELD请求→用户名/凭据及关联安全状态。拒绝`DELETION_CANCELLATION_REQUIRED`，截止时间不改变有效状态，释放暂缓非取消。完整行快照/受控等待/实际SQL审计失败验证无变更拒绝与原子回滚，25数据库+3命令fixture及全量255/176通过。真实交互/浏览器/生产待验，见[English](../implementation/admin-promotion-deletion.md)／[中文](../zh-CN/implementation/admin-promotion-deletion.md)。

### 4.5 客户端配置变化

S2于2026-10-05扩大23项DB/MockMvc真实wait/双向editrotationenable-disable/连续rotation/实际BasicOAutherror认证及auditoutboxidempotencySQL失败rollback，无新生产变更。name/rotation先metadata再读最新RegisteredClient，enable仅metadataUPDATE不写Secret；直接repository/seeder/新授权签发竞争不在该范围。见[English](../implementation/client-secret-concurrency.md)／[中文](../zh-CN/implementation/client-secret-concurrency.md)。

- 禁用客户端、缩小 scopes/audience 或更换 Client Secret：增加 `config_version` 并撤销该客户端现有 Authorization/RT/BFF 会话。
- 合作方扩大 scopes：增加 `config_version`，旧 consent 不自动覆盖新 scope，下次授权必须重新确认。
- 只修改展示名称：增加 `profile_version` 或审计版本即可，不强制退出。
- 修改 Redirect/Post Logout/Back-channel URI：只影响之后的新请求；若变更源于安全事件，应同时禁用客户端并撤销现有授权。

### 4.6 注销锁序与批次

2026-10-04 S3将注销申请/取消、管理员hold/release、expiry和execute统一user→request→关联状态。管理员只先无锁读候选owner，锁user后按同request/owner重检，不把候选读当授权；等锁后重检状态和hold期限。直接execute须owned/PENDING/due，错归属/取消/held/notdue不删。

用户确认[ADR-0006](../adr/0006-user-first-deletion-batch-locking.md)：整批事务原子保持，不逐条提交；候选有限无request锁读，distinct user按PostgreSQL UUID稳定顺序SKIP LOCKED先取，再request锁/重检。定时expiry+pending预选并集一次取user，phase1后不加新user。busy候选后续重跑，实际count不算跳过。job仍advisory单执行者、定时每阶段100，failure回滚全部status/credentials/version/audit/event/outbox。

S3阶段24新DBrace及40相关/强制227server+176starter无失败跳过；只证明测到的注销顺序/批次，非全系统无死锁、多实例live/容量验收。S4随后实现见§4.4，S5见§3.9，S6见§3.4，详见[英文并发契约](../implementation/deletion-concurrency.md)／[中文](../zh-CN/implementation/deletion-concurrency.md)。

## 5. PostgreSQL 短期状态表

| 表 | 用途 | 关键约束与建议保留期 |
|---|---|---|
| `rate_limit_buckets` | 注册、登录、恢复的 IP/账号固定窗口计数 | `(scope, key_hash, window_start)` 主键；到窗口结束后可清理 |
| `captcha_challenges` | ALTCHA challenge 与已用状态 | `purpose` 绑定用途；5 分钟过期；条件更新保证只消费一次 |
| `recovery_sessions` | 受限恢复流程 | token 只存 HMAC 摘要；10 分钟过期；条件更新保证只消费一次 |
| `registration_idempotency` | 匿名注册重试去重 | UUID 幂等键、请求 HMAC 摘要和结果用户；24 小时过期；不保存一次性恢复码明文 |

原始用户名、IP 或 token 不进入短期表键，统一使用带独立 pepper 的 HMAC-SHA-256 摘要。限流计数使用 PostgreSQL UPSERT 原子递增，不能先查再写；消费 challenge 或恢复会话使用条件更新。短期状态写入失败时，注册、恢复和需要 CAPTCHA 的登录全部失败关闭，不能绕过安全控制。

定时清理任务每 5 分钟运行一次，通过 transaction advisory lock 保证集群内单执行者，并对每张表分批删除过期行。清理延迟不改变安全判定，因为业务查询仍必须检查有效期。

## 6. 保留与清理初始值

最终保留期上线前确认，第一版先采用：

| 数据 | 初始保留 |
|---|---|
| 历史用户名 | 永久 |
| UUID 墓碑 | 永久 |
| 已使用/废除恢复码摘要 | 90 天 |
| 已撤销 Authorization 元数据 | Token 最长寿命后再保留 30 天 |
| 普通安全事件 | 180 天 |
| 管理员审计事件 | 1 年 |
| Outbox 成功记录 | 30 天 |
| Outbox 失败记录 | 问题关闭后 90 天 |

清理任务按主键分页，具备幂等性，并为删除数量和失败次数输出指标。任何保留期调整都必须先评估运营追溯、隐私最小化和备份中的残留时间。

## 7. 迁移规则

- 第一版固定使用 Flyway，不同时维护第二套迁移工具。
- 每次迁移先在与生产相同主版本的 PostgreSQL 上验证。
- 不允许应用自动执行不可回滚的大表破坏性迁移。
- 枚举增加值、索引创建和数据回填要拆分为可观察步骤。
- 生产升级前备份；迁移失败时应用不得以不完整 schema 启动。
- Spring Authorization Server 官方 JDBC 示例只作为映射参考；正式 schema 必须满足本文件中的摘要存储、Token family、审计和 outbox 约束。
