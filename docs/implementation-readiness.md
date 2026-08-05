# LAS SSO 实施就绪度与开工门禁

- 状态：允许开始第一版代码实施，尚未达到生产上线条件
- 评审日期：2026-08-01
- 依赖：[总纲](../LAS_SSO_DESIGN.md)、[身份与数据模型](identity-and-data-model.md)、[OIDC 与 BFF 契约](oidc-and-bff-contract.md)、[UI 流程规格](ui-flow-spec.md)
- 实施进度：[阶段 0 实施记录](phase-0-implementation.md)
- Phase 1 进度：[账号基础实施记录](phase-1-account-foundation.md)

## 1. 结论

LAS SSO 已经可以开始编写代码。账号边界、永久身份、用户名规则、登录方式、会话、恢复、OIDC/BFF、退出和管理员权限都有可实现的契约，不需要等待完整视觉稿、正式域名或生产服务器。

这里的“可以开工”不等于“可以上线”：正式 Issuer、生产依赖版本、密码哈希基准、人工恢复规则、备份恢复演练和真实 BFF 联调仍是发布门禁。

第一阶段开发应使用固定的本地 Issuer 和测试客户端；不得让 `.example` 配置进入生产签发。

## 2. 文档职责与优先级

| 文档 | 权威范围 |
|---|---|
| [总纲](../LAS_SSO_DESIGN.md) | 产品边界、系统关系和已经确认的产品决策 |
| [身份与数据模型](identity-and-data-model.md) | 状态机、字段、数据库约束、事务和并发 |
| [账号与管理 API](api-contract.md) | 页面调用的 HTTP 契约、错误、权限和近期认证 |
| [OIDC 与 BFF](oidc-and-bff-contract.md) | OAuth/OIDC、Token、Claims、客户端与 BFF 行为 |
| [安全与恢复](security-and-recovery.md) | 密码、恢复、威胁控制、会话和 Secret |
| [UI 流程规格](ui-flow-spec.md) | 页面、状态、跳转、危险操作确认和一次性秘密展示 |
| [部署与运维](operations-runbook.md) | 环境、上线、备份、恢复、轮换和故障处理 |
| [测试与验收](test-plan.md) | 可验证的完成条件与发布证据 |

发生冲突时先按权威范围判断；跨范围冲突以总纲的产品边界为上位约束，并在同一次变更中同步所有受影响文档。不能只修改实现而不更新契约。

## 3. 已冻结的实现基线

以下决策可直接用于编码：

- Spring Authorization Server 路线保留，但 `SAS-Test` 只作为协议演示，不作为生产骨架。
- `UUID v7` 是永久用户 ID、Spring Security principal name 和 OIDC `sub`；用户名始终是可变资料。
- 用户名大小写敏感，历史值按精确大小写永久保留，修改冷却 90 天。
- 第一版只有用户名 + 密码，不收集邮箱，不提供 MFA。
- 注册始终要求 ALTCHA，并同时执行服务端限流和数据库唯一约束。
- PostgreSQL 保存永久事实和隔离的短期安全状态表；第一版不引入 Redis。
- 浏览器只持有 SSO 或业务 BFF 的 HttpOnly Cookie，不接触 OAuth Token。
- Authorization Code + PKCE S256；AT 15 分钟；RT 每次轮换且 family 从首次授权起绝对最多 14 天。
- 每个业务系统有自己的 Confidential BFF、客户端、会话、权限和 `(issuer, sub)` 用户映射。
- 退出当前应用先清理本地会话，再尽力撤销当前授权；全局退出通过 SSO 和 Back-channel Logout 传播。
- 第一版每个 OIDC 客户端只有一把活动 Client Secret，轮换使用短维护窗口，不自研双 Secret 认证模型。
- 第一版允许 Spring Authorization Server 持久化层保存其正常运行所需的 Token 值；通过数据库隔离、最小权限、加密备份和日志脱敏保护。自研“只存摘要”的 Authorization Repository 不作为开工前置条件。
- 灾难恢复后统一提升 `security_epoch`，清空会话与授权并更换签名 key，所有用户重新登录，避免备份复活已撤销凭据。

## 4. 开发前必须完成的阶段 0

阶段 0 是代码工作的一部分，不要求在开始写任何代码前另行等待：

1. 建立正式后端工程，不在 `SAS-Test` 内继续堆叠生产功能。
2. 锁定受支持的 JDK、Spring Boot、Spring Authorization Server 和 PostgreSQL 版本，并保存依赖锁定结果。
3. 第一版固定使用 Flyway；若未来迁移到 Liquibase，作为独立工具链变更处理。
4. 完成三个短技术验证：持久化 Authorization/Consent、UUID principal 到 `sub` 的全链路、Back-channel Logout 扩展点。
5. 建立真实 PostgreSQL 的 Testcontainers 测试，不使用 H2 或内存 Map 代替关键约束，并覆盖短期状态的原子并发行为。
6. 固定本地 Issuer、两个本地客户端及精确 Redirect/Logout URI。
7. 为安全相关代码建立模块边界：账号、凭据、会话、OIDC、客户端管理、审计/outbox 和维护命令。

