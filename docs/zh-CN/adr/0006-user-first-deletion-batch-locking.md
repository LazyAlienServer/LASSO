# 注销用户优先锁序与批次原子性

状态：用户于2026-10-04明确确认；S3已实现并经PostgreSQL验证，生产验收待完成。

英文对应：[User-first deletion locking with atomic batches](../../adr/0006-user-first-deletion-batch-locking.md)。

## 背景

申请/取消先锁用户；管理员暂缓/释放先锁请求再更新用户资格版本，过期暂缓任务也先请求后用户。取消持用户等请求、管理员/任务持请求等用户可形成循环等待。批次跨用户/阶段若未协调顺序，也可能积累反序用户锁。

## 决策

用户选择保留既有整批事务/失败整体回滚，不改逐用户提交：

- 注销变更均先目标users，再account_deletion_requests，再关联安全状态。管理员候选归属读取不加锁、不授权，用户/请求锁后重检。
- 批次有限候选先无锁读；按PostgreSQL ORDER BY id以FOR UPDATE SKIP LOCKED取得候选用户集合，之后锁请求并重检归属/状态/期限。
- 定时释放过期暂缓与执行PENDING预选两组候选并集、一次取得用户锁；释放开始后不再发现/锁新用户，新增候选后续轮次处理。
- 忙用户/请求在变更前跳过、保留后续重试；保留作业advisory lock。跳过非完成，计数只实际状态变更。
- 整个已处理批次仍外层事务原子，失败回滚status/凭据删除/资格版本/审计事件/outbox，包括两个作业阶段。
- 交互锁等待后重检暂缓期限；直接execute须匹配request/user、PENDING且到期，取消/HELD/未到期/错误归属不删；原管理员保护保持。

## 替代方案

- 每用户事务：未选，缩小持锁但允许部分commit，需新协调/计数/重试契约。
- 只修管理员不修expiry：拒绝，任务仍有同类倒置。
- 分别修两阶段不先锁并集：未选，同事务会积累不同用户顺序。
- 锁请求再找用户：拒绝，重建原倒置。

## 后果

交互操作可能先等同用户变更；due批次即使有候选也可因忙/状态变化返回0，后续必须重跑，0不证明积压清空。定时最多预选100 expired+100 pending、最多执行100，本次无claim/lease表/持久重试队列/逐用户事务。

executeDue按实际请求变更计数（含原管理员保护性取消）；job计release+processed，同条释放后执行贡献2。纠正旧候选/跳过被当完成，不改HTTP payload。原公开构造/方法签名可继续用；直接执行对错/旧参数失败关闭。

用户锁保至batch commit，后续失败回滚前序，长期竞争可能延迟候选，目标容量/饥饿另验。只修已知user/request倒置，不证明任意锁无死锁。后续提升/墓碑/恢复/依赖修复保[历史实现记录](https://github.com/LazyAlienServer/LASSO/tree/8390243/docs/zh-CN/implementation)。

无schema/因素/授权/保留/依赖/部署改变，30天/90天、取消/admin保护/audit/资格保持。原DB证据见[并发记录](https://github.com/LazyAlienServer/LASSO/blob/8390243/docs/zh-CN/implementation/deletion-concurrency.md)。
