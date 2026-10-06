# LAS SSO 测试与验收计划

- 状态：第一版实施规格
- 范围：SSO、LASLOG BFF 适配、AlienCommon BFF 适配、退出传播和运维恢复
- 依赖：[总纲](../../LAS_SSO_DESIGN.md)及 `docs/` 各分类目录下的详细设计

## 1. 质量门禁

生产发布必须同时满足：

1. 单元、数据库集成、API、OIDC/BFF 契约和浏览器 E2E 全部通过。
2. 没有未处理的 Critical/High 依赖漏洞；例外必须有书面影响分析和到期日。
3. Discovery、JWKS、Authorization Code + PKCE、刷新轮换、单应用退出和全局退出完成真实进程验证。
4. 管理员 bootstrap、首次强制改密和离线重置完成演练。
5. PostgreSQL 备份能够在空环境恢复。
6. 日志检查确认没有密码、恢复码、客户端密钥、Token 或 Cookie。
7. 关键负向测试通过，不能只证明正常路径可用。
8. 所有 `.example`、默认 Secret 和测试客户端都不在生产配置。

## 2. 测试层级

2026-10-07 .7：actual.6shortATmixed发现refresh替换IDToken→oldhintglobal400，新增真实refreshPG回归先red，V15HMAChistory/cascade/typeonlylookup修green。扩断言preupgradecurrentcapture/two32bytehash/未登记return拒/ATRT不查/cascade重复拒，全量505server193starter37ref=735零失败错误跳过20tasks。finalactualGrafanaViewer1440/390八panelcanvas/permission403，两chain301/302s五subject9browsermixed12,598operation/120refresh0reuse/16global/sentinel保持，退出后inflight401SESSION_EXPIRED只terminal允许。配对[实现English](refresh-logout-hint-history.md)／[中文](../zh-CN/implementation/refresh-logout-hint-history.md)、[验收English](../operations/grafana-mixed-load-2026-10-07.md)／[中文](../zh-CN/operations/grafana-mixed-load-2026-10-07.md)。小时級rampsoak/historysizing/production未验。

2026-10-06 .6新增17=config6/commitencoder3/disabledexposure1/actualdualportHTTPPG7；原refreshreuse/backchannelreceipt加精确counter断言，全量504server193starter37ref=734零失败错误跳过20tasks/PG实际跑。私有finalJAR/Prome3.6AM0.28.1 authenticatedwebhook真backlog1m/DBsnapshot1m/scrape2m firingresolved6通知，表达式窗口不缩短，dashboard10query过/nonliveGrafana；TLSfinal双链重验。依据[English](../operations/monitoring-and-alerting.md)／[中文](../zh-CN/operations/monitoring-and-alerting.md)，剩余metrics/所有7rulelive/productiontransportoncall/持续load另验。

2026-10-06 .20261006.5新增真实DBreadinessstop/start回归，先red旧200→dbgroup修绿；固定fixtureloopbackport避免Dockerrestart动态端口误判，readiness200→503→200/liveness200/粗status无详情。最终487server193starter37reference=717零失败错误跳过20tasks，finalJAR TLS双链/admin/restore重验及有界Argon2querylogin/JVMpoolSQL通过。记录：[English](../operations/final-artifact-acceptance-2026-10-06.md)／[中文](../zh-CN/operations/final-artifact-acceptance-2026-10-06.md)、[容量English](../operations/capacity-readiness-2026-10-06.md)／[中文](../zh-CN/operations/capacity-readiness-2026-10-06.md)。无领域metrics/alert链/持续productionload完成推断。

2026-10-06 .20261006.4依赖处置后强制716=486server193starter37reference，20tasks全执行、0失败/错误/跳过，实际PG与root/context升级Tomcat运行。Trivy三锁234/解包JAR150包记录、基线17不同CVE不再报告；Semgrep221路径保留1人工误报/0error，Gitleaks source/JAR/allhistory各0匹配；直接opaqueJAR0package不算通过。工具/DB/规则hash及覆盖边界：[English](security-scan-2026-10-06.md)／[中文](../zh-CN/implementation/security-scan-2026-10-06.md)。原.3外部TLSbrowser/维护演练保历史，本轮未重复；生产image/log/DAST/容量仍另验。

2026-10-06后续.20261006.3配置修复保716全量零失败错误跳过20tasks；外部实际Nginx/私有containers/HTTPS完整证据：clientForwardedPrefix扰callback旧红→repoinclude绿，finalJAR/httpsprofile defaultappchains dualbrowserCookie/reauth/currentglobalHTTPSBack-channel、CAhostname反例/HTTP不发Cookie/伪IP保持fixtureCAPTCHAbudget、encodedlogin429。无新增mock配置tests，source/deployprofilehash及browser结果另记。信任/fixturethreshold/production边界：[English](../operations/tls-proxy-acceptance.md)／[中文](../zh-CN/operations/tls-proxy-acceptance.md)。

2026-10-06后续.20261006.2 OIDC revokedcookie续行两red→green/新增7（reauth18），related71阶段绿；最终486server193starter37ref=716零失败错误跳过20tasks。最终actualJAR/scripts/PTY/Edge空库hiddenbootstrap/强制改密（CSRFOrigin/wrong不改）/recoveryshowdownloadreload/offline resetcancel mismatchreuseinvalid无变/成功oldpasswordCookieATRT拒/新login/globalBack-channel过。来源[English](../integration/development-delivery-2026-10-06-2.md)／[中文](../zh-CN/integration/development-delivery-2026-10-06-2.md)，非promotion/live24hexpiry/productionTLSofflinecustody/fullUI。

2026-10-06新增restore事务12/command6共18，最终479server193starter37ref=709零failerror skip20tasks。真实pgdumprestore/twoPKCS12/PTY脚本expectedepoch/旧cookieCodeATRT拒/newepoch低counter、fresh双browser/globalBack-channel/outboxdeletion调度恢复已验；testconsumer generation不是业务runtime，productionbackupkeyRPORToTLS独立。证据：[English](../integration/development-delivery-2026-10-06-1.md)／[中文](../zh-CN/integration/development-delivery-2026-10-06-1.md)。

最新2026-10-05 .4参考加密持久queue有自身37测试（34PG/AESHTTP+3context），不再NO-SOURCE。与server461/starter193共同强制691零failerror skip20tasks；实际Edge默认/自有pausedworkercommit→停JVM→新JVM撤销→旧RT400invalid_grant/别授权独立。fixturelease/receiptSQLfault不是每指令livecrash，presubmitgap/persistentSessionreplay/keyrotationproductioncapacity独立。来源：[English.4](../integration/development-delivery-2026-10-05-4.md)／[中文](../zh-CN/integration/development-delivery-2026-10-05-4.md)。

