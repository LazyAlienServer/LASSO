# 人工恢复、凭据保管与注销争议

[English](../../operations/manual-recovery-and-lifecycle.md) · [ADR-0014](../adr/0014-manual-recovery-verification-and-secret-ui.md)

## 普通用户人工恢复

1. **受控建单。** 通过现有 QQ 群联系，再转既有私密渠道。管理页将精确当前用户名关联稳定 UUID；群身份或新聊天自称用户名不证明归属。不索要密码、恢复码、Cookie/Token 或无关身份证明材料。
2. **两项独立依据。** 与确实可查的既有记录核对，如账号历史用户名/创建细节、非公开长期社区互动、由业务操作者独立确认的 LASLOG/Minecraft 历史事实，或既有线下身份。同来源两个截图/重复叙述只算一项；公开名/UUID/皮肤/IP 不够。仅记录可信来源引用和结论，不把业务敏感内容复制进 SSO。Minecraft UUID 不是人类主体，由消费方核历史关联。
3. **明确结论。** 两项均指向同目标；矛盾未解/记录不足为 NOT_VERIFIED，不签发。疑似盗号/注销争议进入调查，不编造证据，不跳登录或因申请就解冻，保审计。
4. **管理页签发。** `/admin/users` 确认用户名+UUID，填非秘密工单引用/原因、当前管理员密码。沿用当前角色/session/Origin/CSRF/近期密码门禁，仅 ACTIVE 普通非管理员。凭据一次性、30分钟、只显示一次。记 request/audit/凭据行引用/到期，不记明文；管理员不能设置/查看用户密码或代登录。
5. **私密交付和完成。** 只交付已独立核验的既有渠道；用户 `/password-recovery` 自设新密码后普通登录。用关联审计/状态核结果，不索新密码。交付不确定/丢失需重发，废旧未用凭据；已用/过期不复活，DB/审计不能找回明文。窗口关闭后 DOM 清理，不代表剪贴板历史/下载/接收方副本自动删除。

## 工单模板（无秘密）

```text
工单引用 / 建单时间 / 环境：
目标当前用户名 / 稳定UUID：
联系渠道引用（受限，不公开聊天全文）：
依据A：类别 / 可信记录引用 / 核验者 / 结论：
依据B：独立类别 / 记录引用 / 核验者 / 结论：
矛盾及处理：
决定：VERIFIED_ISSUE / NOT_VERIFIED / INVESTIGATION / DELIVERY_UNCERTAIN
SSO操作者 / 非秘密原因：
签发requestId / audit或凭据行引用 / 到期：
交付渠道引用 / 时间 / 接收确认结果：
结果：已用 / 过期 / 重发 / 拒绝 / 待调查
完成audit/requestId / 时间：
后续与受控记录负责人：
```

只保必要依据引用/结论，禁止密码/码/key/Token/完整Cookie/原始敏感导出。工单标签不是认证或自动软件审批。上线前定访问/保留负责人，本文不虚构法定保留期限。

## 管理员恢复与保管

- 首次改密重登后生成恢复码，确认十枚，存受控加密密码管理器或封存离线介质，与主机/DB独立；不进源码、部署环境、截图、公开QQ、数据库旁明文备份。
- 验可读备份副本，不传明文给支持；记录位置/负责人/复查日期而非码。按非秘密generation/date识别批次，轮换废旧并清旧副本。下载或自动测试成功不证明真实保管已完成。
- 优先自己的未用恢复码，再由授权主机人员离线维护；管理员禁止普通人工签发。精确server JAR/hash、DB/config、production non-Web、真实隐藏console、停止排空写入者，见[服务器指南](../../../las-sso-server/README.md)/[恢复屏障](restore-epoch-barrier.md)。
- 不确定reset先核UUID/唯一admin/审计，不盲重跑。reset废旧密码/session/authorization/recovery证据；主机/DB/保管全丢无网页后门。

## 注销争议与管理员任命

1. 核UUID/request ID/当前PENDING/HELD/CANCELLED/EXECUTED，记争议单/事实原因/操作者。SSO生命周期不是业务封禁/内容归属，业务历史另处理。
2. 调查确需时间时，用 `/admin/deletion-requests` hold（最多90天）/审查截止/私有原因/独立公开摘要，不向用户泄露内部指控或他人资料。held仍无资格但本人可密码取消，不当无限冻结、不移除取消入口。
3. 截止前复查，解决后支持的release操作；核原executeAfter：已到期release可在下轮任务立即执行，释放不重置30天等待。准确通知，EXECUTED/DELETED后不承诺恢复。
4. 仅本人 `/account/delete` 密码取消pending/held；按已有UUID离线晋升前须取消，bootstrap原子拒有效PENDING/HELD，不自动解除/取消。不在生产SQL改is_sso_admin/注销行，DELETED UUID不复用/复活。

```text
争议单 / 目标UUID / 注销request ID / 观察状态：
私有依据引用 / 原因 / 操作者：
hold截止 / public summary / requestId/audit：
复查结论 / release或本人cancel requestId/audit / 时间：
原计划执行时间已核 / 用户沟通引用：
最终状态 / 消费方后续：
```

软件门禁/fixture浏览器验收与真实人员培训、申请人核验、离线保管/争议操作分别验。公众开放前指定负责人，并用非生产数据演练。
