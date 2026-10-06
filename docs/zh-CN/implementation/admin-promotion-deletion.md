# 管理员晋升与有效注销申请

- 状态：2026-10-04 S4 实现／受控 PostgreSQL 与命令验证完成；真实维护／部署待验收。
- 英文对应：[Administrator promotion and effective deletion requests](../../implementation/admin-promotion-deletion.md)。
- 决策：[ADR-0002](../adr/0002-require-deletion-cancellation-before-admin-promotion.md)，2026-10-02 已确认，本修复没有新产品规则。

## 1. 晋升门禁

`AdminBootstrapService.promoteExistingUser` 沿用维护 advisory lock／无现存管理员条件，锁定目标 ACTIVE 用户。用户名／密码验证与任何角色／安全变更之前，锁／检查该用户有效 PENDING/HELD 请求。

| 目标状态 | 结果 |
| --- | --- |
| ACTIVE 且有 PENDING/HELD | `DELETION_CANCELLATION_REQUIRED`，所有者先取消、操作员再重试 |
| 已到期 PENDING／过期但仍 HELD | 同样拒绝，日期不令请求终结 |
| 释放暂缓为 PENDING | 仍拒绝，释放非取消 |
| ACTIVE，仅已取消历史或无有效申请 | 符合其他条件可晋升 |
| 已取消历史＋新的有效请求 | 拒绝 |
| 冻结／删除／未知 | 原 `USER_NOT_FOUND` |
| 已存在管理员 | 原 `ADMIN_ALREADY_EXISTS` |

沿用 S3 用户优先锁，取得用户锁后才检查请求，不用命令开始前的 preflight 代替。无新 schema／隔离等级／权限 claim／认证因素／网页晋升接口；空库创建和维护 reset 沿原路径。

拒绝在临时密码策略／哈希、角色／版本、恢复／会话／授权撤销、outbox、成功审计之前。不会自动取消或释放。完整行快照验证尝试前后不变；用户取消后晋升不重写已取消历史，仍设置临时密码／推进版本／失效旧安全状态／写成功维护审计。

## 2. 并发结果

真实 PostgreSQL 受控测试：

- 晋升已持维护锁但等用户，申请先提交：新请求被检查、晋升拒绝。
- 晋升先提交：等待中的申请看到管理员身份、拒绝且无新请求。
- 取消先提交：等待晋升变为合法，取消提交前不改角色／密码。
- 删除先提交：晋升拒绝已删除目标，不产生管理员。
- 等待中申请变 HELD／账号变 FROZEN：按当前状态拒绝；冻结变化是明示隔离库 SQL fixture，不是冻结 HTTP 流程验收。
- 晋升等用户时未持有请求锁。
- 多个合法 bootstrap 仍只一个成功、另一个已有管理员拒绝。

合法晋升失败回滚角色／哈希／版本／安全／审计／outbox。一个测试用实际 PostgreSQL 审计 trigger 在早期变更后故障，另一个外层事务异常验证回滚／重试。不保证手工 SQL 或先持不一致锁的任意调用方事务遵守规则；真实多实例／崩溃／容量独立验收。

## 3. 维护命令

沿用 UUID 输入和隐藏临时密码。新拒绝提示：

```text
Administrator promotion refused (DELETION_CANCELLATION_REQUIRED).
The account owner must cancel the pending/held deletion request first; then retry administrator bootstrap.
```

不输出成功结果并继续抛异常，保留 ApplicationRunner／启动失败。PowerShell 包装器仍传播 Java 退出码，本轮只读取，没有运行或修改。测试用包内 Console supplier fixture；公开生产构造器仍 System.console()，保留非 Web／交互要求。测试验拒绝／失败／成功不含密码、两次 char[] 清零；未运行真实终端隐藏／打包／shell 退出码／生产维护演练。

操作员正确后续为：所有者通过既有认证流程取消，再核对账号／管理员状态并重试目标。不要 SQL 直接改角色或替用户取消。此门禁不创建近期密码证据；弹窗／Token 时间不等于密码重输入。

## 4. 证据与历史 fixture 校准

`AdminPromotionDeletionIntegrationTest` **25 新增**，实际 PostgreSQL18.4／Spring 代理服务。普通／人工恢复与客户端通过 owning service 创建。申请后会话／Authorization 明示 fixture，用于证明拒绝不能删既有受限安全状态，不表示 pending 下正常允许无限制 OIDC。

PENDING/HELD 回归先于修复执行；第一轮 HELD setup 复用客户端 Secret 导致失败，改唯一 Secret 后，两条均因未拒绝而 **预期红灯**。门禁后完整快照通过。红灯 XML 后续覆盖，输出／状态地图 §3.10 保存阶段证据。

`AdminBootstrapCommandTest` **3 新增**，Console fixture 验提示／失败传播／密码清理、无关失败与成功。原注销测试改名 `executionProtectsAdministratorInLegacyInconsistentDeletionState`，仅隔离库手动设置历史／腐化角色请求组合以验执行 fallback，不再通过支持的晋升创建；保留防御而非允许流程。

server 目录 Java21 命令：

```powershell
.\gradlew.bat :test --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --tests "org.las.sso.maintenance.AdminBootstrapCommandTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --tests "org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

最后约19:23专项 **90（25+3+10+24+28）** 零失败／错误／跳过。2026-10-04约19:25–19:28强制全量 **255 server / 176 starter** 同零失败／错误／跳过，12 actionable 全执行，BFF 重编译／NO-SOURCE。晋升 XML25项11:26:48UTC、命令3项11:26:46UTC，相对227新增28。

## 5. 未验收／剩余

未操作生产 DB、部署、commit 或 release。SQL故障 trigger 仅一次性库安装／移除。latch／pg_blocking_pids 确认真实 wait，不靠随机 sleep；Mock Console 非真实进程交互，空库既有测试也为 service 级维护证据。

真实浏览器取消／首次改密、终端／脚本／JAR退出、restart/crash、高竞争容量／live多实例维护未验。S1/S3既有回归和原子DB变更在对应范围通过；S5随后修复/通过数据库/MockMvc/模板，见[墓碑契约](admin-tombstone-views.md)及状态§3.11；S6随后生成/旧兼容修复验证，见[恢复契约](recovery-credential-entropy.md)及§3.12；D1指定补丁随后升级验证，见[Security记录](spring-security-consent-patch.md)及§3.13。完整扫描/B1消费版本仍开放；上方255/176保留S4阶段，最新结果见[状态地图](../../implementation/status-issues-and-plan-2026-10-02.md)。