| 层级 | 目标 | 建议工具 |
|---|---|---|
| 单元测试 | 规则、状态机、Claims、错误映射 | JUnit 5、AssertJ、property-based library |
| 数据库集成 | 迁移、唯一约束、事务、并发 | Testcontainers PostgreSQL |
| PostgreSQL 短期状态集成 | TTL 判定、原子限流、锁、单次消费和分批清理 | Testcontainers PostgreSQL |
| Spring Security 集成 | 登录、CSRF、会话、OAuth/OIDC 端点 | Spring Boot Test、MockMvc/WebTestClient |
| 协议契约 | Discovery、Token/Claims、BFF 一致性 | 独立黑盒测试包 |
| 浏览器 E2E | 跳转、Cookie、页面状态、退出 | Playwright |
| 安全测试 | SAST、依赖、DAST、秘密扫描 | 成熟扫描器组合 |
| 运维演练 | 备份恢复、换钥、管理员恢复、故障 | 隔离测试环境 |

集成测试使用真实 PostgreSQL，不以 H2 或内存 Map 代替最终一致性约束。

## 3. 测试数据与时间

- 每个测试生成独立 UUID v7 和不重复 client ID。
- 用可注入 `Clock` 测试 2 分钟 code、15 分钟 AT、RT family 绝对 14 天、BFF 会话 14 天、90 天 SSO 记住会话、30 天注销等边界。
- 密码和恢复码测试值只存在测试资源，不复用生产 Secret。
- 日志测试使用特殊 canary 值，测试后扫描所有日志确认 canary 不存在。
- 测试数据库迁移从空库开始，也从上一发布 schema 升级。
- 并发测试固定随机种子并保存失败重现信息。

## 4. 身份与数据模型

### 4.1 UUID 与用户

- 新注册 `users.id` 是有效 UUID v7。
- 两个连续生成 ID 不重复；并发生成不重复。
- OIDC `sub` 与数据库 UUID 字符串完全一致。
- `LasUserPrincipal.getName()`、`Authentication.getName()`、`OAuth2Authorization.principalName` 与 `sub` 都是同一 UUID v7；修改用户名后保持不变。
- 业务代码没有按用户名、显示名或邮箱建立永久身份映射。
- 数据库和响应中不存在 email 字段/claim/scope。

### D1依赖同意页补丁/消费解析（2026-10-04已执行）

- 官方CVE-2026-47877/7.1.1BOM与renderer源码重核，三个Security/AS7.1.0→7.1.1对齐；Nimbus JOSE10.9.1、clientSDK11.38.2，Boot/Framework/Jackson基线保持。三锁实际生成/审查，既有starter testJackson保留。
- DefaultConsentPageDependencyTest5新增actual dependency endpoint/default page，converter/auth-result fixture隔离clientId/state/principal/requested/approved scope，旧raw红/新escape绿。第一六400为converter setup未到渲染、删不适用user_code并修fixture，非XSS证据；不称应用允许这些字段或浏览器script已执行。
- AuthorizationConsentIntegrationTest4新增实际配置Spring/PostgreSQL/MockMvc：partnerapprove/cancel、不同主体拒绝、一方skip、JDBCConsent/CodePKCE签名UUIDaudnonceRT/原state保留；原client注入state被内部consent state替换而不渲染，非原应用漏洞复现。
- 本地临时filepublication独立Gradle/native Boot4.1.0消费：.module api platform与POM-only/ignore redirection两方式全部Security7.1.1；初POM-only7.1.0红，显式config/client/JOSE依赖修复。无MavenCLI/consumer任意旧锁override/托管发布保证。
- 110server/131starter专项与强制323server/176starter零失败/错误/跳过，18task全执行/BFFcompileNO-SOURCE/两bootJar/普通metadata生成；bootJarSecurity全7.1.1及Nimbus补丁实际检查。renderer/consent XML14:25:25.244/21.810UTC。

本轮指定D1依赖修复/兼容已验，完整SCA/SAST/Secretlog/browserTCP/script/consentCSP/可用性/TLSproxy/生产deploy/liveconsumer仍独立。命令/来源/阶段见状态§3.13及[English](spring-security-consent-patch.md)／[中文](../zh-CN/implementation/spring-security-consent-patch.md)。

### A1/A2直连Servlet路径/风控（2026-10-04已执行）

- 两新增suite各48，root与/passport，HttpClient真实TCP/Tomcat11.0.22/Security7.1.1/PostgreSQL18.4/Argon2/JDBCSession，无MockMvc/auth注入。A1专用Basic/三literal编码/knownunknown/匿名真实Cookie拒/错partnerdisabledclient/服务与人会话隔离/clientbudget429/五歧义内部target；A2三loginpath成功/CSRF计数/封禁/CAPTCHAHTTP正proof单次与错目的网段过期、proof与CSRF/block先后、真实错误触发阈值/五歧义login。
- startupassert loopback/forwardnone与240hwindow，test阈值2/3、零jitter，clear预算仅一次性库。SecureCookie由helper手动HTTP转发，Origin固定testissuer；非browserTLS/SameSite/__Host/proxyorigin/生产窗口验收。转发头禁用时spoof不能绕block不等于trustedproxy部署已验。
- 初40setup404未实现/api/v1/me、CAPTCHA403Origin错；child properties覆盖造成48setup失败、generic400path回显2过严断言，分别改现有/profile/issuerOrigin/继承TestPropertySource与无资格字段断言，非新产品漏洞。既有修复不造旧code red，本轮生产源码/schema/deps/policy无变化。
- 96新增＋181相关（48+48+6+25+53+1）与强制419server/176starter零失败/错误/跳过，12task/BFF重编译NO-SOURCE；root48XML15:32:45.005UTC、context48 15:32:33.975UTC，真实listener/context证据保留。

productionreverseproxy归一/信任/TLS/browserJS/可访问性、任意URI/containerdispatchmethod、并发CAPTCHA/生产thresholdwindow/capacity/crashrestart/fullSecretlog未验，SSO-04恢复终态版本吞吐及S2/B1完整消费仍独立。命令/阶段见状态§3.14及[English](servlet-path-security.md)／[中文](../zh-CN/implementation/servlet-path-security.md)。

### 2026-10-05 S2/SSO-03/04/06持续交付验证

- S2新增23DBMockMvc：九双向writerpair/rotationrotation/pgblockinglatch、实际Basicoldrejectnewaccept/OAuthtokenoldinvalid_clientnewinvalid_grant、独立client不阻、disable-rotate-enable不复活、五audit/outbox/idempotencySQLfaultrollback与replay/conflict/retry。原生产client无新修改，OAuth400假定错修test，91专项/combined110绿。
- SSO-03publicconfiguretrustedConsumerBuilderoverload兼容原，PKCEcustomizer后配置standardparams/localstate保持；两签名callback包外consumer验证promptmax_age/state noncePKCEtarget/验证IDToken时间读取及default不透传browserquery，30callback+45target+10chain=85绿。OP/Servletfixture非liveLAS/RP15m门禁。
- SSO-04用户ADR-0007新增stateEpoch复用globalepoch，七字段同SQLsnapshot，knownunknown均epoch，missingblank503/no-store；17actualDBwirelifecycle/precedence/history/rollback/正常passwordrecover不改eligibility/requiredexit/未commit读/newgenerationlowercounterrestshape。consumerinflight代次屏障与actualbackup独立。
- 1小loadactualTCP/Argon2/DB4warmup＋8worker80query全200无失败budget累积，fullrun1343ms/59.57rps/p50112.9/p95203.5/p99232.1ms，本机记录非productionSLA/持续capacity/consumer数量。
- identified0.1.0-dev.20261005.1 forced460server178starter零失败错误跳过18tasks，两bootJar/metadata/refBFFcompileNO-SOURCE；263sourcefingerprint/manifestJar三锁hash。独立原生module/POM-only本地制品消费者编译旧新publicAPI且Security全7.1.1，无composite/inheritedlocks；非MavenCLI/liveconsumer/正式release。

