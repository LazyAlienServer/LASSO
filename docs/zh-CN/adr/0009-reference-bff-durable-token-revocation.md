# 参考 BFF 持久加密 Token 撤销

P03后续：[ADR-0013](0013-reference-revocation-operator-recovery.md)替代原无replay/cleanup/keyregistry边界；当前参考V2有精确代次offline恢复、仅完成保留、认证排空轮换。原文证据/计数保留历史范围。

状态：2026-10-05用户明确选择参考BFF验证；已实现并经PostgreSQL/HTTP/真实SSO进程重启验收，消费生产Adapter仍独立。

[English](../../adr/0009-reference-bff-durable-token-revocation.md)。

## 背景

Starter sink是公开提交边界，默认executor队列进程退出丢任务。当前应用退出先删除本地授权/结束Session再提交本会话Token快照；顺序保持，提交返回不能误称OP已撤销。

## 决策

1. 参考BFF可选durable-revocation profile实现原sink。默认Starter/BFF不依赖DB，不建LASLOG表/独立JDBC库。
2. 显式独立PostgreSQL与reference_revocation schema/打包参考V1迁移，profile启动迁移校验；缺DB/key失败不回退内存。生产权限分离归部署。
3. submit以REQUIRES_NEW提交再返回；标准JCE AES-256-GCM/随机96bit nonce/128bit tag，长度前缀taskId/registration/issuer/client/endpoint/hint/keyId AAD。Client Secret不入库，投递用当前可信配置并比身份/endpoint快照；仅HTTPS或显式literal loopbackHTTP开发开关。
4. DB SKIP LOCKED每次取一due任务，独立claimtoken/两分钟lease，HTTP前领取即加attempt，DB时间控制到期。旧/过期owner不能回执，租约过期新owner恢复。每5秒poll最多串行10个，每个HTTP前即时领取。
5. POST form/Basic、3秒connect/5秒read、禁redirect、仅HTTP200确认；失败分类码/随机指数retry，最多8claims；deadletter持久不自动重试，第8次领取后崩溃到期标LEASE_EXHAUSTED。
6. HTTP200后DB回执失败可能重发RFC7009；预算内at-least-once非exactly-once。成功擦nonce/ciphertext，dead保加密材料供显式运维恢复。
7. 保local先退出；提交失败抛错但local已结束。提交落库前crash仍可丢任务，不提供跨存储logout/outbox原子、业务Session持久或危险操作自动重放。

## 运维影响

仅显式单keyId/key，非多key轮换服务。重启保key/独立保护备份；pending/dead未排空即换key导致PAYLOAD_UNAVAILABLE/最终dead。先排空恢复或另设计认证re-encryption。身份endpoint保持，Secret单独轮换支持；不匹配CONFIGURATION_CHANGED且不发旧Token到新地址。

类包私有属于sample非Starter API，JDBC/Flyway/PostgreSQL仅参考模块；SSO V1–V14/Starter锁API不改。receipt/dead保留清理/监控人工恢复/DB权限pool容量TLS归运维，无自动清理或dead重放。并发DB claim不代表完整multiinstance BFF Session/refresh/index/replay。进程暂停/网络歧义可重复请求，DB fencing不能撤销已开始外部HTTP。

## 证据

本决策PG/AES/HTTP/config验证覆盖commit作业/lease/retry/receipt、key/metadata/registration及失败接受；真实默认/自有browser也验已提交task重启和别sid独立。原范围/来源见[.4交付](https://github.com/LazyAlienServer/LASSO/blob/8390243/docs/zh-CN/integration/development-delivery-2026-10-05-4.md)。
