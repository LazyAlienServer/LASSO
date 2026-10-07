# 生产启动门禁实现

- 状态：当前源码已本地实现；目标环境工作仍属于 P02/P06–P10。
- 决策：[ADR-0012](../adr/0012-production-configuration-startup-gates.md) / [English](../../implementation/production-startup-gates-2026-10-07.md)。

## 代码范围

| 范围 | 实现 |
| --- | --- |
| 早期配置 | `ProductionConfigurationGate`：显式 production、profile 冲突、HTTPS issuer/context path、秘密独立性、Flyway/session/actuator/Cookie规则 |
| 数据库启动 | `ProductionStartupConfiguration`/`ProductionDatabaseGate`：Flyway 后在 Web 启动前检查 security epoch、管理员与 ACTIVE 客户端 |
| key 加载 | `PersistentSigningKeyConfiguration`：非 local 急切加载、匹配 RSA 对/至少2048位、清理密码数组、类别化失败 |
| 维护 | bootstrap/reset/restore 脚本关闭 cleanup；bootstrap 拒绝密码命令行参数 |
| 测试 | `ProductionConfigurationGateTest`、`ProductionStartupIntegrationTest`、维护命令回归；真实 PostgreSQL18.4、keytool PKCS12 与 servlet 启动 |

## 已验证情形

补充回归验证普通ACTIVE管理员启动、未来Flyway版本拒绝及生产客户端登记在提交前拒绝保留example URI。即使global lazy初始化开启，门禁仍急切执行；空管理员启动失败后无监听socket。

2026-10-07 JDK21/Docker 聚焦通过配置、生产启动、维护和local兼容测试。集成验证空库Web拒绝、非Web bootstrap、首次改密/过期管理员、合法/无效/DISABLED客户端、example Secret与缺epoch拒绝、错误key密码/alias/1024位key拒绝，以及拒绝启动后无listener。错误不含fixture秘密。最终严格完整构建 **562服务端+193 Starter+37 reference=792项**，失败/错误/跳过零，全部rerun且必需数据库覆盖。

仓库检查：481 UTF-8/text、1150本地链接/一个声明兄弟链接、三锁/Wrapper checksum/15迁移；四维护PowerShell语法通过。Gitleaks8.30.1有一条generic-api-key fixture误报：ProductionConfigurationGateTest的虚构路径protected-key.p12，不是凭据/key文件，保留复核，不称原始零发现。未验production image/DAST/真实隐藏console维护；集成测试仅服务验证时移除交互runner，再单独运行真实runner验证无console拒绝。

完整仓库构建验证本次修改；后续代码变更按风险重新验证。本实现不验证公共 CA/DNS/代理、真实秘密管理器注入、生产备份/RPO/RTO、目标容量、消费方门禁或正式上线批准。此源码构建新制品需新交付编号，历史 `.7` 与 `0.1.0-dev.20261007.1` 字节不改。