命令/时间/来源见[client](client-secret-concurrency.md)、[query](service-access-epoch-contract.md)、[开发交付](../integration/development-delivery-2026-10-05.md)及各zh-CN counterpart。严格DTO/cache要更新epoch，默认build仍SNAPSHOT，actualrestoreconsumerbarrier/browserTLSproxy/fullscan/B1durableordering/production待验。

### 2026-10-05 UserInfo与真实浏览器联验

真实Code兑换AT的GET/POST UserInfo200，missing/HTML/cookie-only/revoked/malformed401；reauth11与consent4共15专项绿。AS补resource-server并显式Bearer converter防止误选客户端预算Bean，前expected200/actual302多次红。默认及application-security BFF实际Edge双context/注册ALTCHA/双登录/浏览器绑定密码重认证/当前退出/SSO免密码返回/全局退出与真实Back-channel通过。强制.2 **461server/178starter零失败错误跳过18task**，BFF仍NO-SOURCE，browser独立脚本非Gradle suite。命令/manifest/本地HTTP与RP门禁边界见[English](../integration/development-delivery-2026-10-05-2.md)／[中文](../zh-CN/integration/development-delivery-2026-10-05-2.md)。

### 4.2 用户名

2026-10-05后续.3：用户ADR-0008默认sid marker拒迟到登记，两签名回归先红后绿；registry10 Clock/latch/短锁/失效失败/其他sid/期限，callback增加5至35含exchange中Logout与错误签名不污染。强制461server193starter654零失败错误跳过18tasks，最终默认自有BFF实际Edge闭环保持。维护9PowerShell启动边界＋两实际JAR无console拒绝/securitysnapshot不变，非成功隐藏输入演练。命令来源见[English.3](../integration/development-delivery-2026-10-05-3.md)／[中文](../zh-CN/integration/development-delivery-2026-10-05-3.md)，durable/customstore/restart/production仍独立。

参数化测试：

```text
有效：abc, Alice, ABC_123, 1234, a_B
无效：ab, 25字符, 连字符, 空格, 中文, emoji
```

验收：

- `Alice`、`alice` 和 `ALICE` 可以分别注册并登录，数据库中是三个独立用户名。
- 登录必须精确匹配大小写；`Alice` 的密码不能配合 `alice` 登录 `Alice` 的账号。
- 数据库列和唯一索引使用确定性的大小写敏感比较，不存在 `citext` 或 `lower(username)` 唯一索引。
- 当前用户名数据库唯一。
- 历史用户名按精确大小写永久占用；历史 `Alice` 不阻止独立的 `alice`。
- 普通用户 90 天内不能二次修改。
- 只把 `Alice` 改为 `ALICE` 也算一次修改并触发 90 天冷却。
- 到第 90 天边界允许修改。
- 两个并发事务抢同一用户名只有一个成功。
- 修改失败不关闭旧用户名。
- 历史用户名不能登录，也不会被分给另一个用户。

### 4.3 状态机

- `account_status: ACTIVE -> FROZEN -> ACTIVE` 合法。
- `deletion_status: NONE -> PENDING -> CANCELLED` 合法，取消后账号状态仍由 `account_status` 决定。
- 冻结账号可以同时为 `deletion_status=PENDING/HELD`，不会因取消注销而自动解冻。
- 注销执行后 `account_status=DELETED` 且 `deletion_status=EXECUTED`。
- `DELETED` 不能恢复到 Active，UUID 不复用。
- 唯一管理员不能经 Web 进入 `FROZEN`/`DELETED` 或失去管理员身份。
- 数据库唯一部分索引阻止第二个 `is_sso_admin=true`。

## 5. 注册与 CAPTCHA

正常路径：

- `REGISTER` Challenge 成功创建账号和 8–10 个恢复码。
- Challenge、用户、用户名、密码凭据和恢复码在一个事务提交。
- 一次性响应只出现一次恢复码。

负向路径：

- 无 Challenge、过期、错误签名、错误用途、重复使用均失败。
- ALTCHA v2 签名元数据中的 challenge ID、用途和提交 IP 网段任一不匹配均失败。
- `LOGIN_RISK` Challenge 不能用于注册。
- 并发重复 Challenge 只有一次成功。
- 用户名冲突不创建孤立用户或恢复码。
- 密码策略失败不消耗用户名。
- PostgreSQL 短期状态写入失败时注册失败关闭。
- IP 小时/每日阈值和 `Retry-After` 正确。
- CAPTCHA 失败仍保留独立事务中的限流计数；第 6 次同网段小时请求返回 `429`。
- 相同幂等键和请求体重试不再次返回恢复码；同一键配不同请求体返回 `IDEMPOTENCY_KEY_REUSED`。
- 修改客户端时间、请求 ID 或隐藏字段不能绕过 TTL/用途。

## 6. 密码与登录

### 6.1 密码

- 11 字符拒绝，12 和 128 字符接受，129 拒绝。
- 空格和长密码短语不被裁剪。
- 常见/泄漏密码拒绝。
- 密码不要求字符种类组合。
- 存储值是 Argon2id/批准的 `PasswordEncoder` 格式，不等于明文。
- 相同密码的两个用户哈希不同。
- 参数升级后旧哈希成功登录会重新哈希。
- Argon2 基准 p95 低于最终门限，错误请求不能绕过限流耗尽内存。

### 6.2 登录

- 正确用户名/密码成功，`amr=["pwd"]`、`acr="urn:las:loa:1"`。
- 用户不存在和密码错误的外部提示/状态一致，耗时差异在可接受范围。
- 历史用户名、冻结、删除账号不能正常登录。
- 登录成功轮换 SSO Session ID。
- 未选择记住和选择记住的 idle/absolute 到期准确。
- absolute 到期不能因持续访问无限延长。
- 达阈值后要求 CAPTCHA，继续失败短期锁定。
- 管理员不能获得 90 天记住会话。
- 管理员 45 分钟 idle、10 小时 absolute、15 分钟近期认证按最终配置测试。
- 近期认证端点验证当前密码、轮换会话 ID、更新 `last_password_auth_at`，不自动执行此前失败的写操作。
- 任何会话的 `credentials_version` 或 `security_epoch` 不匹配时立即失效。
- 授予管理员身份会撤销目标既有普通/记住会话；只有重新登录产生的 `admin_session=true` 短会话可调用管理员端点。

## 7. 恢复

### 7.1 用户恢复码

