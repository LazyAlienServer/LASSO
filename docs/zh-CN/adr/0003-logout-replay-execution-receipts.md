# 退出重放 adapter 必须提供执行回执

状态：用户于 2026-10-04 明确确认；默认单 JVM 实现已验证，持久 adapter 验收待完成。

英文对应：[Require execution receipts from Logout replay adapters](../../adr/0003-logout-replay-execution-receipts.md)。

## 背景

原 `LasSsoLogoutReplayStore.accept` 只关联 issuer/jti 与摘要，不能区分执行中和已成功。相同重传再次执行失效，执行故障可能误归为无效 Token，索引先移除又可能导致重试假报完成。LAS 契约要求已完成的相同重传返回缓存成功，冲突内容拒绝，未完成执行可重试。

## 决策

增加默认 `process(issuer, tokenId, contentDigest, retainUntil, action)`，返回 `SUCCESS`、`CONFLICT` 或 `IN_PROGRESS`。`accept` 保留不变以保持源码兼容，但不是执行回执，接收器不再调用。

- 默认 `process` 抛 `UnsupportedOperationException` 失败关闭。只有 accept 的自有 adapter 仍可编译，但接收为 no-store **503**，不调用 Session 失效，直到实现新契约。
- 每个 issuer/jti 和摘要在执行前原子占用；相同内容执行中返回可重试 503，冲突返回 400。action 完成后才能记录成功，相同已成功重传返回缓存 204 不再执行。
- 失败释放执行占用、保留摘要，相同内容可重试。Session adapter 失败须保留未退出 Session 索引，含部分成功的重试必须幂等。
- 摘要/成功回执保留至 `exp + 60 秒`，覆盖允许偏差；查询回执前仍验证 Token，过期 Token 不因回执存在重新可用。
- 默认进程内实现不会仅因保留期过去就删除运行项。本次不规定共享 schema、租约期限或崩溃恢复策略。

## 考虑过的方案

- 单独内存完成层包裹旧 accept：未采用，只在同 JVM 保留运行兼容，增加第二状态所有者而未解决持久完成。
- 将 accept 静默当成功：拒绝，摘要绑定不证明 Session 已失效。
- 删除 accept/整体替换接口：未采用，增量方法提供编译兼容迁移路径并明确运行失败关闭。

## 后果与验收

后续B2默认sid标记时序见[ADR-0008](0008-retain-sid-revocations-before-local-registration.md)，与短issuer/jti执行回执独立，不为本ADR增加持久保证。

这是用户批准的运行兼容变化；consumer升级adapter见[当前接入](../integration.md)。固定issuer、first-party sid、短寿命保持，替换decoder负责密码学/原始claim验证。

默认原子占用/回执及接收行为通过确定性故障/并发和真实签名/JWKS 负面测试。持久 adapter 自行确定事务、租约、独立 Session 存储效果及重启/多实例验收；`process` 不构成跨存储 exactly-once。退出先于登记仍按 B2 跟踪，ADR 不授权业务数据库、发布或部署操作。
