# LAS SSO 安全、密码与恢复设计

- 状态：第一版实施规格
- 依赖：[总纲](../LAS_SSO_DESIGN.md)、[数据模型](identity-and-data-model.md)、[API 契约](api-contract.md)
- 明确接受的限制：第一版没有邮箱验证和 MFA

## 1. 保护目标与信任边界

### 1.1 核心资产

按影响从高到低：

1. JWT 签名私钥；
2. 生产数据库、备份和数据库凭据；
3. 管理员账号、恢复码和服务器维护权限；
4. OIDC 客户端密钥；
5. 用户密码哈希、恢复码摘要；
6. SSO/BFF 会话、Authorization Code、Refresh Token；
7. 审计日志与安全事件；
8. 用户名、显示名、头像等公开资料。

### 1.2 信任边界

```text
不可信浏览器
  -> 反向代理
  -> SSO Web/API
       -> PostgreSQL
       -> 持久化签名私钥
       -> 第一方 BFF Back-channel Logout

服务器运维终端
  -> Java 维护命令
       -> 同一 PostgreSQL / 审计服务
```

- 浏览器、代理转发头、所有自由文本和合作方回调地址默认不可信。
- PostgreSQL 不能暴露公网。
- BFF 不因属于 LAS 自营服务就跳过 Token、Issuer、Audience 或 Logout Token 验证。
- 网页 SSO 管理员和服务器运维是两个权限域；网页管理员不能读取签名私钥、数据库密码、备份或部署 Secret。

## 2. 主要威胁与控制

| 威胁 | 主要控制 |
|---|---|
| 撞库、弱密码 | 12 字符最低、泄漏密码拒绝、Argon2id、用户/IP 限流、风险 CAPTCHA |
| 批量注册 | 注册始终 ALTCHA、IP/时间窗口限流、数据库唯一约束、人工冻结 |
| 账号枚举 | 登录统一错误和近似耗时；恢复错误不返回账号状态细节 |
| 会话窃取/固定 | HTTPS、主机级 Secure/HttpOnly Cookie、登录后换 Session ID、绝对过期 |
| CSRF | CSRF Token、Origin/Referer 校验、SameSite Cookie |
| XSS 窃取身份 | Token 不进浏览器、严格 CSP、输出编码、禁止不可信 HTML |
| Authorization Code 注入 | `state`、`nonce`、PKCE S256、精确 Redirect URI、单次 code |
| Refresh Token 重放 | 服务端保存、轮换、single-flight、Token family 重用检测 |
| JWT 混淆 | 固定 Issuer/Audience/算法/Token 用途；Logout Token 显式 `typ` |
| 开放跳转 | `returnTo` 只允许站内相对路径；退出后地址精确登记 |
| 管理员接管 | 短会话、近期密码、离线恢复码、维护命令、完整审计 |
| 数据库泄漏 | 慢哈希、秘密摘要、最小权限、加密备份、日志脱敏 |
| 签名私钥丢失/泄漏 | 受限挂载、离线加密备份、轮换 Runbook、启动失败关闭 |
| Back-channel Logout 伪造/重放 | 签名验证、`aud`/`sid`/`events`、短 `exp`、`jti` 重放缓存 |
| 短期安全状态写入故障导致绕过 | 注册/恢复及需 CAPTCHA 的登录失败关闭，不因数据库异常无限放行 |
| 大小写近似用户名冒充 | 内部只认 UUID v7；UI 显示精确大小写；恢复和审计按精确用户名与 UUID 核对 |

## 3. 密码

### 3.1 用户规则

- 长度 12–128 个 Unicode 字符；服务端在规范化前后都限制输入字节数，防止超长 DoS。
- 允许空格和密码短语，不强制大小写、数字、符号组合。
- 不静默裁剪空格，不做会改变用户意图的 Unicode 规范化。
- 拒绝与当前用户名相同、常见密码和已知泄漏密码。
- 不设置周期改密；只有用户主动、恢复、泄漏处置或管理员初始化时要求改密。
- 改密后不允许继续使用相同密码。

第一版至少随应用部署本地常见/泄漏密码拒绝表。未来若调用 k-Anonymity 密码泄漏查询，只发送哈希前缀，超时不能把用户密码或完整哈希发给外部服务。

### 3.2 哈希

首选 Argon2id，初始最低参数：

```text
memory:      19 MiB (m=19456)
iterations:  2
parallelism: 1
salt:        每个密码独立随机生成
```

上线前必须在生产同规格硬件做基准测试：

- 单次验证应低于 1 秒；
- 目标区间先设为约 250–750 ms；
- 测试并发登录和恶意请求下的 CPU/内存上限；
- 若提高参数，以“不使认证端点成为容易触发的资源耗尽点”为边界；
- 参数编码进哈希；用户下次成功登录时可以透明升级旧参数。

