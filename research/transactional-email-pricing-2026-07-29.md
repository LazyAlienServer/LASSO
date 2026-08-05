# LAS SSO 托管事务邮件价格调研

> 核对日期：2026-07-29  
> 场景：约 1,000 名用户，主要发送注册验证、登录 MFA、账号恢复和安全通知。  
> 口径：每封邮件只有一个收件人；按月发送 1,000 / 5,000 / 10,000 封；使用共享 IP；不带附件；不计税费。美元不换算成人民币。

## 结论

在 LAS SSO 的体量下，邮件本身非常便宜，选择重点应是验证码的到达率、延迟、可观测性和供应商故障处理，而不是每月几元的价差。

- **优先试用阿里云邮件推送或腾讯云 SES**：两者均无最低月费，稳定用量下约为 `￥1.90–20/月`。面向中国用户时，建议同时做一次主流邮箱实测，再择优上线。
- **AWS SES à-la-carte 是美元计价中最便宜的选择**：约 `$0.10–1.00/月`，但新账号默认进入 Essentials，需要主动切换 à-la-carte，并完成生产发信申请。
- **Resend 免费档适合开发或极低量试运行**：免费 3,000 封/月，但 100 封/日是硬限制；不建议让正式 MFA 登录长期依赖这一上限。
- **Postmark 最省集成判断但有明显最低月费**：正式用量最低 `$15/月`，对于当前规模价格最高。
- **无需购买独立 IP**：每月 1 万封远低于各家推荐或允许独立 IP 的典型规模，共享 IP 更适合当前用量。

正式选择前，建议用同一发信子域名策略和同一批测试邮箱，对 QQ 邮箱、网易邮箱、Outlook、Gmail 等实际收件箱测试：验证码到达时间、垃圾箱率、退信事件和连续发送稳定性。价格不能替代到达率实测。

## 简明成本表

下表采用“长期稳定运行”的费用，不把一次性赠送额度或 AWS 新客通用抵扣金计入；Resend 的免费档是持续月度额度，因此保留。

| 服务与方案 | 1,000 封/月 | 5,000 封/月 | 10,000 封/月 | 最低月费 / 计费方式 |
|---|---:|---:|---:|---|
| AWS SES Essentials（新账号默认） | `$0.16` | `$0.80` | `$1.60` | 无固定月费，`$0.16/1,000 封` |
| AWS SES à-la-carte（主动切换） | `$0.10` | `$0.50` | `$1.00` | 无固定月费，`$0.10/1,000 封` |
| Resend Free / Pro | `$0` | `$20` | `$20` | 免费 3,000 封/月且限 100 封/日；Pro 最低 `$20/月`，含 50,000 封 |
| Postmark Basic | `$15` | `$15` | `$15` | 免费档仅 100 封/月；最小付费档 `$15/月`，含 10,000 封 |
| 阿里云邮件推送（按量） | `￥2.00` | `￥10.00` | `￥20.00` | 无固定月费，`￥2/1,000 封` |
| 腾讯云 SES（按量） | `￥1.90` | `￥9.50` | `￥19.00` | 无固定月费，`￥0.0019/封`，按日结算 |

