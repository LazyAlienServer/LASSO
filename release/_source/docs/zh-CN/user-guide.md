# 用户与管理员操作

[目录](README.md) · [English](../en/user-guide.md) · [操作失败排查](troubleshooting.md)

## 1. 注册与登录

| 步骤 | 操作 | 结果 |
| --- | --- | --- |
| 1 | 访问公布的唯一HTTPS站点 `/register` | 登录名3–24字母/数字/下划线，大小写敏感；密码≥12字符 |
| 2 | 保存一次显示的恢复码 | 私有密码管理器/离线位置，不发支持群 |
| 3 | `/login` 登录 | 风险可要求CAPTCHA或等待；按提示完成 |
| 4 | 若提示必须改密 | 真实输入临时密码并换新，重新登录 |

业务站使用登记的OIDC流程，不共享账号密码；产品不要求邮箱/MFA。

## 2. 账号页面速查

| 页面 | 可做什么 | 影响 |
| --- | --- | --- |
| `/account` | 资料、稳定UUID、改登录名 | UUID/业务映射不变；90天改名间隔、历史名保留本人 |
| `/account/sessions` | 查看/撤销设备会话 | 撤销/全局退出传播至消费方；当前业务站退出不结束SSO主会话 |
| `/account/recovery-codes` | 剩余数量、近期密码后换批 | 旧批立即失效，新码只显示一次 |
| `/account/security` | 进入安全/恢复页改密 | 按UI提示撤销旧登录/授权/恢复材料 |
| `/account/authorizations` | 查看/撤销客户端授权 | 再授权可能需consent |
| `/account/delete` | 近期密码确认后申请注销 | 30天等待、会话撤销/资格拒；等待/HELD可取消 |
| `/password-recovery` | 用户名+未用码或人工凭据换密码 | 本次码消费，普通恢复的其他未用码仍有效 |

## 3. 恢复和一次性输出

- 一次性dialog关闭/save/Escape/离页清明文；不能重开页面找回。
- 下载、clipboard、设备历史由本人保管；换批后清旧副本。
- 无可用码：联系既有QQ群；联系人渠道不等身份验证。
- 怀疑码泄露：能安全登录时轮换；不要向支持者发密码/码/Token/Cookie。
- 近期密码证据15分钟、绑定当前浏览器登录会话；refresh不算重新输密码。

## 4. 管理员页面

SSO管理员只管理身份服务，不自动成为业务站管理员。首次bootstrap强改；离线reset设新ACTIVE密码，见[运维](operations.md)。

| 页面 | 操作 | 核对点 |
| --- | --- | --- |
| `/admin/users` | 查UUID、生命周期/会话/凭据、冻结/解冻、人工恢复/交接 | 确认目标、近期密码/原因，不能SQL绕last-admin |
| `/admin/clients` | 第一方/审核合作方、URI/scope/audience/Secret轮换 | 每站/环境独立；一次保存Secret |
| `/admin/deletion-requests` | pending/held/history、hold/release | 原因/期限；不手删身份行 |
| `/admin/audit` | 时间/action/target过滤 | 最小脱敏证据，不公开原始个人资料 |

## 5. 人工恢复工单

| 顺序 | 操作 |
| --- | --- |
| 1 | 确认稳定UUID/当前名，转经核验的私密渠道 |
| 2 | 至少两项独立依据，对照既有可信记录 |
| 3 | 记录引用/结论、操作者/原因、issue request/audit/到期 |
| 4 | 一次性私密交付，记录结果，不记明文 |

群身份/公开资料/单截图/IP不足；无法核实不签。管理员使用自己的离线码或主机维护，不签普通人工恢复凭据。重发废旧未用人工码。

## 6. 注销争议

| 状态 / 操作 | 含义 |
| --- | --- |
| PENDING / HELD | 本人可按密码流程取消；仍无服务资格 |
| hold | 记录requestID/原因、有限期限/复查和非敏感公开摘要 |
| release | 保留原30天schedule；已到期可下轮立即执行 |
| EXECUTED / DELETED | 不复活；不承诺业务站历史内容自动删除 |

敏感操作失败先核commit状态，保存requestId/时间/类别，不盲目重复签发。