如 Java 生产依赖无法可靠提供 Argon2id，才使用 BCrypt。BCrypt 至少 cost 10，并解决其常见 72-byte 输入限制；不得用快速 SHA-256/MD5 保存密码。

密码 pepper 是可选的纵深防护。如果启用：

- 存在数据库之外的部署 Secret；
- 不记录到日志或备份清单；
- 丢失等同所有密码不可验证；
- 泄漏后只能通过强制改密完成轮换。

第一版可先不启用 pepper，避免在缺乏 Secret 管理能力时制造不可恢复依赖。

### 3.3 常量行为

- 用户不存在时也执行固定的假密码哈希验证。
- 登录错误统一显示“用户名或密码错误”。
- 失败响应加入小幅随机抖动，但不能以长睡眠占满工作线程。
- 密码比较由 `PasswordEncoder.matches` 完成，不自行比较字符串。

## 4. 用户恢复码

### 4.1 生成与保存

- 每批生成 8–10 个恢复码。
- 每个码至少 80 bit CSPRNG 熵，使用无歧义大写字符分组显示，例如 `LAS-XXXX-XXXX-XXXX-XXXX`。
- 明文只在 HTTPS 成功页面出现一次，不进入响应日志、埋点、错误报告、剪贴板遥测或管理员界面。
- 数据库保存 `HMAC-SHA-256(server_recovery_pepper, normalized_code)`，pepper 与数据库分离。
- 页面鼓励下载/打印并离线保存，同时说明“任何得到恢复码的人都能重置密码”。

### 4.2 使用

1. 用户提交当前用户名和一个恢复码。
2. 服务端执行用户、IP、全局限流，必要时要求 ALTCHA。
3. 验证成功后原子标记该码已使用，并建立 10 分钟受限恢复会话。
4. 用户设置符合策略的新密码。
5. 系统增加 `credentials_version`，撤销全部会话、Authorization 和 Refresh Token。
6. 第一版只消耗本次使用的恢复码，当前批次其他未使用码继续有效，优先保证忘记密码用户仍有后续自助恢复能力；若用户怀疑整批码泄漏，应在登录后主动重新生成并废除旧批次。

恢复流程不直接登录完整账号，完成后必须使用新密码登录。

### 4.3 重发

用户已登录并在 15 分钟内重新输入密码后可以重发。生成新批次和废除旧批次在同一事务内完成。如果一次性明文响应丢失，服务端不能恢复旧码，用户应再次执行重发。

## 5. 普通用户人工恢复

用户同时失去密码和恢复码时，只能联系唯一 SSO 管理员。

第一版人工核验不能依赖系统不存在的邮箱。上线前必须由 LAS 运营确定最少两项可审计依据，例如：

- 账号历史用户名和大致创建时间；
- 只有账号本人合理掌握的 LASLOG/Minecraft 历史信息，由 LASLOG 管理员在业务系统核对；
- 既有社区身份与长期互动记录；
- 线下已知身份。

不得把公开资料、单一聊天账号截图或 IP 地址当成充分证明。

管理员核验后：

1. 在管理员页面填写核验依据摘要和原因；
2. 15 分钟内重新输入管理员密码；
3. 签发最长 30 分钟、单次的人工恢复凭据；
4. 通过双方已经建立的可信沟通渠道交付；
5. 用户按普通恢复接口设置新密码；
6. 系统撤销全部旧会话/授权并废除人工凭据；
7. 安全事件与管理员审计关联同一 `request_id`。

管理员不能查看或直接设置用户密码，不能查看用户恢复码，不能“登录为该用户”。

## 6. 唯一管理员初始化

### 6.1 前置条件

- 生产第一次开放注册和外部流量前完成。
- 维护命令使用和 Web 应用相同的构建产物、配置解析、`PasswordEncoder`、Repository、事务和审计服务。
- 维护模式使用 `WebApplicationType.NONE`，不启动 HTTP 监听。
- 命令获取 PostgreSQL advisory lock，防止两个初始化命令并发执行。

### 6.2 两种输入

如果目标普通账号已经存在：

```text
java -jar las-sso.jar admin bootstrap --user-id <UUID>
```

如果数据库尚无用户，维护命令可以原子创建账号并授予唯一管理员：

```text
java -jar las-sso.jar admin bootstrap --username <username>
```

第二种形式由服务端生成 UUID v7，并在成功后输出 UUID；两种形式都不得接受 `--password`。

### 6.3 临时密码

命令在可交互终端隐藏读取两次临时密码并：