- 每个码只使用一次；并发提交只有一个成功。
- 错误码、错误用户名、过期恢复会话和错误 CSRF 均失败。
- 恢复用户名精确区分大小写；`Alice` 的恢复码不能用于 `alice`。
- 恢复会话只能调用改密/退出，不能调用 `/me` 完整功能或 OIDC 授权。
- 恢复成功增加 `credentials_version`。
- 旧密码、已使用的恢复码、旧会话、旧 Authorization 和旧 Refresh Token 全部失效；同批其他未使用恢复码继续有效。
- 恢复成功不自动建立完整登录。
- 重新生成恢复码时旧批次在同一事务全部失效。
- 响应中断后不能通过 API 再取回同一批明文码。

### S1 原子失效回归（2026-10-03 已执行）

`PasswordRecoveryInvalidationIntegrationTest`：28 项、零失败/错误/跳过；使用 Spring 代理服务、真实 PostgreSQL 18.4 Testcontainers、`TransactionTemplate` 和 `pg_blocking_pids` 确认竞争事务实际等待，没有使用 mock 锁或随机 sleep 作为成功依据。

- 普通/强制改密后旧恢复完成返回 `SESSION_EXPIRED`，不能覆盖新密码或再递增凭据版本；普通恢复码与人工凭据创建的会话均覆盖。
- 普通/强制改密、晋升、维护 reset 与恢复创建/完成覆盖两种先提交顺序；恢复先完成后，旧当前密码改密拒绝，合法维护操作作为后续事务仍可执行。
- 人工恢复凭据与普通/强制改密、晋升的创建竞争覆盖；晋升撤销尚未使用人工凭据。维护 reset 的恢复竞争使用管理员自助码，签发人工凭据给管理员本身不属于合法流程。
- 两个有效恢复会话竞争完成只有一个密码提交；等待用户锁后会话到期仍拒绝；恢复成功保持现有未使用普通码政策。
- 密码错误导致的失败不失效合法恢复；显式提交前故障回滚密码、凭据版本、恢复状态和安全事件；已匹配人工凭据的失败审计与限流独立提交。

原始三项回归先在缺失失效的实现上运行，普通/强制改密两项失败；人工凭据晋升竞争新增回归也先失败，再修复转绿。到期 fixture 曾违反 `created_at < expires_at` 约束，修正 fixture 后通过，不能把该 fixture 失败描述成生产缺陷。

