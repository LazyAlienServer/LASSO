# LAS SSO 部署与运维手册

- 状态：第一版实施规格
- 规模假设：约 1,000 用户，常态并发 1–2，单实例可接受
- 依赖：[总纲](../../LAS_SSO_DESIGN.md)、[安全与恢复](../architecture/security-and-recovery.md)
- 可执行步骤：[SSO 部署指南](sso-deployment-guide.md)

## 1. 责任分界

| 角色 | 负责 | 不负责 |
|---|---|---|
| 服务器运维 | 主机、反向代理、部署、数据库、Secret、签名私钥、备份、维护命令 | 业务角色、Minecraft 白名单 |
| SSO 网页管理员 | 用户冻结/恢复、会话、OIDC Client、客户端密钥、审计查看 | JWT 签名私钥、数据库、备份、部署 |
| LASLOG 管理员 | LASLOG 权限、Minecraft 绑定/白名单/封禁/准入 | SSO 密码、OIDC 签名 |
| AlienCommon 管理员 | 稿件和内容权限 | SSO 密码、LASLOG 权限 |

第一版唯一 SSO 网页管理员可以与服务器运维是同一个自然人，但必须使用不同的权限入口、凭据和审计记录。

## 2. 环境

至少建立：

```text
local
test
production
```

每个环境独立：

- Issuer；
- PostgreSQL 数据库和账号；
- JWT 签名密钥；
- OIDC Client ID/Secret；
- Cookie 名称或 host；
- 会话加密/恢复码 pepper 等 Secret；
- 回调和退出地址。

禁止用生产私钥为测试环境签名，禁止把生产数据库副本直接放到开发环境。确需使用生产数据排障时先匿名化。

## 3. 第一版部署拓扑

2026-10-06隔离single-hop Nginx/TLS/实际Secure __HostCookie与HTTPSCode/Token/UserInfo/revoke/Back-channel闭环已验，客户端Forwarded/Prefix扰乱callback的部署输入已修。使用完整headerinclude和私有appsDB，不将framework当sendertrust。记录[English](tls-proxy-acceptance.md)／[中文](../zh-CN/operations/tls-proxy-acceptance.md)，publicCA续期/生产DNS防火墙/CDN多级等仍按实际拓扑验收。

```text
Internet
  -> Reverse Proxy / TLS
       -> LAS SSO Spring Boot (single instance)
            -> PostgreSQL
            -> read-only mounted signing key
```

要求：

- PostgreSQL 只监听内网/本机，不开放公网。
- 应用以非 root/非 Administrator 的专用系统账号运行。
- 签名私钥只允许该服务账号读取，应用不能修改私钥目录。
- 数据、日志和密钥使用不同持久化路径。
- 反向代理到应用也优先使用受限内网；代理只信任明确来源的 `Forwarded`/`X-Forwarded-*`。
- 应用根据可信代理头构造外部 HTTPS 地址，不能接受任意客户端伪造 host/scheme。

第一版不要求多实例。未来横向扩展前必须先证明会话、Refresh Token 锁、outbox 和密钥加载不依赖进程内状态。

## 4. 配置与 Secret

实现阶段使用稳定的配置前缀。逻辑配置至少包括：

```text
LAS_SSO_ISSUER
LAS_SSO_PUBLIC_BASE_URL
LAS_SSO_DATABASE_URL
LAS_SSO_DATABASE_USERNAME
LAS_SSO_DATABASE_PASSWORD
LAS_SSO_SIGNING_KEY_PATH
LAS_SSO_SIGNING_KEY_PASSWORD
LAS_SSO_RECOVERY_PEPPER
LAS_SSO_TRANSIENT_SECURITY_PEPPER
LAS_SSO_SESSION_ENCRYPTION_KEY
LAS_SSO_TRUSTED_PROXY_CIDRS
```

名称可在实现时适配 Spring 属性，但必须有一份生产配置清单。

规则：