- 应用完整密码策略；
- 使用与 Web 登录相同的 `PasswordEncoder`；
- 设置 `PASSWORD_CHANGE_REQUIRED`；
- 设置 `temporary_expires_at = now + 24h`；
- 设置 `is_sso_admin = true`，依赖唯一部分索引保证只有一个；
- 写维护审计记录，但不写密码；
- 只输出目标 UUID、状态和到期时间。

管理员首次登录只能访问 `/password-change-required` 和退出。成功改密后撤销临时会话，建立新管理员会话，并要求立即生成和离线保存恢复码。

禁止：

- 默认管理员密码；
- 把初始密码写进数据库迁移、配置文件、镜像、日志或 shell 历史；
- 公开的管理员提权 API；
- 直接手工更新 `is_sso_admin`。

## 7. 管理员忘记密码

恢复优先级：

1. 管理员使用离线恢复码自助重置；
2. 恢复码也丢失时，由拥有服务器和数据库 Secret 权限的运维人员执行维护命令；
3. 如果服务器运维权限、数据库访问和备份也同时丢失，系统不提供网页后门。

### 7.1 PowerShell 包装脚本

仓库提供：

```text
scripts/reset-admin-password.ps1
```

建议调用方式：

```text
powershell -File scripts/reset-admin-password.ps1
```

脚本职责仅限于：

- 定位已部署 JAR 和维护配置；
- 确认当前目标是预期环境；
- 由 Java 维护命令查询并显示数据库中的唯一管理员；
- 调用 Java 维护子命令；
- 原样传播安全的退出码。

脚本不得：

- 自己计算密码哈希；
- 接受 `-Password`；
- 把密码放入参数、环境变量、临时文件或管道；
- 直接执行 SQL；
- 输出哈希、数据库连接串或其他 Secret。

### 7.2 Java 维护子命令

包装脚本调用：

```text
java -jar las-sso.jar --spring.main.web-application-type=none \
  --las.sso.maintenance.command=admin-reset-password
```

执行前建议把 Web 实例摘出流量并确认数据库备份可用。命令必须：

1. 启动同一 Spring 容器的维护模式；
2. 查询并显示唯一管理员的用户名与 UUID，要求明确确认；
3. 隐藏读取并二次确认新的最终密码；
4. 校验完整密码策略并使用同一 `PasswordEncoder`；
5. 在一个事务中更新哈希、增加 `credentials_version`、设为 `ACTIVE`、清除临时到期时间和锁定、废除全部恢复状态、撤销全部会话和 Authorization；
6. 写入 `ADMIN_PASSWORD_RESET_BY_MAINTENANCE` 审计和全局退出 outbox；
7. 在同一数据库事务中失效该管理员的受限恢复会话和近期认证状态；
8. 输出成功状态、用户名、UUID 和审计事件 ID，不输出密码或哈希。

成功退出码为 `0`；交互、密码策略、管理员状态、数据库或事务失败均返回非零。包装脚本原样传播 Java 进程退出码。

退出码 `6` 不能自动重复执行改密；运维人员应先按审计 ID检查数据库和 outbox。

## 8. 会话安全

### 8.1 普通用户

| 类型 | 空闲期限 | 绝对期限 |
|---|---:|---:|
| 未选择记住 | 24 小时 | 14 天 |
| 选择记住 | 14 天 | 90 天 |

### 8.2 管理员

第一版默认：

```text
空闲期限：45 分钟
绝对期限：10 小时
近期认证：15 分钟
```

管理员不能选择 90 天记住会话。任何密码变更、恢复、冻结、全局退出和 `credentials_version` 改变都会使现有 Cookie 即时失效。

所有会话请求还要匹配全局 `security_epoch`。管理员端点必须同时满足数据库中当前 `is_sso_admin=true` 和会话建立时的 `admin_session=true`；授予/转移管理员身份后先撤销目标的旧会话，不能把既有 90 天普通会话原地升级为管理员会话。

需要近期认证的操作统一先调用 `POST /api/v1/me/reauthentication`。成功重新验证密码后轮换会话 ID 并更新 `last_password_auth_at`，但不自动执行此前失败的危险写操作。

### 8.3 Cookie 与浏览器

- 只使用 HTTPS；生产开启 HSTS。
- SSO Cookie 使用 `__Host-` 前缀、`Secure`、`HttpOnly`、`Path=/`、无 `Domain`。
- 默认 `SameSite=Lax`；恢复受限 Cookie 使用 `Strict`。
- 不把 Token、恢复凭据或密码放入 LocalStorage、SessionStorage、IndexedDB、URL、HTML 或 Service Worker cache。
- 登出响应清除 Cookie 的 Path、Secure 和 SameSite 属性必须与设置时一致。

## 9. 人机验证与限流

初始配置值：