来源：[AWS SES 定价](https://aws.amazon.com/ses/pricing/)、[AWS 2026-07-21 定价方案公告](https://aws.amazon.com/blogs/messaging-and-targeting/introducing-amazon-simple-email-service-ses-pricing-plans/)、[Resend 定价文档](https://resend.com/docs/knowledge-base/what-is-resend-pricing)、[Postmark 定价](https://postmarkapp.com/pricing/)、[阿里云邮件推送计费](https://help.aliyun.com/zh/direct-mail/billing-methods)、[腾讯云 SES 价格说明](https://cloud.tencent.com/document/product/1288/47930)。

## 各服务细节

### AWS SES

- 2026-07-21 起，新 SES 账号默认进入 Essentials；官方同时说明可以随时切换至 à-la-carte。Essentials 在每月 0–1,000 万封区间为 `$0.16/1,000 封`；à-la-carte 外发邮件为 `$0.10/1,000 封`。[定价方案公告](https://aws.amazon.com/blogs/messaging-and-targeting/introducing-amazon-simple-email-service-ses-pricing-plans/)、[定价页](https://aws.amazon.com/ses/pricing/)
- 2026-07-21 起，新客户不再获得原 SES 专项“每月 3,000 条消息、持续 12 个月”的免费额度。新 AWS 客户可能获得前 6 个月最高 `$200` 的 AWS 通用抵扣金；已有 SES 专项免费资格的账号可用完原期限。抵扣资格不应写进长期成本预算。[官方公告](https://aws.amazon.com/blogs/messaging-and-targeting/introducing-amazon-simple-email-service-ses-pricing-plans/)
- 新账号处于 SES sandbox 时，只能向已验证地址发信，且限制为 24 小时 200 封、每秒 1 封；生产使用前需要申请移出 sandbox。[AWS SES 发信配额](https://docs.aws.amazon.com/ses/latest/dg/manage-sending-quotas.html)
- 外发邮件数据另计 `$0.12/GB`。验证码邮件很小，因此成本表未计入这一项。[AWS SES 定价](https://aws.amazon.com/ses/pricing/)
- 标准独立 IP 为 `$24.95/IP/月`；托管独立 IP 为 `$15/账号/月`，另加 0–1,000 万封区间 `$0.08/1,000 封`。LAS 当前不需要。[AWS SES 定价](https://aws.amazon.com/ses/pricing/)

### Resend

- 免费档为 3,000 封/月、100 封/日；免费档不能超额继续发送。发送和接收都占额度，多收件人分别计数。[定价文档](https://resend.com/docs/knowledge-base/what-is-resend-pricing)、[额度限制](https://resend.com/docs/knowledge-base/account-quotas-and-limits)
- 最低付费 Pro 为 `$20/月`，含 50,000 封；超额为 `$0.90/1,000 封`。付费档无每日发信上限。[定价文档](https://resend.com/docs/knowledge-base/what-is-resend-pricing)
- 免费档的 100 封/日在常态下可能够用，但突发注册、重新登录或邮件重发会共同消耗额度。若 MFA 邮件超过上限，用户将无法完成登录，因此它更适合作为开发期方案。
- 独立 IP 为 `$30/月`，仅 Scale 方案可加购；官方要求高日发送量，明显不适合当前 LAS 体量。[Resend 定价](https://resend.com/pricing)

### Postmark

- 免费 Developer 档为 100 封/月，不允许超额；100 到 10,000 封之间没有更小的付费档。[Postmark 定价](https://postmarkapp.com/pricing/)
- 最低 Basic 为 `$15/月`，含 10,000 封；超额 `$1.80/1,000 封`。即使当月未发送邮件也支付 `$15`，未用额度不结转。[月度计费说明](https://postmarkapp.com/support/article/1107-how-does-monthly-pricing-work)
- 独立 IP 从 `$50/IP/月` 起，仅面向每月 300,000 封以上用户，并要求 Pro 或更高方案；当前无需考虑。[Postmark 定价](https://postmarkapp.com/pricing/)

### 阿里云邮件推送

- 按量后付费为 `￥2/1,000 封`，无固定月费；计费精确到分。[阿里云计费说明](https://help.aliyun.com/zh/direct-mail/billing-methods)
- 每个主账号共有一次性 2,000 封免费额度，不是每月重置；免费部分每天最多发送 200 封。[阿里云计费说明](https://help.aliyun.com/zh/direct-mail/billing-methods)
- 1 万封资源包为 `￥19.98`，有效期 6 个月；到期未用余额不退。与当前按量价差只有 `￥0.02/万封`，LAS 没有必要为了这点价差预付。[阿里云计费说明](https://help.aliyun.com/zh/direct-mail/billing-methods)
- 初始日额度为 2,000 封，可随信誉等级调整，足够 LAS 的预计规模。[阿里云规格清单](https://help.aliyun.com/zh/direct-mail/product-overview/limits)
- 独立 IP 为 `￥900/个/月`；当前体量使用共享 IP 即可。[阿里云独立 IP](https://help.aliyun.com/zh/direct-mail/user-guide/dedicated-ip)
- 开通按量服务要求阿里云账号完成实名认证。[购买流程](https://help.aliyun.com/zh/direct-mail/purchase-procedure)

### 腾讯云 SES

- 按量价为 `￥0.0019/封`，按日结算，无固定月费。[腾讯云价格说明](https://cloud.tencent.com/document/product/1288/47930)
- 每个账号有一次性 1,000 封免费额度，不限有效期、用完为止，不是每月额度。[腾讯云价格说明](https://cloud.tencent.com/document/product/1288/47930)
- 新发信域名默认信誉等级 1，初始日额度 500 封；额度会随送达质量升级。普通发送超过当日额度会直接失败，因此需要监控配额和发送失败事件。[腾讯云信誉等级](https://cloud.tencent.com/document/product/1288/77252)
- API 明确支持验证码等触发邮件，默认接口频率为 20 次/秒，足够当前规模。[腾讯云发送邮件 API](https://cloud.tencent.com/document/api/1288/51034)
- 2026-03-02 后开通服务的个人认证用户不能使用 SMTP，但可以使用 API 或控制台；若坚持 SMTP，需要企业认证。[腾讯云 SMTP 指南](https://cloud.tencent.com/document/product/1288/65749)
- 独立 IP 为 `￥900/个/月`；当前体量无须购买。[腾讯云价格说明](https://cloud.tencent.com/document/product/1288/47930)

## 对 LAS SSO 的建议

第一阶段保留统一 `MailSender` 抽象，并优先比较以下两个落地候选：

1. **阿里云邮件推送**：初始日额度更宽松；使用 API 或 SMTP 均可；按量成本约 `￥2–20/月`。
2. **腾讯云 SES API**：价格略低；API 明确面向验证码场景；需要注意初始 500 封/日和个人认证账号的 SMTP 限制。

不建议仅凭标价直接决定。可分别开通测试账号，使用同一套模板，在 3–7 天内进行小规模实测；记录发送受理时间、送达时间、垃圾箱位置、退信和供应商事件通知。最终选择到达稳定性更好的一个，另一个可以保留为未来故障切换候选，但第一阶段不必实现双供应商自动切换。