- Secret 使用部署平台 Secret 文件/凭据存储，不写入 Git、镜像、Compose 示例默认值或 systemd/任务计划日志。
- 环境变量可能被进程检查工具读取；支持 Secret 文件时优先文件挂载。
- 应用启动日志只打印配置来源和脱敏后的 host，不打印 URL 中的密码。
- 同一个秘密不能跨数据库、客户端、Cookie 加密、恢复码摘要和签名用途复用。
- 当前设计不需要 SMTP 配置；任何 SMTP 变量都不是第一版启动前置条件。

## 5. 启动门禁与健康检查

从当前开发基线到目标生产的责任与执行顺序见[上线计划](../zh-CN/implementation/production-launch-plan.md)／[English](../implementation/production-launch-plan.md)。其中P02逐项核对本节规则与实际代码，P06–P10落实环境/Secret/DB/监控/灾备，P15–P19完成最终候选演练和生产开放；本手册写出的控制不能自动记为已实现。

### 5.1 启动时必须失败关闭

以下任一条件不满足时，生产应用不得进入 Ready：

- Issuer 仍为 `.example`、`localhost` 或 HTTP；
- 找不到签名私钥、密码错误、`kid` 重复或证书/算法不匹配；
- PostgreSQL schema 版本与应用不兼容；
- 无法读取 PostgreSQL；
- PostgreSQL 短期安全状态迁移缺失或原子读写检查失败；
- 存在超过一个管理员；
- Cookie Secure/可信代理配置与生产不一致；
- 客户端存在生产通配 Redirect URI；
- 关键 Secret 使用示例默认值或强度不足。

首次部署尚无管理员时，应用可以启动为维护状态但不能开放注册/OIDC 登录；管理员 bootstrap 完成并重启/重新检查后才 Ready。

### 5.2 健康端点

建议：

```text
/actuator/health/liveness
/actuator/health/readiness
```

- Liveness 只判断进程是否卡死，不因 PostgreSQL 短故障无限重启。
- 预期启动门禁涵盖数据库、签名 key/schema和关键配置；当前readiness运行期实际为`readinessState,db`。2026-10-06旧配置DBdown仍200已修，.5实际200→503→200/liveness始终200，[English记录](capacity-readiness-2026-10-06.md)／[中文](../zh-CN/operations/capacity-readiness-2026-10-06.md)。这不表示上节所有生产config/admin/schema/key验证器已实现，启动失败校验与运行期health分别核。
- 健康详情只对本机/监控网络开放，公网只返回粗粒度状态。

## 6. 首次上线

2026-10-06[管理员维护隔离闭环](../zh-CN/operations/administrator-maintenance-acceptance.md)／[English](administrator-maintenance-acceptance.md)已验证真PowerShell/JARhash/WindowsPTY hiddenbootstrap、临时密码强制改密/十个80bit恢复码展示download、offline reset及旧passwordSessionToken拒。并修撤销SSOCookie新OIDC请求丢失；不代表production终端/proxy/SecureCookie/离线实际保管或已有用户晋升注销流程已验。

1. 确定正式域名、TLS 证书和不可随意变更的 Issuer。
2. 建立独立 PostgreSQL 与最小权限账号。
3. 生成持久 RSA 签名 key、稳定 `kid` 和加密离线备份。
4. 注入所有生产 Secret。
5. 执行数据库迁移。
6. 在未开放公网注册的维护状态启动。
7. 执行管理员 bootstrap，设置临时密码。
8. 管理员首次登录改密，生成并离线保存恢复码。
9. 登记 LASLOG、AlienCommon 客户端，分别交付一次性 Client Secret。
10. 运行 Discovery、JWKS、登录、刷新、当前退出、全局退出和 Back-channel Logout 冒烟测试。
11. 执行一次数据库和密钥恢复演练。
12. 配置监控/告警后再开放注册。

任何步骤失败都记录实际状态，不重复运行可能生成第二把秘密的命令。

