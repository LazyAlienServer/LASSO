# 退出投递与撤销回执

- 状态：2026-10-04 B3 已修复并经 PostgreSQL/loopback HTTP 验证，部署验收独立记录。
- 英文对应：[Logout delivery and revocation acknowledgements](../../integration/logout-delivery-acknowledgements.md)。
- 适用：服务端 `BackChannelLogoutDelivery`、starter `LasSsoTokenRevocationQueue`。

## 1. 成功确认

| 路径 | 确认成功的状态 | 其他结果 |
| --- | --- | --- |
| 服务端 → 已登记 BFF Back-channel 接口 | 仅 **200 或 204**，沿用 LAS 接收契约 | 重试，不能仅因 HTTP 请求完成就设置 `processed_at` |
| Starter → Discovery 的 OP revocation endpoint | 仅 **200**，依据 [RFC 7009 §2.2](https://www.rfc-editor.org/rfc/rfc7009.html#section-2.2) | 重试，不宣称远程撤销已确认 |

`202 Accepted`、其他未约定 2xx、重定向和 4xx/5xx 均不是确认。两条路径显式禁止现有 HTTP 工厂跟随跳转，通过 `RestClient.exchange` 读取状态，而非依靠 retrieve 默认错误规则。响应体/`Location` 不用来确认、不进入日志；重试仍访问原 URI，Logout/Refresh Token 和客户端凭据不转发给跳转目标。

RFC 7009 要求忽略撤销响应体；已无效 Token 也返回 200，因此该回执不证明找到了此前有效 Token，也不是每个下游服务状态的独立观察。同样，Back-channel 200/204 是接收方的协议确认，发送方无法独立证明任意接收数据库已经产生哪些效果。

## 2. 重试与耗尽

B3 不新增迁移、认证机制、HTTP 依赖或替换重试策略。原连接/读取超时仍为 3/5 秒，应用级上限仍八次；失败第 `n` 次（1–7）后保留原随机指数退避，延迟 `1..min(300, 2^n)` 秒。没有顺带新增 `Retry-After`、按状态提前死信或熔断策略。

**服务端 outbox：**保留认领与条件完成/重试事务、claim token 所有权。未确认状态保持 `processed_at` 空，递增 `attempt_count`，写 `HTTP_<status>`，清认领字段并设置 `available_at`。八次失败设置 `dead_lettered_at` 并记错误类别；死信不再认领。同批失败接收方不阻塞另一个任务完成，后续有效重试清 `last_error_code` 并标处理完成。本地 `GLOBAL_LOGOUT_REQUESTED` 标记仍与 HTTP 投递事件不同。

**默认 starter 队列：**`submit` 正常返回仍只代表进程内提交，不是 OP 回执或持久接受。未确认 HTTP 结果继续既有重试，八次后记录非秘密类别并终止该请求；没有持久死信记录、回执 API、重启存活或多实例保证。持久 `LasSsoRevocationTaskSink` 仍由消费方按公开接受契约实现，B3 不提供其 schema 或使提交/本地退出原子化。

HTTP 错误写/记为 `HTTP_<status>`，传输失败用 `ResourceAccessException` 等非秘密异常类型；耗尽/worker 日志不附响应体、URI/传输异常文本、Token 或客户端 Secret。这里只验证针对性日志路径，不是全应用/生产日志扫描。

## 3. 已执行验证

2026-10-03 23:55 左右（Asia/Shanghai）两条新单项回归在旧代码上先失败：服务端将 302 标 processed，默认 starter 不重试原端点。修复后转绿，扩大测试在 2026-10-04 执行。

- `BackChannelLogoutDeliveryIntegrationTest` **20 项**：真实 PostgreSQL 18.4 Testcontainers、Spring 投递/签名及实际 HTTP；覆盖 200/204、未约定 201/202/206、301/302/303/307/308、400/401/429/500/503、重试后成功、完整八次失败/死信不再认领、断连、两接收方互不影响、claim 清理和安全日志。fixture 提前设 retry 到期而非等待随机生产退避，DB 状态更新/HTTP 均真实。
- `LasSsoTokenRevocationQueueTest` **18 项**：真实 HTTP；覆盖精确 200、Basic/form、异常 2xx/跳转/错误、恢复后成功、传输失败、八次耗尽、不转发及安全日志。一项使用实际默认调度器，矩阵通过包内测试构造器注入可控调度器，记录延迟并直接执行 HTTP，不改变公开构造器/生产调度，也不是长时计时测试。
- 相关专项：服务端 **73 项**（20 + 既有 53 持久化）、starter **20 项**（18 + 既有两项当前退出），零失败/错误/跳过。
- 2026-10-04 00:10–00:12 左右（Asia/Shanghai）全量强制重跑：

  ```powershell
  .\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
  ```

  **服务端 178 + starter 71 项**，零失败/错误/跳过；参考 BFF 重新编译、自身 `test NO-SOURCE`。

响应秘密 canary、真实 Logout Token 和请求 Token 与捕获耗尽日志/数据库错误码作对照。早期红灯 XML 被后续 Gradle 覆盖，命令输出及[状态地图 §3.5](../../implementation/status-issues-and-plan-2026-10-02.md)保存阶段记录。扩大过程中修正测试 assertThat 重载编译歧义，这不是生产缺陷。

## 4. 剩余验收

未执行真实 TLS/反向代理、正式 BFF 或真实 OP 撤销。超时/慢响应占用、完整时长调度、过期 claim 竞争、崩溃/重启恢复、容量和多实例部署不由状态矩阵覆盖。它验证发送侧确认/重试，不能关闭 B4 接收 claim/重放/执行语义或 B2 退出先于登记的协调。

B4、持久接受、版本交付和真实消费端 E2E 分别开放。改动未提交、发布或部署，绿色测试不代表生产就绪。

随后 2026-10-04 B4 严格验证/单 JVM 执行回执已验证，用户批准 ADR-0003；最新全量为 178 server / 117 starter。见独立[接收与 adapter 升级契约](backchannel-logout-receipts.md)，持久/真实消费及登记时序仍有限制。