| 场景 | 用户/账号维度 | IP 维度 | 后续动作 |
|---|---:|---:|---|
| 注册 | 不适用 | 5/小时、20/天 | 拒绝并 `Retry-After` |
| 登录失败 | 5/15 分钟 | 20/15 分钟 | 要求 ALTCHA |
| 登录持续失败 | 10/30 分钟 | 50/30 分钟 | 账号短锁 15 分钟/IP 拒绝 |
| 恢复凭据 | 5/小时 | 20/小时 | 要求 ALTCHA，继续失败则拒绝 |
| 管理员登录失败 | 5/15 分钟 | 10/15 分钟 | 锁 30 分钟并高危告警 |
| Client Secret 失败 | Client 10/15 分钟 | 20/15 分钟 | 临时拒绝并告警 |

要求：

- 阈值全部配置化并带指标；
- 使用 IP 前缀/哈希，避免长期保存完整 IP；
- 不因单个共享 IP 永久锁定用户；
- CAPTCHA Challenge 绑定用途、2–5 分钟 TTL、单次使用；
- PostgreSQL UPSERT 原子计数，不能“先读后加”；
- 管理员可解除误锁，但不能删除安全事件。

## 10. Web 安全基线

反向代理和应用至少设置：

```text
Content-Security-Policy:
  default-src 'self';
  script-src 'self';
  style-src 'self';
  img-src 'self' https: data:;
  connect-src 'self';
  frame-ancestors 'none';
  base-uri 'none';
  form-action 'self'

Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

如 ALTCHA Widget 需要内联样式/脚本，使用构建产物哈希或 nonce，不直接加入 `unsafe-inline`。登录、授权和管理员页面禁止被 iframe。

所有 HTML 输出转义。第一版只允许服务端默认头像资源 ID；未来开放头像上传时必须单独做 MIME、尺寸、解码重编码和存储域隔离，不能只检查扩展名。

## 11. Secret 与日志

- 生产 Secret 不提交 Git，不进入前端构建产物。
- 数据库账号按环境独立，Web 应用不使用 PostgreSQL 超级用户。
- 短期状态表只允许 SSO 数据库账号访问，不为业务系统开放数据库连接。
- 客户端密钥、会话加密密钥、恢复码 pepper 和签名私钥使用不同值。
- 日志默认不记录请求/响应体；显式屏蔽 Cookie、Authorization header 和所有秘密字段。
- 错误追踪平台发送前再次脱敏。
- 管理审计只追加，普通网页管理员不能删除。

第一版明确接受 Spring Authorization Server 持久化层可能保存框架运行所需的可用 Authorization Code/Refresh Token 值。SSO 数据库因此按在线高敏 Secret 存储保护，不允许业务系统、分析账号或网页管理员访问；备份必须加密。不得以此为理由放宽密码、恢复码、人工恢复凭据或 Client Secret 只保存摘要的要求。

## 12. 安全事件响应

| 事件 | 立即动作 |
|---|---|
| 用户密码疑似泄漏 | 重置密码、增加凭据版本、全局退出、重发恢复码 |
| 管理员密码疑似泄漏 | 摘流量、维护命令重置、撤销全部客户端管理会话、检查审计 |
| 客户端密钥泄漏 | 创建新密钥、部署 BFF、撤销旧密钥、撤销该客户端授权 |
| Refresh Token 重用 | 撤销 family 和本地会话、记录高危事件、要求重新授权 |
| 数据库泄漏 | 保护证据、轮换数据库/客户端/pepper 相关 Secret、评估强制改密 |
| JWT 私钥泄漏 | 立即停签旧 key、发布新 key、撤销所有会话/授权、通知全部 BFF |
| 短期状态 pepper 泄漏 | 轮换 pepper、清空短期状态表、要求未完成 challenge/恢复流程重新开始 |

签名私钥泄漏的处理不能只靠等待 Token 15 分钟过期；需按运维手册执行紧急换钥、全局撤销和应用侧阻断旧 `kid`。

## 13. 已接受风险与后续改进

第一版明确接受：

- 没有 MFA，管理员密码泄漏可导致 SSO 管理面被接管；
- 没有邮箱/实名，无法技术上保证一人一账号；
- 单实例和自管服务器存在维护窗口；
- 人工恢复的正确性依赖运营核验质量。

建议在系统稳定后优先评估管理员 Passkey/TOTP、双管理员审批或 break-glass 分权，但这些不阻塞第一版。

## 14. 参考

- OWASP Password Storage Cheat Sheet: <https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html>
- OWASP Authentication Cheat Sheet: <https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html>
- OWASP Forgot Password Cheat Sheet: <https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html>
- OAuth 2.0 Security Best Current Practice: <https://www.rfc-editor.org/rfc/rfc9700.html>
- JWT Best Current Practices: <https://www.rfc-editor.org/rfc/rfc8725.html>
