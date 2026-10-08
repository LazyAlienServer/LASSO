# ADR-0015：运行历史保留与有界清理

- 状态：用户确认P05策略，2026-10-08
- [English](../../adr/0015-runtime-history-retention.md)

## 决策

保留身份墓碑、占用用户名、注销事实、consent、管理员审计/安全事件；P05不自动删除/归档这些事实，增长/可核验归档由运维另做。

终态session和已用/废除普通/人工凭据摘要默认90天、配置最低90天。会话无人访问也标到期，删除不让旧sid变新登录；有效码不删。人工到期审计仍原job，只有其use/revoke及expiry都足够旧才删摘要。

授权所有Token deadline及有记录RT family绝对deadline都过期30天（最低30）才回收；有Token而未知expiry或RT family deadline时失败保留，无安全deadline的无Token初始授权留待调查。删除沿RT撤销trigger/hint及security级联。REVOKED RT摘要仅无存活授权且Token expiry/撤销都超过30天才清；CURRENT/ROTATED不单独按期清，保复用检测。

存活授权全部ID hint关联保留，不单TTL/count裁剪。仅支持的成功SSO outbox超过30天且无claim/dead可删，pending/leased/dead/未知event不删。P03参考队列独立复用。

有限batch/事务statement锁timeout、忙候选跳过/重检。授权子表fanout测量并timeout保护，不虚称固定行上限；单auth超过10000 RT摘要跳过/报告，不无限加载。RT history先于auth锁，与refresh一致，任一子/父忙释放已取候选锁后跳过。失败phase rollback，commit后成功计数。短期表仍原expiry/retry但跳忙行；低基数completed/skipped run/变更行数/耗时/失败指标无身份Token。监控排除成功outbox。live hint/RT、未知expiry/超大家族仍刻意保留，需容量/运维跟进。

## 后果

V16加实测清理/查询索引，不改旧迁移。DELETE不等物理缩表，vacuum/维护属PG运维。timeout/大fanout反复失败为运维信号，不盲增batch或删live history。JWT离线验/消费expiry对账独立。pepper换代需先失效/对账受影响授权/challenge，不能继续不识别旧HMAC；同key重启非rotation。目标规模/清理吞吐/审计归档仍容量运维输入。