## 7. 发布与回滚

### 7.1 发布前

- 固定应用、JDK、PostgreSQL 和前端构建版本；
- 通过 [测试计划](../implementation/test-plan.md) 的发布门禁；
- 检查依赖漏洞和许可证；
- 生成数据库备份并验证可读取；
- 检查当前 outbox 无持续失败；
- 确认回滚版本仍兼容将要执行的 schema。

### 7.2 发布顺序

1. 进入短维护窗口或从代理摘流量。
2. 完成备份。
3. 执行向后兼容迁移。
4. 部署新应用。
5. 验证 readiness、Discovery、Issuer、JWKS 和管理员登录。
6. 验证两个 BFF 冒烟登录和退出。
7. 恢复流量，观察 30 分钟关键指标。

### 7.3 回滚

- 应用回滚优先，不自动回滚已提交的数据迁移。
- 破坏性 schema 变化必须使用 expand/migrate/contract 多阶段发布。
- 如 Token/Claims 契约变化造成 BFF 故障，先恢复兼容签发，再回滚适配库。
- 回滚不能更换 Issuer、丢失当前签名 key 或自动清空 Authorization。

## 8. 备份与恢复

以下是待生产确认的第一版默认目标：

```text
RPO: 24 小时
RTO: 4 小时
```

### 8.1 PostgreSQL

- 每日加密全量/逻辑备份，保留 30 天；
- 每周备份保留 12 周；
- 备份文件与生产数据库不在同一磁盘/主机；
- 备份前后记录时间、数据库版本、schema 版本、大小和校验和；
- 备份读取账号不得是应用账号；
- 每季度在隔离环境做完整恢复演练。

若需要把 RPO 降到 24 小时以下，再增加 WAL/PITR；第一版不把未经演练的复制称为备份。

### 8.2 JWT 签名私钥

- 当前私钥和仍需验证旧 Token 的历史私钥做加密离线备份；
- 备份包含 `kid`、算法、创建时间和公钥指纹，但密钥密码分开保存；
- 恢复后用公钥指纹验证，不通过实际生产签发试错；
- 私钥备份访问需要服务器运维权限，不通过网页管理员提供。

### 8.3 PostgreSQL 短期状态

`rate_limit_buckets`、`captcha_challenges`、`recovery_sessions` 和 `registration_idempotency` 随数据库一起备份，但不属于必须恢复的永久身份事实。恢复到旧快照后必须清空这些表，使全部短期登录/恢复/CAPTCHA 事务失效。正常运行时每 5 分钟由单个实例抢 advisory lock，并按表每批最多清理 1000 条过期行；监控过期行数量和清理失败。

### 8.4 恢复验收

2026-10-06已提供非Web、禁调度、expectedepoch与交互确认的可执行屏障及[隔离真实演练](../zh-CN/integration/development-delivery-2026-10-06-1.md)。执行[恢复操作流程](../zh-CN/operations/restore-epoch-barrier.md)／[English](restore-epoch-barrier.md)；保users/clients/consent/audit/deletion与outbox，释放pendingclaims，撤销RT历史/删除SpringSession和短事务。命令不生成key或验证外部writer已停，以下生产备份/key/consumer步骤仍须完成。

从备份恢复生产身份库时，不能证明备份时间点之后发生的退出、改密、RT 使用和撤销事实仍然存在，因此第一版采用“恢复即全局安全重置”：

1. 生成新的随机 `security_epoch`；
2. 将恢复出的全部 SSO 会话、Authorization、Refresh Token family 和未完成登录/恢复事务标记失效或清空；
3. 生成新的签名 key/`kid` 并停止使用恢复出的旧私钥签发，BFF 临时拒绝灾难前旧 `kid`；
4. 清空 `rate_limit_buckets`、`captcha_challenges`、`recovery_sessions` 和 `registration_idempotency`；
5. 清理 LASLOG、AlienCommon 的全部 BFF 本地会话；
6. 恢复服务后要求所有用户重新登录。

