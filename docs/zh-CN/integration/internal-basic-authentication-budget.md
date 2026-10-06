# 内部第一方 Basic 认证预算

- 状态：2026-10-04 A3实现/受控验证完成，生产传输/容量待验。
- 英文对应：[Internal first-party Basic authentication budget](../../integration/internal-basic-authentication-budget.md)。
- 决策：[ADR-0005](../adr/0005-independent-internal-basic-failure-budget.md)，用户批准内部独立失败/在途预算。

## 1. 范围/配置

既有内部路径：

```text
GET /internal/v1/principals/{subject}/service-access
Authorization: Basic <已登记第一方凭据>
```

只有ACTIVE/FIRST_PARTY、登记client_secret_basic并匹配Secret才取得服务身份；不启用Client Credentials Grant、不用人类Token/Cookie替代客户端认证，保留A1规范化路由/显式角色。

默认配置：

```yaml
las:
  sso:
    first-party-authentication:
      client-failures: 10
      network-failures: 20
      window: PT15M
```

限额正数，window正整秒Duration，server扫描验证；固定epoch对齐非滑动。键为client ID及既有Servlet remote address的IPv4/24、IPv6/56。既有表按FIRST_PARTY_AUTH_CLIENT/NETWORK存HMAC，非凭据/完整IP；本步不改trusted proxy/address规则，生产另验。

与OAuth CLIENT_SECRET_FAILURE_CLIENT/IP独立，OAuth耗尽不独立耗内部，内部不加OAuth失败；不意味着全部协议限流并发已修。

## 2. 占用/归还

过滤器有界解析头，预算在**一个短新事务**按network/client占用；条件UPSERT只有未满才加，禁止并发超额。第二桶拒绝回滚第一桶，本次不部分计费，拒绝后计数封顶不无限加。

提交后查客户端、active第一方资格并matches，预算锁不覆盖哈希。未知/不合资格无昂贵哈希但保留失败成本。正确认证在另一短事务各归还本次一个、之后才放行；成功不清旧失败，最后一项删桶（schema不许0），其他减1。本次receipt幂等，原窗口过期清理不会重建或改新窗口。

失败/中断/崩溃保留至到期，所以计数是**失败＋当前窗口获准在途**，不是完成成功查询配额。25次串行合法已验仍200且成本0；当前窗口并发合法仍可能暂满，不另限成功吞吐或加缓存。

耗尽时正确Secret也哈希前拒，直到到期或在途成功归还；Retry-After只是上限等待提示，不保证准时恢复。已知ID/匿名/共享网段可被恶意失败暂阻，是接受取舍非永久封禁。窗口切换允许跨窗口重叠哈希，没有全局并发/所有请求rate shield、Secret缓存或持久reservation lease。

## 3. 输入/响应

Authorization只一个/最多4096字符，Basic格式合法、client ID非空最多128无控制、Secret非空；既有解码不变。缺失/无效/超长/多头使用`<unknown>`及网段成本，无client查询/哈希；语法合法但轮换unknown ID仍网段计数。不用client_id query回退。

| 结果 | 响应 |
| --- | --- |
| 预算获准但认证无效/缺失/非活动第一方 | 既有401 Basic realm，no-store |
| 客户端/网段耗尽 |429，整秒Retry-After，no-store problem+json/RATE_LIMITED |
| 占用/客户端DB/归还事务不可用 |503，no-store problem+json/IDENTITY_SERVICE_UNAVAILABLE，不放认证 |
| 合法及归还成功 |既有service-access响应不改主体状态字段 |

429/503 body requestId与X-Request-Id一致，沿用现有filter attribute，不存在则在此位置生成server UUID。失败清本次SecurityContext，不将旧人类登录当服务角色。日志只有非秘密异常类别/静态耗尽消息，无Secret/header/SQL/异常文本；HMAC窗口/针对性日志是证据，不是指标/运维告警完整实现。

server filter构造器现在需budget/network/request-ID，手动构造须更新；非starter API变化。只内部路径新增预算，普通登录/OAuth各沿原配置，无migration/deps/locks/cache改变。

## 4. 已执行验证

15:23 Asia/Shanghai原单项红灯，第11错误Secret仍401；接入预算后429，matches仅10次。spy委托真实encoder，直接调用计数证明哈希前，不靠耗时猜测。

最终**25项**真实PostgreSQL18.4/Spring Security/MockMvc/Argon2 client；encoder/budget spy仅仪器或明示fault：

- 错Secret cutoff、成功归还不累计/不清旧失败；跨网段client、IPv4/6轮换unknown网段；
- 缺失/错误/超长/空/多头/控制/长ID、不合资格、编码路由；OAuth桶隔离；
-25并发错误在hash处暂停：10进入matches、15返回429，预算DB锁已释放；
- reserve/refund故障响应/日志，加**真实SQL第二桶故障/归还故障**证明整体回滚/503；
- 固定窗口精确边界、旧receipt幂等/新窗口保护/清理、config、requestId/canary。

约束/function/trigger只在一次性数据库故障注入、finally删除，无项目migration或用户DB操作；Clock直构service也用真实TransactionTemplate/manager。不是live断库/kill/restart/负载/分布部署。

server目录Java21命令：

```powershell
.\gradlew.bat :test --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --no-daemon --console=plain
# 原红，后扩套绿。

.\gradlew.bat :test --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --tests "org.las.sso.security.ClientAuthenticationRateLimitConverterTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

最后专项**32**（25+6+1）无失败/错误/跳过；2026-10-04约15:46–15:49强制全量**203server/176starter**零失败/错误/跳过，12actionable全执行，BFF重编译、NO-SOURCE。红灯XML后覆盖，输出/状态地图§3.8保留阶段。

## 5. 剩余验收

2026-10-05query新增stateEpoch与另8worker80validTCP观察不累计failurebudget，生命周期/restore-shaped/wireversion见[epoch契约](../implementation/service-access-epoch-contract.md)，不扩大成全部A3concurrent503proxycapacity/productionSLA。identifiedartifact/兼容见[开发交付](development-delivery-2026-10-05.md)。

后续[A1/A2ServletHTTP矩阵](../implementation/servlet-path-security.md)已验直连Tomcat/TCP root与/passport Basic门禁/退款/clientbudget拒绝，不验全部A3传输故障/并发容量。TLS/proxy网段、生产Argon2/DB/连接池、合法高流量/窗口边界/指标告警/重启多实例仍待验；PostgreSQL原子非live拓扑。混合config/window需运维，预算不新串行client变更与在途Secret。SSO-04恢复version吞吐独立；S3–S6/D1后续各有范围证据，以状态地图校准。A3步骤未commit/发布部署。
