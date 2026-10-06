# Back-channel 退出验证与执行回执

- 状态：2026-10-04 B4 实现/受控验证完成；部署/持久验收待完成。
- 英文对应：[Back-channel Logout validation and execution receipts](../../integration/backchannel-logout-receipts.md)。
- 决策：[ADR-0003](../adr/0003-logout-replay-execution-receipts.md)，用户明确同意增量 SPI 与旧 adapter 运行失败关闭。

## 1. 重放/执行前验证

默认命名 `lasSsoLogoutTokenDecoder` 用 Spring/Nimbus、固定 registration issuer、RSA JWKS、仅 RS256。`typ` 必须 `logout+jwt`；错误签名、未发布 kid、算法、issuer/audience 拒绝。即使替换 decoder，自动配置也要求非空固定 issuer。

接收器要求：

- issuer/jti/sid 为非空字符串，可选 sub 若出现须非空字符串；
- audience 包含本 client ID，禁止 nonce；
- events 为对象且 logout-event 值为**空对象**；
- 必需 iat/exp，`exp > iat`，寿命最多 120 秒；
- iat 不晚于 `now + 60s`、不早于 `now - 180s`；
- `now < exp + 60s`，精确偏差到期边界拒绝；nbf 如出现保留成熟 decoder 验证。

第一方必需 sid，不能用 sub-only 扩大失效范围；有 sid 可不带 sub。每次重传均先验证再查回执。

Nimbus 会在 Spring 转换前把数值 sub 转文本。默认 decoder 因此在成熟 decoder 完成签名/issuer/用途/时间验证后，使用 Nimbus 自身 SignedJWT payload API 检查原始身份 claim 类型，不手写 Token 分割、JSON 或验签。替换 decoder 的消费方须保留密码学/原始类型检查，接收器无法恢复已被自有 decoder 转换的类型；仍执行配置 issuer/audience/语义检查。

## 2. Replay SPI 升级

`LasSsoLogoutReplayStore.accept(...)` 保留原抽象方法，但只绑定摘要；接收器改调用新增：

```java
Result process(String issuer, String tokenId, String contentDigest,
               Instant retainUntil, Runnable action);
// Result: SUCCESS, CONFLICT, IN_PROGRESS
```

接口默认抛错。**只有 accept 的旧 adapter 保持源码兼容，但未升级前接收为 503，不执行 Session**。这是用户确认的有意变化，避免把摘要记录当执行成功；应升级实现而非允许 Bean 覆盖或退回 accept。默认类实现两方法，accept 本身不创建成功回执。

process 契约：

1. issuer/jti 键先绑定原 Token SHA-256 摘要；不同内容即使在失败后也返回 CONFLICT。
2. 原子占用一个 action，相同内容执行中返回 IN_PROGRESS，其他键不必等待该 action。
3. 执行正常结束才能记录成功；相同已成功重传返回 SUCCESS，不再执行。
4. 失败释放占用、保留摘要，相同内容可重试、冲突继续拒绝。
5. 按 `retainUntil = exp + 60s` 保留；默认不会仅因时间过去删除执行中项，过期且未执行的项可清理。

默认为 ConcurrentHashMap 单键原子状态，action 在该边界之外执行，仅单进程，没有分布式事务、租约、持久或崩溃保证。持久 adapter 自行确定正确占用/完成/恢复事务及幂等 Session 效果；失效成功但回执保存失败仍应安全重试。本次不规定业务 schema/租约政策。

## 3. 执行与 HTTP

| 结果 | 响应 |
| --- | --- |
| Token 无效/内容冲突 | 400 |
| action 完成/相同缓存成功 | 204 |
| 相同 action 尚在执行 | 503 |
| 重放存储、Session 执行、旧 adapter 未支持或 decoder 基础设施故障 | 503 |

接收响应均 no-store。已知无效 JWT/claim 与基础设施/执行错误分开；日志只记非秘密类别，不附 Token、claim 文本、SQL/adapter 异常信息。真实 JWKS 503 已验为可重试 503；自有 decoder 应以 BadJwtException 表示无效输入、相应 JwtException 表示可用性故障。

`LasSsoBackChannelSessionStore.invalidate` 必须幂等，仅所有匹配索引会话已结束才正常返回（含无匹配/已结束），未完成须抛错。默认索引不再先删除：成功后移除，尚存活失败会话保留；尝试全部目标后报告部分失败，使重试只处理剩余存活。已失效会话可清理，不把所有 IllegalStateException 都当成功。

回执仍仅协调单JVM有效Token执行、缓存成功不重执行。B2默认迟到登记保护现由Session Registry sid标记负责，保留本地绝对期限而非jti回执短expiry，见[ADR-0008](../adr/0008-retain-sid-revocations-before-local-registration.md)。自有/持久adapter须自行等效保护；业务在途/restartcrash/任意multi-instance独立。

## 4. 已执行证据

原始专项 18 项/13 失败，先于修复执行：签名异常 claim 被接受、数组事件异常、重复执行、adapter 错误被当 400、索引失效失败却 204。第一次修复只剩旧 accept 测试按预期 503；升级该自有测试 adapter，另保留旧 adapter 失败关闭回归。

最终全量内：

- `LasSsoLogoutTokenValidationIntegrationTest` **33 项**：真实 RSA、HTTP JWKS、自动配置 decoder/实际接收器；30 种缺失/空/类型、事件/时间、签名/kid/算法/issuer/audience/用途/nonce/nbf 异常，以及相同成功缓存、sid-only 正常和真实 JWKS 503。数值 sub 因 Nimbus 提前转换在扩展后仍红，原始类型检查后转绿。
- `LasSsoBackChannelLogoutControllerTest` **15 项**：部分/不完整失效、存储/decoder 故障、失败后冲突、相同并发、缓存成功、60 秒保留边界、process 自有实现/accept-only 兼容、自有 decoder 固定 issuer；该层显式 decoder/Session fixture，针对性日志 canary。
- `DefaultLasSsoLogoutReplayStoreTest` **5 项**：原摘要行为、失败重试/回执、冲突、执行中不清除/到期，固定 latch/Clock。
- 既有 14 签名回调和一项 Bean 替换继续通过。最后专项在补 issuer 断言前 **67 项**零失败/错误/跳过；最终全量包含 **68 项**相关子集。

服务端目录执行：

```powershell
.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

2026-10-04 约 01:59–02:02（Asia/Shanghai）：**178 server + 117 starter** 零失败/错误/跳过，12 actionable tasks 全执行，BFF 重编译/`test NO-SOURCE`。相对 71 项，B4 新增 46；无 schema/依赖/锁更改。早期红灯 XML 后续覆盖，命令输出/状态地图 §3.6 保留证据。

## 5. 尚未验收

签名验证层调用实际 Controller，不是 live Servlet/MVC 参数绑定，回调仍有已说明请求/事件 fixture。真实 SSO/TLS/代理/BFF/浏览器、消费持久 adapter/DB 回执、多实例/崩溃、吞吐/保留容量、通知登记时序未验。服务端既有 PostgreSQL 套件已重跑，但 B4 没新增持久实现。改动未提交/发布/部署，剩余问题见[状态地图](../../implementation/status-issues-and-plan-2026-10-02.md)及独立 B3 发送契约。