ADR-0007（用户2026-10-05确认）资格query的stateEpoch复用新的security_epoch；旧备份stateVersion可以回退，不跨epoch比counter/changedAt。开放前完成new epoch与本地cache/inflight隔离屏障，consumerfresh对账建立currentgeneration、废旧资格/本地证据并拒旧epochlate reply，不能随不同响应往返切换。server受控restore-shape测试非本节真实演练，见[English](../implementation/service-access-epoch-contract.md)／[中文](../zh-CN/implementation/service-access-epoch-contract.md)。

该规则优先于“尽量保留登录状态”。如果未来要在灾难恢复后保留会话，必须先建立独立、未随备份回退的撤销账本并完成专项设计。

恢复演练至少验证：

- 用户 UUID、当前/历史用户名和密码可登录；
- 唯一管理员仍唯一且恢复码摘要存在；
- OIDC Client 配置和 Client Secret 摘要正确；
- 恢复前所有 Authorization/会话/RT 都因新 `security_epoch` 失效；
- 新 JWKS 使用新 `kid`，灾难前旧 Token 不能继续访问 BFF；
- 注销等待任务、outbox、审计和保留任务继续运行；
- 两个 BFF 能完成新登录和全局退出。

## 9. 监控与告警

参考BFF可选持久撤销profile的queueDB/key是消费端独立资源，不属于SSO身份schema。监控pendingoldest/leasedexpired/dead/categoricalerrors，成功清Tokencipher，dead保encrypted材料须运维显式恢复，无自动replay/cleanup/multikeyrotation。先排空旧key任务再更换，DBkey分离备份；未验灾备continuity/presubmitcrash不称保证。契约见[English](../integration/reference-durable-revocation.md)／[中文](../zh-CN/integration/reference-durable-revocation.md)。

### 9.1 指标

2026-10-07后续.7实际Grafana12.2.0privateDatasource/import与Viewer桌面手机展示/write403已验，两mode5分钟TLSmixed发现并修refreshlogoutIDhint400（V15HMAC历史关联）。[English](grafana-mixed-load-2026-10-07.md)／[中文](../zh-CN/operations/grafana-mixed-load-2026-10-07.md)记录12,598operation/实际refresh与内存采样限制；这不代生产Grafanaauthretention/oncall或小时soak。

以下仍是完整目标清单；2026-10-06 .6已实现核心fixedcounter/hashTimer/cachedDBgauge、独立port/credential/privatePrometheusscrape、7rule和dashboardfile/10query，真实3类firingresolved到authwebhook。实际名称/语义/部署与限制：[English](monitoring-and-alerting.md)／[中文](../zh-CN/operations/monitoring-and-alerting.md)。未包registrationreject/recovery/tokenissue/cleanup/diskbackupclock全部、actualGrafana/oncallproduction；旧.5本机attach观察保历史。base仍health/info，monitoringprofile optin，health迁管理端口，禁止公网proxy管理口。

至少采集：

```text
http_requests_total / duration
login_success_total
login_failure_total{reason}
registration_success_total
registration_rejected_total{reason}
password_recovery_total{result,type}
admin_login_failure_total
token_issue_total{grant_type}
token_refresh_total{result}
refresh_token_reuse_total
active_sso_sessions
backchannel_logout_total{client,result}
outbox_pending / oldest_age / retry_total
database_pool_active / pending
transient_state_operation_errors
transient_state_expired_rows
password_hash_duration
jvm_memory / gc / threads
clock_offset
```

标签不能包含用户名、UUID、IP、Token、`sid` 或高基数 `request_id`。

### 9.2 初始告警