相关专项共 87 项（28 + 6 + 53）；全量强制重跑服务端 158 项、starter 37 项，均无失败/错误/跳过，参考 BFF 重新编译且 `test NO-SOURCE`。命令和分阶段证据见[状态地图 §3.3](status-issues-and-plan-2026-10-02.md#33-2026-10-03-s1-原子失效与数据库竞争验证)。这些是数据库/服务及已有 MockMvc 回归，不是浏览器、实际代理、生产并发容量或交互维护命令演练。

### 7.1.1 S6熵预算与旧摘要兼容（2026-10-04已执行）

- 两生成入口去固定前缀/分隔符后随机字符预算先红（60<80），原1项生成/HMAC对照通过；现四组16均匀32表字符80bit，保留SecureRandom与十枚。
- RecoveryCodeServiceTest最终9项/新增8：普通人工预算/格式、独立固定旧普通/人工与新HMAC向量、横杠/Locale.ROOT大写、前缀不同、digest防御copy。格式输出与源码均匀CSPRNG合并支持熵预算，不是随机源统计认证。
- RecoveryCredentialCompatibilityIntegrationTest33新增真实PostgreSQL18.4/Spring/MockMvc：旧摘要由固定明文/JCA/test pepper独立创建，不用新生成器。覆盖旧新×普通人工service/HTTP204/no-store/10mSecureHttpOnlyStrictCookie/完整改密、code完成前后与Session重放、原request关联/ALREADY_USED/过期审计无测试明文；撤销/过期/规范化/错身份type；rotation/reissue新摘要/30m与外层rollback、同批第二旧码、改密unused旧码/已有Session、10m到期不改密；并发一Session、CAPTCHA/用户预算、实际恢复页新placeholder/maxlength64。
- 初扩大两失败为nullable used_at helper单值映射，改行record修fixture，不计生产缺陷。最终164（9+33+28+6+53+25+10）和强制314 server/176 starter零失败/错误/跳过，12task全执行/BFF重编译NO-SOURCE；JVM/兼容XML13:27:56.325/343UTC。

旧三组仍60bit、不批量失效/重写/续期，原单次/撤销/期限/同批保留规则不变。无schema/deps/pepper/因素/权限/公开入口变化。live全部issuer升级、浏览器TCP/JS打印下载/clipboard/可访问性/容量/随机源认证/全量Secret扫描未验。命令/时间见状态§3.12及[English](recovery-credential-entropy.md)／[中文](../zh-CN/implementation/recovery-credential-entropy.md)。

### 7.2 管理员人工恢复

- 只有管理员且有 15 分钟近期认证才能签发。
- 原因必填，凭据最长 30 分钟、单次使用。
- 管理员不能为自己签发。
- 管理员不能查看用户密码/恢复码或直接设置密码。
- 签发、成功、失败、过期、撤销均有审计关联。

## 8. 管理员 bootstrap 与密码重置

### 8.1 Bootstrap

- 无管理员时可按现有 UUID 授予。
- 空库模式可创建 UUID v7 用户并授予。
- 并发执行只有一个成功。
- 已存在管理员时再次执行失败且不修改任何用户。
- 目标为 `ACTIVE` 但有 `PENDING` 或 `HELD` 注销申请时，提升返回失败及先取消注销的提示；角色、密码哈希/凭据版本、注销申请和既有安全状态均保持原状，不自动取消或释放暂缓（S4 / ADR-0002）。
- 用户取消注销后可重试提升；已取消等终态申请历史不阻止符合其余条件的目标。
- PostgreSQL 受控并发验证申请注销与提升：申请先提交则提升失败；提升先提交则申请因管理员身份失败，不能形成管理员与有效注销申请并存的新状态。与取消/执行交错时仍符合生命周期条件及统一锁顺序。
- 命令没有 `--password`，终端隐藏输入并要求二次一致。
- 临时密码 24 小时过期。
- 初次登录只允许改密/退出。
- 改密后需要新会话并引导生成恢复码。
- stdout、stderr、应用日志、shell 历史和审计中没有临时密码或哈希。

### 8.1.1 S4晋升与注销回归（2026-10-04已执行）

- 原PENDING/HELD两条先红：门禁前未抛拒绝；HELD首次setup的重复Client Secret已修fixture，不作为产品缺陷。
- `AdminPromotionDeletionIntegrationTest`25项实际PostgreSQL18.4/Spring代理服务：PENDING/HELD/到期状态/释放暂缓拒绝，完整行快照无角色/凭据/资格版本/请求/恢复/会话/授权/审计/outbox变化，取消历史合法重试/新请求、无申请及非ACTIVE/未知/已有管理员。
- 申请与晋升双向、取消先提交/未提交、执行/HELD/冻结变化的受控交错；latch/pg_blocking_pids确认真实用户锁等待，独立SKIP probe证等用户时无请求锁；冻结为明示SQL fixture。并发维护只一管理员；外层事务失败与实际SQL审计trigger失败均完整回滚并可重试。
- `AdminBootstrapCommandTest`3项Console fixture：取消提示与拒绝传播、无关失败/成功、输出无密码及两次char[]清零。真实System.console隐藏输入/PowerShell/JAR进程退出码未运行。
- 旧执行fallback测试直接造历史/腐化角色与请求组合，保留防御，不再通过支持的晋升制造有效申请与管理员并存。
- 最后专项90（25+3+10+24+28）、强制全量255 server/176 starter均零失败/错误/跳过；12task全执行，参考BFF重编译、自身NO-SOURCE。

规则仍ADR-0002；无schema/依赖/认证因素/公开晋升接口变化。真实浏览器取消→晋升→首次改密、维护终端/脚本、容量/崩溃/部署/多实例待验收。命令/时间见状态地图§3.10及[English](admin-promotion-deletion.md)／[中文](../zh-CN/implementation/admin-promotion-deletion.md)。

### 8.2 `reset-admin-password.ps1`

静态/行为验收：

- 脚本只调用 Java 维护命令，不含哈希算法或 SQL。
- 仅接受非秘密的 `-Profile`，无 `-UserId` 或 `-Password`。
- Java 命令自动查询并显示唯一管理员的用户名与 UUID，确认前不修改数据。
- Java 命令使用 Web 应用相同 `PasswordEncoder`。
- 管理员不存在、非活动或确认期间发生变化时失败。
- 密码策略失败返回非零且不修改凭据。
- 成功后设置 `ACTIVE`、清除临时到期时间和锁定、凭据版本递增。
- 全部会话/Authorization/Refresh Token/恢复码失效。
- outbox 与密码更新处于同一事务，失败时整体回滚。
- 审计事件 `ADMIN_PASSWORD_RESET_BY_MAINTENANCE` 存在且无秘密。
- 旧管理员 Cookie 和 Refresh Token 不能继续使用。

## 9. API 与浏览器安全

### Starter 运行期 Session 所有权（2026-10-03 已验证，边界明确）

`LasSsoSessionChainIntegrationTest` 10 项验证实际 Spring 安全链、OAuth2 manager、repository 与 loopback HTTP 刷新客户端；使用初始认证 fixture/Mock Servlet 请求，不启动真实 SSO/Tomcat，也不是浏览器 E2E。

- 同主体两 Session 实际 HTTP 刷新分别携带各自 RT，写入各自轮换后的 AT/RT。
- 同 Session 并发只消费一次 RT；不同 Session 可同时进入 HTTP endpoint，旋转结果不共享。
- 正确 CSRF 的当前退出仅撤销当前 Session；缺失 CSRF 拒绝且不提交任务。
- 明确 `invalid_grant` 只结束对应 Session；503 暂时故障保留其原 RT/Session，恢复后可刷新。
- 自定义 LAS repository 的保存/读取/删除接入真实 manager/filter/logout；无关 `@Primary` Spring repository 不被使用。
- 本地授权删除、认证清除、Session 失效均先于 sink 提交；sink 拒绝提交不恢复当前登录、不影响另一 Session。远程撤销持久接受/最终成功仍未验证。
- `LasSsoCurrentApplicationLogoutHandlerTest` 修正旧弱断言，直接检查本地删除→远程提交的调用顺序；此前仅验证调用存在，不能作为顺序证据。

### SSO-01/B2 签名回调与公开组合（2026-10-03 已验证）

`LasSsoOidcCallbackIntegrationTest` 14 项：真实 Spring Code 登录、HTTP Token/JWKS、RSA 签名/nonce/PKCE 验证，默认/包外应用自有链及立即签名退出。入站 Servlet、Session ID 事件调度和接收路由为 fixture（终端 servlet 调真实 Controller），不是 MVC 绑定/真实容器/浏览器/实际 SSO。

- 原始单项回归先失败：回调/签名/防固定/Token 保存通过，立即退出 204 却未失效目标 Session；成功处理器登记后转绿。
- 自有链使用公开 configurer，保留业务角色门禁和成功 delegate；统一 LAS repository，刷新/登记各一，重复 configure 拒绝。
- 回调与请求登记失败先清本地登录再输出自定义/默认错误，不调用成功 delegate；缺 sid 拒绝；错误签名/nonce 不保存 Token、不登记。
- 显式监听器验证换号重建/销毁清理、失败失效、再次登录替换旧 sid 索引。真实容器事件触发仍未验收。
- 自有链 CSRF 当前退出与实际 HTTP RT 暂故障/终态错误输出通过；另两项刷新 Filter 测试验证公开错误 Hook 的失效/保留语义。

最终强制重跑服务端 158 + starter 53 项零失败/错误/跳过，参考 BFF 自有链 profile 重新编译、自身 `test NO-SOURCE`。Code exchange 与 index 没有跨存储事务；退出先于/并发于登记、并发在途请求、持久接受、真实浏览器/重启/多实例/B3–B5 仍另行跟踪。公共接入与验证边界见[接入契约](../zh-CN/integration/starter-security-composition.md)和状态地图 §3.4。

### B5 安全返回与本地授权事务（2026-10-04 已执行）

- 原两项签名目标回归先红（路径/query均回首页），state绑定和公开返回接点后绿。
- `LasSsoReturnToTest` 45项含URI允许/拒绝、控制/编码、重复/长度/缺省、UTF8/query/context、十分钟精确到期/未来、错/重复state、替换/隔离及确定性并发单次repository消费。
- `LasSsoOidcCallbackIntegrationTest` 28项，比B4新增14：完整Code/PKCE/RSA/JWKS、目标成功/回调篡改、重放/双浏览器/新授权替换、nonce/OAuth错误/过期/重复state、并发一次兑换、自有处理器不被跳过/显式采用/业务403以及登记失败不交目标。OP参数无目标。
- 直接授权非法目标暴露框架通用500，匹配validation Filter修400/no-store；首次fixture断言误认为500有redirect已纠正，不把setup当原业务缺陷。
- 最后专项83（45+28+10既有链）全绿；强制全量178server/176starter零失败/错误/跳过，BFF重编译/资源处理、自身NO-SOURCE。B5新增59，无schema/依赖/锁变化。

用户确认自有链显式接入（ADR-0004），目标非权限/近期密码证据，不自动重放写操作。单JVM同Servlet Session同步不保证shared store/多实例；旧请求无时间属性须重新登录。入站controller/Servlet/ID事件仍fixture，live MVC/browser/SSO/proxy、UI渲染/消费验收未完成；命令时间见§3.7和[中文契约](../zh-CN/integration/login-return-target.md)／[English](../integration/login-return-target.md)。

### 初期业务 BFF 重启验收（计划，尚未执行）

- 重启后旧本地 Cookie/Token 关联不能访问受保护业务，旧 OAuth/重认证 state 不能完成回调或恢复十五分钟窗口。
- SSO 主会话仍有效时，重新顶层登录可建立新的业务会话；主会话过期时正常要求密码。普通回调不沿用旧业务会话的近期认证证据。
- 登录、加载、刷新和退出仍按本地会话隔离（B1），同 JVM 的两浏览器互不覆盖，不能用重启全失效掩盖运行时混用。
- 重启后的 Back-channel 接收、可靠撤销作业、查询观察到期/重新确认仍满足各自契约；回调/退出 replay 的保留与会话全失效策略一致。
- SSO 自身 JDBC 会话/challenge 重载能力继续通过；不得把业务网站重新登录方案误实现为清空 SSO 全部会话。

每个写端点测试：

- 无 CSRF、错误 CSRF、跨 Origin 请求拒绝；
- 正确同源 CSRF 通过；
- JSON 多余字段、类型错误、超长字段拒绝；
- 错误响应符合 Problem 格式且有 `requestId`；
- `Cache-Control: no-store`；
- 不在错误中泄漏栈、SQL、host、账号额外状态或秘密。

Cookie 验收：

```text
__Host- 前缀
Secure
HttpOnly（会话/恢复）
Path=/
无 Domain
SameSite 符合契约
```

浏览器 E2E 确认 LocalStorage、SessionStorage、IndexedDB、URL、DOM 和前端日志中没有 Token。

安全 Header 测试包括 CSP、HSTS、`nosniff`、Referrer Policy、Frame Ancestors。使用测试页面验证 SSO 不能被 iframe。

### A3 内部 Basic 哈希前预算（2026-10-04 已执行）

- 原单项第11错误Secret仍401先红；预算接入后429且真实encoder spy只10次matches，直接证明哈希前。
- `FirstPartyClientRateLimitIntegrationTest`25项，实际PostgreSQL18.4/MockMvc：正常25查询成功归还、旧失败不清、client跨网段/IPv4/IPv6、轮换unknown、格式/多头/超长/控制/ID边界、active第一方门禁先于哈希、编码路径、OAuthscope隔离。
- 25并发在matches latch暂停，10获准/15限流、锁不覆盖哈希；固定Clock精确窗口/幂等旧refund/新窗口/清理；实际SQL第二桶约束及refund trigger故障验证两桶回滚、503；spy故障明示、requestId/no-store/日志canary通过。
- 最后专项32（25+6资格+1OAuthconverter）零失败/错误/跳过；强制全量203server/176starter同样无失败/跳过，BFF重新编译/NO-SOURCE。

ADR-0005已确认内部独立失败/在途预算，正常成功不累计、满桶连正确Secret也拒；非所有请求吞吐limit，跨窗口/多键/合法高流量仍需容量。真实Servlet/TLS/代理网络、多实例/重启演练、连接池/Argon2压测、指标告警、SSO-04恢复/吞吐未验；SQLfault仅一次性DB并finally移除，无生产迁移。命令/时间见状态地图§3.8及[中文](../zh-CN/integration/internal-basic-authentication-budget.md)／[English](../integration/internal-basic-authentication-budget.md)。

## 10. OIDC 协议

2026-10-02 已执行证据：`OidcPasswordReauthenticationIntegrationTest` 的 10 个真实 PostgreSQL/MockMvc 用例通过，覆盖强制密码验证、正数 max_age、none 错误、缺失/未来证据、错误参数、CSRF/Origin、到期、限流、原请求绑定、签名 ID Token 与刷新保留 auth_time；`JdbcSessionPersistenceTest` 覆盖 challenge JDBC 重载及两个完成副本仅一个消费成功。服务端完整 126 项、零跳过；starter 任务为 up-to-date（原 20 项通过结果）。这不替代 LASLOG 依赖方、本地会话十五分钟边界或真实浏览器/多实例部署验收。

### 10.1 Discovery/JWKS

- Discovery `issuer` 与配置完全一致。
- 只发布实际启用的 grant/response/algorithm。
- `S256` 存在，`plain` 不作为允许方法。
- JWKS 只有公钥，无私钥参数。
- 当前 `kid` 稳定，重启不变化。
- 缺失私钥时生产启动失败，不随机生成。

### 10.2 Authorization Code

正常：

- Confidential Client + Authorization Code + PKCE S256 成功。
- Code 2 分钟到期、只能使用一次、绑定 client/redirect/code challenge。
- ID Token `iss/aud/nonce/sub/sid/auth_time/acr/amr` 正确。

负向：

- 缺失/错误 `state`、`nonce`、`code_verifier`、`redirect_uri`、client secret 失败。
- `code_challenge_method=plain` 失败。
- Code 跨 client 使用失败。
- Code 重放失败。
- Redirect URI 子路径、大小写、查询变化、通配域、HTTP 生产地址失败。
- Implicit、Password、Device、Client Credentials、Dynamic Registration 未启用。
- 未审批 scope 返回标准 `invalid_scope`。

### 10.3 Token

- ID Token `aud=client_id`。
- LASLOG AT 仅有 `urn:las:resource:laslog` audience；AlienCommon 同理。
- Resource Server 拒绝错误 Issuer、Audience、签名、算法、过期、未来 `nbf` 和 ID Token。
- Access Token 不含邮箱、Minecraft、QQ、管理员/业务角色。
- Refresh Token family 从首次 Code 授权起绝对 14 天到期，每次刷新轮换但不顺延。
- 到 family 第 14 天边界后刷新失败；有效 SSO 会话可通过新的顶层 Authorization Code 流无密码重新授权。
- 多进程/多实例并发刷新经 PostgreSQL 分布式 single-flight 只有一个旧 Token 请求，不能只靠进程内锁。
- SSO 刷新事务的数据库行锁下会重新检查账号状态、凭据版本、全局 epoch 和客户端配置版本；与冻结/改密/全局退出并发时不能提交新 Token。
- 真实重复使用触发整个 family 撤销和安全事件。
- 改密、冻结、删除和全局退出后刷新失败。
- 用户能查看合作方 consent；撤销单个客户端只终止该客户端，不退出其他应用或 SSO 主会话。

## 11. BFF 契约

同一黑盒契约套件分别运行在：

```text
las-sso-spring-boot-starter
las-sso-django
```

必须一致验证：

- 登录 URL 生成和站内 `returnTo`；
- 回调 `state`/`nonce`/PKCE/Issuer/Audience；
- `(issuer, sub)` JIT 唯一映射；
- 用户名变化更新快照而不创建新用户；
- 完整登录同步资料，持续会话最多每 24 小时调用一次 UserInfo；同步仍只按 `(issuer, sub)` 定位用户；
- Token 仅在服务端存储；
- `/api/session` 未登录/已登录响应；
- 当前应用退出保留 SSO 主会话；
- 全局退出清除所有本地会话；
- Back-channel Logout `sid` 清理；
- Refresh single-flight 与失败恢复；
- 每个受保护请求检查 `auth_fresh_until`；AT 到期且刷新失败时不能仅凭本地 Cookie 继续，SSO 故障返回服务不可用；
- SSO 不可用时返回服务不可用而不是封禁/密码错误。

适配库契约主版本不兼容时，业务应用启动测试必须失败并给出明确错误。

## 12. 退出

### 12.1 当前应用退出

- LASLOG 当前退出只清除 LASLOG 会话/授权。
- AlienCommon 会话和 SSO 主会话继续有效。
- 再次进入 LASLOG 经顶层 Authorization Code 流可无密码返回。
- SSO/Revocation Endpoint 不可用时，LASLOG 仍先清除本地会话和 Cookie；远程撤销进入重试，不会导致用户保持登录。

### 12.2 全局退出

- 任一 BFF 发起 RP-Initiated Logout 后 SSO 主会话失效。
- 两个 BFF 均收到有效 Logout Token 并按 `sid` 清除本地会话。
- Logout Token 验证 `typ`、签名、`iss`、`aud`、`iat`、`exp`、`jti`、`events`、`sid`，并拒绝 `nonce`。
- 完全相同的有效 Logout Token 使用同一 `jti` 重传时返回已缓存的成功结果且不产生副作用。
- 相同 `jti` 但 Token 内容不同返回 `400`；已退出会话收到合法重复通知仍返回成功。
- BFF 临时 `5xx` 时 SSO 延迟重试。
- Back-channel 永久失败时，授权撤销在 Access Token/刷新边界最多约 15 分钟阻断会话。
- 从用户点击全局退出到两个正常 BFF 清除会话的目标为数秒，测试记录实际值。

### 12.3 管理员强制下线

与全局退出相同，并额外验证管理员审计、原因和目标 UUID。冻结用户后即使业务 Cookie 残留，也不能获得新 AT/Refresh Token。

### 12.4 B3 发送回执回归（2026-10-04 已执行）

- 原始两条单项均先红：真实 HTTP 302 被服务端标 processed，starter 不重试原端点；修复后转绿。
- `BackChannelLogoutDeliveryIntegrationTest` 20 项：真实 PostgreSQL 18.4/outbox/签名/HTTP；200/204 才完成，其他 2xx/3xx/4xx/5xx 重试，断连、claim 清理、恢复后成功、八次死信不再认领、另一个接收方可完成以及错误/日志无 canary/Token。
- `LasSsoTokenRevocationQueueTest` 18 项：精确 200、Basic/form、其他 2xx/跳转/错误与断连、恢复后成功、完整八次/七次退避、日志不含秘密。生产调度器单项验证真实重试，其余矩阵用包内注入调度器记录延迟，HTTP 仍真实；无生产 scheduler 改动。
- 两发送路径的跳转目标请求数为零；不只验证“最终 2xx”。DB 重试 fixture 将任务设为到期，不用随机 sleep 偶然碰调度。
- 相关专项 73 服务端 + 20 starter 通过；最新强制全量 178 服务端 + 71 starter 零失败/错误/跳过，BFF 重新编译、自身 `test NO-SOURCE`。

未覆盖真实 TLS/代理/OP/BFF、长时/慢响应/容量、崩溃重启、claim 竞争、多实例或 B4 接收语义；默认队列耗尽不是持久死信。命令/时间见状态地图 §3.5，回执约定见[中文](../zh-CN/integration/logout-delivery-acknowledgements.md)／[English](../integration/logout-delivery-acknowledgements.md)。

### 12.5 B4 验证/回执/失败可重试（2026-10-04 已执行）

- 原始 18 项中 13 项先失败：合法签名异常 claim 被接受、事件数组抛错、重复执行、执行失败误 400/204，触及真实缺陷。
- `LasSsoLogoutTokenValidationIntegrationTest` 33 项：真实 RSA/HTTP JWKS、默认 decoder/接收器；30 负面变体（缺失/类型/时间/用途/事件/签名/kid/算法/issuer/audience/nonce/nbf），同成功重传、sid-only 和实际 JWKS 503；原始 numeric sub 类型断言保留，Nimbus 提前转换缺口确实修复。
- `LasSsoBackChannelLogoutControllerTest` 15 项：并发同内容不假报成功、失败/部分失效可重试、失败后冲突拒绝、存储/decoder 可用性 no-store 503、缓存成功不执行、exp+60 秒边界、过程式 adapter/旧 accept-only 503、固定 issuer 与日志 canary。
- `DefaultLasSsoLogoutReplayStoreTest` 5 项含执行中不被清理、失败释放、成功回执、保留期与原 accept 不等同完成；固定 Clock/latch 不靠随机 sleep。
- 67 项阶段专项全绿，最后加固定 issuer 断言后全量包含 68 项相关子集（33+15+5+14+1）；服务端 178/starter 117 零失败/错误/跳过，BFF 重编译、`test NO-SOURCE`。

用户批准增量 SPI/旧 adapter 失败关闭见 ADR-0003。上述验证调用实际 Controller，不是 live MVC 绑定/真实 SSO/浏览器/容器；持久回执/租约/崩溃/多实例、通知先于登记、容量待验。默认单 JVM 过程不是跨存储 exactly-once。命令时间见状态地图 §3.6；[中文升级契约](../zh-CN/integration/backchannel-logout-receipts.md)／[English](../integration/backchannel-logout-receipts.md)。

## 13. 管理员功能

- 用户按用户名/UUID查询、cursor 分页和过滤正确。
- 冻结/解冻幂等，原因和审计必填。
- 唯一管理员不能冻结、删除、解任自己。
- 客户端 Redirect/Logout/Back-channel URI 精确校验。
- 第一方/合作方 consent 配置限制正确。
- Client Secret 只显示一次，摘要存储。
- 每个客户端始终只有一把活动 Secret；维护窗口轮换后旧密钥立即失败，`config_version` 递增并撤销既有授权/会话。
- 缩小 scope/audience 或禁用客户端会撤销现有授权；合作方扩大 scope 会要求重新 consent；仅改展示名不强制退出。
- 网页 API 不存在 JWT 签名私钥读取/上传/删除端点。
- 管理员自由文本按不可信内容转义，不能存储型 XSS。

### S5墓碑管理读取（2026-10-04已执行）

- 真正注册/申请/到期执行删除后，列表有身份但详情ADMIN_TARGET_NOT_FOUND先红；扩大service/API/自动选中模板三红。可选凭据join使2绿1红，无值模板fallback后三绿。
- AdminUserTombstoneIntegrationTest18新增实际PostgreSQL18.4/MockMvc/Thymeleaf：墓碑详情200/no-store/三个字段确有null、无hash恢复码；自动/显式/filter外选择、未知404、混合/DELETED cursor无漏重复、原凭据三状态/时间与冻结；反复读取/四种终态写拒绝目标快照不变；普通/匿名拒绝；非终态缺凭据fixture仍登录/资格拒绝；另一真实事务删除未提交时读旧完整状态、commit后墓碑。
- 管理员role/认证/ServletSession/近期密码时间为fixture；目标由真实owning service删除，仅时间设到期。没有真实浏览器/TCP/JS/可访问性/proxy/容量/部署验收，仍依赖保留的当前用户名，不重建损坏username。
- 相关138（18+10+24+25+2+53+6）和强制273 server/176 starter零失败/错误/跳过，12task全执行，BFF重编译/NO-SOURCE；新增XML12:48:55.549UTC。无schema/权限/保留/依赖变化。

命令/阶段见状态§3.11及[English](admin-tombstone-views.md)／[中文](../zh-CN/implementation/admin-tombstone-views.md)。契约验证生命周期删除→管理员读取边界，不宣称页面多次query是统一快照或SSO-04全部交付。

## 14. 注销

- 申请必须重新输入密码。
- 申请后 `deletion_status=PENDING`、执行时间准确为 30 天并全局退出，`account_status` 不被注销流程覆盖。
- 等待期只允许取消/确认页面，不允许普通 OIDC 登录。
- 30 天内重新认证可取消。
- 到期任务幂等执行，凭据/会话/授权删除，用户 UUID 墓碑保留。
- 重跑任务不创建第二个事件或恢复已删资料。
- 管理员暂缓必须有原因、最长 90 天，到期自动释放；释放后任务可继续执行。
- 历史业务内容不由 SSO 直接删除。
- 冻结/争议账号按最终运营保留规则测试。

### S3 注销锁序/原子批次（2026-10-04 已执行）

- 原3项先红：admin hold/release等user时已有request锁、expiry忙user未跳过，真实pg_blocking_pids/SKIP probe，不捏造deadlock victim。
- DeletionConcurrencyIntegrationTest24项实际PostgreSQL18.4/Spring/事务，含cancel/admin/delete双向提交、status/version/audit/events/credentials精确结果、busyuser/request后续重跑、旧/新申请/owner重检、directdue、两阶段候选user预锁、advisory/并发batch去重。
- 真SQLtrigger第二target故障验证整批credentials/outbox/version rollback，明示spy phase2fault连expiry也回滚；Clock在实际wait前进验过期hold拒绝。latch/pg_blocking_pids/future控交错，非random sleep或纯mock保存顺序。
- 最后专项40（24+10注销+6资格）零失败/错误/跳过；强制227server/176starter同零失败跳过、12task全执行，BFF重编译/NO-SOURCE。

ADR-0006用户确认保留batch原子，0不表示due backlog空、count=实际转换（release后execute计2）；无schema/认证/授权/30天/90天/deps改动。仅测到的注销锁序，realHTTP/browser/production contention/容量/饥饿/crashrestore/live多实例/alllockgraph未验；S4随后见§8.1.1，S5见§13，S6见§7.1.1，D1指定补丁见§4。命令/时间见状态地图§3.9及[English](deletion-concurrency.md)／[中文](../zh-CN/implementation/deletion-concurrency.md)。

## 15. 审计、隐私与日志

对每种管理员写操作验证：

```text
actor UUID
actor session
action
target
reason
result
request_id
timestamp
```

安全事件至少覆盖登录失败、恢复、改密、Token 重用、冻结、删除、全局退出。

自动扫描应用、代理、审计和错误追踪输出，确认不存在：

```text
测试密码 canary
恢复码 canary
Client Secret canary
Authorization Code
Access/Refresh/ID/Logout Token
Cookie / Set-Cookie
完整 IP
```

验证保留任务在边界前不删除、边界后删除，并保留永久用户名历史和 UUID 墓碑。

## 16. 故障与恢复演练

### 16.1 依赖故障

- PostgreSQL 断开：Readiness 失败，新认证/写操作停止，没有脏提交。
- 短期状态表缺失/锁超时：注册和恢复失败关闭；登录不绕过限流；并发刷新不误报重用。
- BFF Back-channel `5xx`：outbox 重试、监控和积压告警。
- 系统时钟偏移：超过阈值告警；Token 校验只允许 60 秒。
- 磁盘接近满：告警且不会因日志无限写入损坏数据库。

### 16.2 备份恢复

在空环境恢复 PostgreSQL 后，按运维手册生成新安全 epoch 和新签名 key，再运行完整冒烟。验证：

- schema 版本；
- 用户/管理员；
- Client 配置；
- 历史用户名；
- 恢复后生成新 `security_epoch`，恢复前所有会话/Authorization/RT 即使数据库记录为 Active 也不能使用；
- 使用新签名 key/`kid`，BFF 拒绝灾难前旧 Token；
- PostgreSQL 短期状态表和两个 BFF 本地会话清空，全部用户重新登录；
- outbox、注销任务和清理任务；
- 两个 BFF 新登录/退出。

### 16.3 换钥

- 常规轮换期间新旧 JWKS 同时可见。
- 切换后新 Token 使用新 `kid`，旧 Token 仍可验证到期。
- 删除旧公钥前门禁能发现仍未过期的旧 Token。
- 泄漏演练验证 BFF 可拒绝指定旧 `kid` 并强制全局重新登录。

## 17. 性能与稳定性

在生产同规格或可换算环境：

1. 50 并发登录，记录 Argon2、数据库和响应 p50/p95/p99。
2. 20 requests/s 混合负载运行 10 分钟。
3. 100 个会话同时刷新，适配库不重复使用旧 RT。
4. 1,000 用户全局下线/冻结事件不会造成 outbox 永久积压。
5. 一个 BFF 持续失败时，重试有退避且不影响另一个 BFF。
6. 日志和审计写入不会耗尽磁盘或显著拉高登录延迟。

这不是容量承诺；测试结果写入发布记录，实际阈值根据部署硬件确定。

## 18. 用户验收场景

### 18.1 普通用户

用户能够：

- 注册并保存一次性恢复码；
- 使用用户名/密码登录两个业务网站；
- 修改显示名、头像和冷却期允许的用户名；
- 查看并撤销会话；
- 退出当前应用而保留另一个应用；
- 全局退出；
- 修改/恢复密码并让旧会话失效；
- 重发恢复码；
- 查看并撤销已授权合作网站；
- 申请并取消 30 天注销。

用户不能：

- 查看 Token 或 Client Secret；
- 修改业务权限、Minecraft 绑定或稿件权限；
- 使用历史用户名登录；
- 绕过 90 天用户名冷却。

### 18.2 唯一管理员

管理员能够：

- 首次登录强制改密并保存恢复码；
- 查询、冻结、解冻、强制下线用户；
- 经人工核验签发一次性恢复凭据；
- 创建/禁用 OIDC Client、管理精确地址/scopes/audience、轮换 Client Secret；
- 查看安全事件和审计。

管理员不能：

- 查看/设置用户密码；
- 查看用户恢复码；
- 冒充用户；
- 冻结/删除/解任自己；
- 在网页中操作 JWT 签名私钥、数据库、备份或部署权限。

## 19. 发布证据

每次候选发布保存：

```text
commit/build 标识
依赖锁定清单
数据库迁移版本
自动测试摘要
OIDC/BFF 契约测试摘要
安全扫描摘要
性能测试摘要
备份/恢复演练时间
冒烟测试操作者与结果
已接受例外及到期日
```

没有可观察证据时不能把功能标记为“已验证”。
