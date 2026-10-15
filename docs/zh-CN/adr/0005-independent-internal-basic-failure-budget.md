# 内部 Basic 独立哈希前失败预算

状态：用户于2026-10-04明确确认；已实现并经PostgreSQL/MockMvc验证，生产容量/传输验收待完成。

英文对应：[Independent pre-hash budget for internal Basic authentication](../../adr/0005-independent-internal-basic-failure-budget.md)。

## 背景

A3内部service-access查询直接验证Secret，没有哈希前限流。OAuth端点已有先观察失败、失败后计数，但内部未接入。把10/20次十五分钟套到所有查询会很快阻断正常资格查询；仅观察失败后哈希则并发错误请求可能在尚无失败记录时全部通过。

## 决策

用户选择内部客户端/网段独立预算，不共享OAuth失败scope：

- 沿用rate_limit_buckets、HMAC和网段基础，新内部scope，默认客户端10/网段20，十五分钟固定窗口。
- 客户端查询/密码哈希前，在一个短REQUIRES_NEW事务原子占用网段→客户端；任一耗尽整体回滚本次占用，429/Retry-After。
- 占用提交后检查active first-party Basic资格和Secret，哈希不持预算行锁。认证成功在另一短事务仅归还本次名额；失败/未完成/崩溃保留至窗口到期。
- 计数代表已失败＋当前窗口在途校验，不是成功查询配额；成功不清旧失败。耗尽时连正确凭据也哈希前拒绝，新窗口/其他未耗尽键独立可用。
- 内部scope独立于OAuth Token/revocation认证失败桶，本修复不重做协议端点限流、不把内部查询和刷新可用性耦合。
- 占用/认证/成功归还的DB事务故障返回no-store503、不继续认证。原无效401 Basic challenge保留；预算429/RATE_LIMITED+Retry-After/requestId。
- 内部Authorization最多4096字符、client ID128；无效/缺失/超长/重复头共用匿名客户端和网段预算，不查询/哈希。

## 替代方案

- 所有内部查询计数：不作为初期默认，失败阈值过小，影响正常机器查询。
- 共用OAuth桶：用户未选，内部攻击/误配不应独立耗掉Token刷新失败预算，共享需另审协议并发。
- 只观察失败/哈希后才加：未选，会并发超额通过哈希门禁。
- 哈希期间持锁或内存缓存：未选，避免长DB锁且单进程状态不能代表PostgreSQL共享预算。

## 后果

正常认证增加两次短写事务，成功查询仍无缓存/仍验Secret。占用拒绝原子/计数封顶；本次不透明receipt归还幂等且只针对原窗口。schema计数必须正，最后一名额归还删桶不写0；过期清理不重建桶或改新窗口。

限制的是每窗口/键获准哈希，不是总容量/所有请求吞吐上限或跨窗口全局并发。耗尽会暂拒合法客户端/共享网段，固定窗口边界、多键和持有效Secret的高流量仍需容量/运维评估。崩溃成本保留至到期是失败关闭取舍；窗口内配置变更/混合版本部署需运维审查，不提供自动恢复协议。

实际PG/MockMvc、encoder spy、受控并发/Clock/SQL故障验证保[历史契约](https://github.com/LazyAlienServer/LASSO/blob/8390243/docs/zh-CN/integration/internal-basic-authentication-budget.md)。无schema/依赖/权限/proxy规则新增；目标地址/容量另验。