| 条件 | 级别 |
|---|---|
| Readiness 连续 2 分钟失败 | 紧急 |
| 登录成功率在有流量时异常下降 | 高 |
| 管理员登录连续失败或账号锁定 | 高 |
| `refresh_token_reuse_total > 0` | 高 |
| 签名 key 加载失败/未知 `kid` 激增 | 紧急 |
| Outbox 最老事件超过 5 分钟 | 高 |
| 单客户端 Back-channel Logout 连续失败 5 次 | 中/高 |
| PostgreSQL 磁盘剩余低于 20% | 高 |
| 最近 26 小时无成功数据库备份 | 高 |
| 系统时钟偏差超过 30 秒 | 高 |
| Argon2 p95 超过 1 秒 | 中 |

在实际基线建立后调整，不能为了消除噪声直接关闭安全告警。

当前repo7rules无clientId标签，Back-channel按聚合receipt/queue观测，不能将上述“单客户端5次”当已实现rule。快照失败/过期值应invalid，不能当队列空或正常；consumer与生产operator通知须另核。实际规则/1m2m窗口和演练见[监控记录](monitoring-and-alerting.md)。

## 10. 日志与保留

日志分类：

```text
application
access
security_event
admin_audit
maintenance
```

默认保留：

- Application/Access：30 天；
- Security Event：180 天；
- Admin Audit：1 年；
- Maintenance：1 年；
- 备份操作清单：1 年。

日志使用 UTC、结构化 JSON 和 `request_id`。访问日志不记录请求体、查询串秘密、Cookie、Authorization header 或响应 Token。日志访问权限小于数据库管理员范围，审计日志不能由网页管理员删除。

## 11. 定时任务

| 任务 | 建议频率 |
|---|---|
| 执行到期注销 | 每小时 |
| 清理过期会话/Authorization | 每小时 |
| Outbox 发送与重试 | 持续/每分钟 |
| 清理成功 outbox | 每日 |
| 清理安全事件/审计（按策略） | 每日 |
| 检查客户端密钥到期 | 每日 |
| 检查签名 key 轮换时间 | 每日 |
| 数据库备份 | 每日 |
| 备份完整性验证 | 每日轻量、每季度恢复 |

任务使用数据库锁，保证单实例或未来多实例下都不会重复产生有害动作；业务动作本身仍需幂等。

## 12. 日常 Runbook

### 12.1 管理员初始化