如果 UUID principal、持久化 Authorization 或 Back-channel Logout 中任一验证无法满足契约，应先回到设计评审，不能用可变用户名、浏览器 Token 或自定义非标准退出接口绕过。

截至 2026-08-02，正式 Gradle 工程、版本/依赖锁、Flyway schema、UUID principal、Logout Token、账号基础、公开注册、ALTCHA v2、PostgreSQL 短期状态以及注册/登录响应式界面验证已经完成。完整测试共 25 项，0 失败、0 跳过；阶段 0 门禁已关闭。详见[阶段 0 实施记录](phase-0-implementation.md)。

## 5. 可并行实施顺序

| 工作流 | 可立即开始 | 合并前依赖 |
|---|---|---|
| 工程骨架、配置、迁移、测试容器 | 是 | 阶段 0 版本锁定 |
| 用户、用户名、密码、恢复码、状态与审计 | 是 | 数据模型迁移通过 |
| UUID principal、登录、会话、近期认证 | 是 | 用户与凭据模型 |
| OIDC Discovery/JWKS/Code + PKCE | 是 | 固定本地 Issuer 与 key |
| Authorization/RT family/version 检查 | 是 | 持久化技术验证 |
| LASLOG Spring BFF 适配 | 可并行搭骨架 | OIDC 契约测试可运行 |
| AlienCommon Django BFF 适配 | 可并行搭骨架 | 同上 |
| SSO 前端页面 | 可先做壳与组件 | 对应流程线框和 API 契约冻结 |
| 管理后台危险操作 | 可做只读页 | 近期认证、审计和确认流程冻结 |
| 生产部署 | 否 | 第 7 节全部上线门禁 |

建议先做 LASLOG 一条完整纵向链路：注册/登录 -> Authorization Code -> JIT 用户 -> 刷新 -> 当前退出 -> 全局退出。通过黑盒契约后，再复用同一契约实现 AlienCommon 适配。

## 6. UI 是否需要先设计

需要先完成低保真流程设计，但不需要在后端开工前完成整套视觉设计。

必须先确定的是页面状态和安全交互：OIDC 登录上下文、错误与限流、恢复码只显示一次、临时密码强制改密、近期认证、注销等待期、Client Secret 只显示一次、危险操作确认及退出语义。这些内容会影响路由、API、会话和测试，若边写页面边临时决定，容易形成安全或状态漏洞。

可以后置的是品牌视觉、插画、动效、最终配色、精细排版和非核心账号首页布局。后端的数据库、OIDC、会话、Token、outbox、维护命令和契约测试不应等待高保真稿。

最小 UI 前置产物见 [UI 流程规格](ui-flow-spec.md)：

1. 页面清单与入口；
2. 每页正常、加载、空、错误、过期和权限不足状态；
3. 登录/注册/恢复/退出/注销/管理员危险操作的跳转图；
4. 一次性秘密的展示与“已保存”确认；
5. 中文错误文案和可访问性基线；
6. 桌面与窄屏低保真线框。

完成上述内容即可开始 SSO UI 实现，无须等待像素级设计系统。

## 7. 生产上线门禁

以下事项不阻塞阶段 0/1 编码，但阻塞生产上线：

- 正式域名、TLS、固定 Issuer 和两个业务系统的精确回调/退出地址；
- 受支持依赖版本与升级策略；
- 生产硬件上的 Argon2 参数和资源耗尽测试；
- 普通用户人工恢复的至少两项核验依据和运营记录模板；
- 管理员恢复码离线保管和维护命令演练；
- 正式反向代理、可信代理、Cookie、CSP 与 Secret 注入；
- PostgreSQL 备份、异地保存、恢复后全局 epoch/key 重置演练；
- LASLOG 与 AlienCommon 的真实 BFF 黑盒契约测试；
- 全局退出、Back-channel 重试、15 分钟 freshness 上界验证；
- 日志秘密扫描、安全扫描、依赖漏洞处置和发布证据；
- 账号注销、历史业务数据保留和人工争议处理规则；
- 完成核心 UI 的浏览器 E2E 与可访问性检查。

## 8. 当前未阻塞但必须跟踪的事项

- 头像第一版固定只支持站内默认头像资源 ID；上传与任意 URL 等媒体安全方案确定后再开放。
- 合作网站 consent UI 可以在首个合作方接入前实现，不阻塞 LAS 自营第一版。
- 管理员 Passkey/TOTP、多人审批、多实例和 KMS/HSM 属于后续增强。
- 正式公共适配库可以先以内仓模块交付；在两种 BFF 实现稳定后再发布独立版本。
