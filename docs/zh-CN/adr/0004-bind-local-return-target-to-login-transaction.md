# 返回目标绑定登录事务

状态：用户于 2026-10-04 确认；默认单 JVM 和应用自有链实现已验证，部署验收待完成。

英文对应：[Bind local return targets to the login transaction](../../adr/0004-bind-local-return-target-to-login-transaction.md)。

## 背景

B5 丢弃 returnTo、默认登录总回首页。直接用回调参数或主体/Session 全局目标，会导致目标替换或授权事务串用；starter 若在应用成功处理器之前强制跳转，也可能跳过业务检查。

## 决策

用户选择“自有链显式接入”，不强制接管所有链的跳转：

- 无效、重复、过长 returnTo 在启动授权前返回 no-store 400。仅接受保守应用内路径，拒绝外部源、authority、fragment 和歧义路径/控制编码。
- 校验后的目标和创建时间存到标准 Spring 授权请求内部属性，与 state/nonce/PKCE 一起；目标不作为 OP 参数。按既有协议契约十分钟到期。
- 在发起本地 Session 内匹配 state，授权请求只移除一次。同 Session 新授权替换旧请求，保留 Spring 单个待完成请求行为。错误 state 不消费正确请求。
- 回调 returnTo 不能覆盖保存目标；Code/nonce/签名验证和 Session 登记成功后，才在该请求公开已验证目标。
- 默认成功使用目标，无目标保留首页。应用自有成功代码始终执行，可读 `LasSsoReturnTo.target(request)`，或在业务检查后将 `LasSsoReturnToSuccessHandler` 作为最终跳转步骤，不强制绕过它。
- 默认消费在同 JVM 的 Servlet Session 对象上同步；不新增共享 schema/分布式锁/多实例保证。

## 替代方案

- 应用成功之前强制跳转：拒绝，可能跳过入站、本地资格等业务检查。
- 独立 Session 全局保存一个目标：未选，易和后来的授权混用，缺准确 state/期限/单次绑定。
- 回调读取目标：拒绝，不可信响应不能决定认证后去向。
- 任意相对/绝对 URL：初期拒绝，保守校验足够；扩展路径语法或外部允许列表另行决定。

## 兼容与后果

无目标默认行为及原应用成功处理器保持；显式目标现在影响默认链。无效值拒绝，不静默忽略。部署前不带创建时间属性的待完成授权会拒绝，须重新发起登录。手动替换 resolver/repository 须保留时间/state/目标契约；最终跳转不能绕过登记或信任回调参数。

目标只是导航，不是业务权限、近期密码证据或保存的写操作；目标页继续鉴权，不自动重放危险写操作。真实浏览器/容器/代理、共享 Session/重启/消费/多实例独立验收。见[返回目标契约](../integration/login-return-target.md)，ADR 不批准部署或业务 DB 变更。