使用[安全与恢复设计](../architecture/security-and-recovery.md#6-唯一管理员初始化)中的 Java 维护命令。完成后记录：

- 管理员 UUID；
- 命令审计事件 ID；
- 临时密码到期时间；
- 首次改密完成时间；
- 恢复码已离线保存的确认，不记录恢复码本身。

### 12.2 管理员忘记密码

1. 优先使用离线恢复码。
2. 同时丢失时安排维护窗口并备份。
3. 摘除 Web 流量。
4. 运行 `scripts/reset-admin-password.ps1`，核对命令显示的唯一管理员用户名与 UUID 后确认。
5. 输入并二次确认新的最终密码；命令失败时不要因终端超时盲目重跑。
6. 恢复 Web，管理员使用新密码登录并重新生成恢复码。
7. 验证旧会话、旧 Refresh Token、旧恢复码全部无效。
8. 审查近期管理员登录和 Client 变更记录。

### 12.3 普通用户人工恢复

- 只在管理页签发短期凭据；
- 原因和核验摘要必填；
- 不通过数据库或维护脚本替普通用户直接改密；
- 完成后确认全局退出事件成功。

### 12.4 OIDC Client Secret 轮换

1. 与目标 BFF 安排短维护窗口并暂停新登录/刷新。
2. 管理员执行轮换；系统原子替换唯一活动密钥、增加客户端配置版本，旧密钥立即失效并撤销现有授权/会话。
3. 通过安全渠道立即部署一次性显示的新密钥到目标 BFF。
4. 重启/热加载 BFF，完成登录、刷新和退出冒烟测试后恢复流量。
5. 检查旧密钥认证失败、新密钥正常。
6. 保留审计 ID，不保存明文 Secret；若明文响应丢失，只能再次轮换。

### 12.5 常规签名 key 轮换

1. 在离线/受控环境生成新 RSA key 和唯一 `kid`。
2. 备份新私钥和指纹。
3. 部署为“发布但暂不签名”，JWKS 同时出现新旧公钥。
4. 确认所有 BFF 能刷新 JWKS 并识别新 `kid`。
5. 切换新 key 签名。
6. 旧公钥至少保留到最长相关 Token/Logout Token 过期并加安全余量。
7. 停止发布旧公钥；私钥按保留策略归档或销毁。

不得通过网页管理员上传或切换签名私钥。

### 12.6 签名私钥泄漏

1. 将 SSO 摘流量并保全证据。
2. 生成全新 key 和 `kid`，切换签名。
3. 撤销全部 SSO 会话、Authorization、Refresh Token 和 BFF 会话。
4. 通知 BFF 临时拒绝泄漏 `kid`，即使旧 Token 尚未过期。
5. 恢复服务并强制全部用户重新登录。
6. 轮换可能同机泄漏的数据库、客户端和 Secret。
7. 完成影响分析与事件记录。

## 13. 故障处理

### 13.1 PostgreSQL 不可用

- Readiness 失败，停止新登录/注册/刷新/管理写操作；
- 不从短期状态表猜测或重建永久账号状态；
- 已有 BFF 本地会话能否继续由业务应用的本地策略决定；
- 数据库恢复后检查事务、连接池、outbox 和时钟，不自动重建账号。

### 13.2 短期状态表异常

- 注册、恢复和需 CAPTCHA 的登录失败关闭；
- 普通低风险登录只有在账号锁定计数和限流原子操作可用时才允许；
- Refresh single-flight 无法保证时拒绝并发刷新，避免误触重用检测；
- 检查 V5 迁移、表膨胀、索引、锁等待和清理任务；恢复后让所有未完成短期事务重新开始。

### 13.3 SSO 不可用

- BFF 不删除已有本地身份映射；
- `auth_fresh_until` 尚未到期且现有 Access Token 可被本地验证的请求可按业务策略继续；
- freshness 到期后若不能刷新，受保护请求返回身份服务不可用并停止把本地 Cookie 视为有效身份；
- 不能新登录、刷新到期 Token、修改 SSO 账号或验证新授权；
- 页面显示“身份服务暂不可用”，不能显示成封禁或密码错误。

### 13.4 Back-channel Logout 积压

- SSO 撤销事实仍以 PostgreSQL 为准；
- 持续重试临时失败并监控最老事件；
- BFF 在每个受保护请求检查 `auth_fresh_until`，并在 Access Token 最多 15 分钟的刷新边界再次确认授权；
- 超过 15 分钟仍有活动会话时升级告警并人工调用目标 BFF 的受保护清理工具；
- 不把失败事件直接标记成功以清空告警。

## 14. 容量与维护

第一版不需要复杂自动扩容，但上线前至少做：

- 50 并发登录短压测；
- 20 requests/s 混合登录/刷新/会话查询 10 分钟；
- PostgreSQL 连接池上限与主机资源匹配；
- Argon2 并发下内存上限验证；
- Outbox/BFF 故障时重试不会压垮自身；
- 磁盘按一年审计、180 天安全事件和 30 天备份估算并保留余量。

每月检查依赖安全更新，每季度做恢复演练，每年常规签名 key 轮换。高危漏洞和密钥泄漏不等待固定维护周期。

## 15. 上线前必须替换/确认

```text
正式 Issuer 与三个域名
LASLOG/AlienCommon 精确 Redirect、Logout、Back-channel URI
生产服务器与反向代理
Argon2 基准参数
管理员 45 分钟/10 小时会话默认值
人工恢复核验规则
RPO/RTO 与备份保留期
审计/安全事件保留期
JDK、Spring、PostgreSQL 固定版本和支持周期
未来合作网站审批与隐私条款
```
