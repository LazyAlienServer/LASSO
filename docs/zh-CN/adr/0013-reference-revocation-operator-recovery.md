# ADR-0013：参考撤销队列的操作恢复与密钥排空

- 状态：P03实现已接受，2026-10-07
- [English](../../adr/0013-reference-revocation-operator-recovery.md)

## 决策

保持先本地退出、submit返回前持久接受。提供安全的request级提交结果（accepted/failed/no-token），不宣称远程成功；默认内存sink遇无效提交必须拒绝，不能静默成功。提交前进程crash仍需消费方补偿：消费方独立存储会话/授权恢复意图，或调查后通过支持的OP管理流程撤销对应授权/会话。参考队列不能重建从未提交的Token。

增加参考队列V2维护状态/审计及离线交互命令。死信恢复要求精确task UUID、预期replay generation、操作者/原因、可认证解密载荷；授予新一轮最多8次尝试，不自动无限重试。审计与变更同事务。完成回执默认30天后按小时有界批量清理（可配1–365天）；pending/leased/dead载荷不被保留清理删除。操作审计保留，另定审计期限策略。

单例active-key registry用域分离HMAC证明持有密钥。全部队列写入/claim短暂串行锁定并验证配置key。旧队列首次升级只在未完成key ID一致且保留载荷可认证时初始化。轮换前必须排空全部未完成任务（含dead/lease），同事务比对旧key、更新registry/审计新key。不增加重加密/多key环。旧进程轮换后不能submit/claim，同ID不同key/启动不可读均拒绝。外部key保管与消费方采用仍各自负责。

## 后果

参考V2独立于SSO V1–V15与消费方schema。维护用小型non-Web配置，不做OIDC Discovery/启动worker。恢复不改变issuer/client/endpoint，投递仍核可信当前登记。清理不代表撤销成功；不可恢复死信阻止轮换，需故障处理，不能SQL删除绕门禁。增长/dead年龄/保留审计仍需监控与容量策略。RFC7009至少一次与pre-submit gap继续明确。
