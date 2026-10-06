# 本地登记前保留 sid 撤销标记

状态：2026-10-05用户明确确认；默认单JVM已验证，持久/多实例验收独立。

[English](../../adr/0008-retain-sid-revocations-before-local-registration.md)。

## 背景

B2原success wrapper已修回调后窗口，但有效Logout在Code兑换前/中到达时可能无本地Session而成功；迟到callback随后登记已撤销sid。默认/自有两实际签名CodePKCE/JWKS/安全链回归先红。

## 决策

- 默认Registry在invalidate记录sid标记，包含无匹配/失效失败。
- 期限为配置sessionAbsoluteLifetime，自每次invalidate起算，正值最多14天/默认14天；重复Logout只延长。与短issuer/jti replay回执期限不同。
- sid标记/check与索引publish/snapshot用同一短state lock；Session/container/lifecycle在锁外。登记先则Logout快照包含，Logout先则登记拒绝，不按subject封禁自然人。
- callback拒绝沿用登记失败503/清Token/结束Session/提交本会话撤销，不调用业务success。register仅抛错，不自行先invalidate，保留cleanup读取Token快照机会。
- 保留SPI签名/no-arg constructor，新增Duration构造器，auto-config传当前properties。自有store自行提供等效register/invalidate协调，不静默包装/创建业务schema。
- wire Logout仍需验签sid。legacy直接subject invalidate仍仅当前快照；默认Registry每配置issuer/client单独作用域，SSO sid不可复用。

## 影响和边界

标记进程内，流量触发最多每分钟全量过期清理，每sid精确expiry检查；不为容量淘汰仍有效标记。内存随期限内唯一撤销sid增长，容量/期限/墙钟假设须验。restart丢marker/index/receipt；初期BFF重启废登录，但跨restart旧在途callback/任意shared store不由本修复保证。

非分布式事务/持久撤销队列/业务授权屏障，不取消已获授权在途操作。durable/restart/multiinstance/crash独立。回归涵盖callback前/HTTP exchange中Logout、错误签名不污染、不同sid、受控并发顺序、部分失败重试/监听重入/期限边界。
