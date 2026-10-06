# LAS SSO 已完成内容、问题清单与后续计划

- 更新日期：2026-10-07（Asia/Shanghai）；保留 2026-10-02 文件名及原审查编号。
- 基线：`codex/fix-sso-quality-review` 分支，HEAD `8cee3ed`，包含尚未提交的工作区实现；不能只用 HEAD 还原此次审查对象。
- 审查时间：2026-10-01 至 2026-10-02。
- 当前结论：.20261006.7刷新后globalLogout oldIDhint400已修V15HMAChistory，735全量0skip；actualGrafanaViewerdesktopmobile及两chain5分钟mixed12,598operation/120refresh0reuse通过。productiononcall/transport/remainingprobe/小时級rampsoak/historysizing/scanDAST/actualconsumer与完整B1仍开。
- 最新执行验证：2026-10-07 **00:07:51–00:12:17（Asia/Shanghai）0.1.0-dev.20261006.7**强制 **505server+193starter+37referenceBFF=735** 零失败错误跳过20tasks/actualPG/V15/bootJARmetadata；303source fingerprintee911dc4.../18artifact/3unchangedlocks/nativePOMcompile。finalGrafana/mixed00:36–00:48，301.154/302.178s五subject9Session/Viewer1440/390八panelcanvas/权限403；scan241lock157JAR0reportedvuln/Semgrep2331warning0error/Secrets三0match，详§3.27。
- 当前工作：actualGrafana导入渲染/Viewer写拒与受控十分钟mixed完成，发现refresh→globalhint400修复。下一productionprivateTLSrotationthrottle/Grafanaauthretentionbackup/真人oncallescalation、remainingdomainexternalprobe、小时級rampsoak/真实productionhardwareDBvolume/metricscost/historycleanup；productionimageOSJDKproxy/logDAST/officialDNSCA-CDNfirewall/backupkey/actualconsumer仍另验。B1presubmitgap/customdurableindexreplay与signedcommittedrelease独立，无commitremote deploy。
- 本文是实现状态与执行计划，不替代架构、安全、API、OIDC 契约，也不把历史测试结果当成当前所有改动的验证结果。
- 本次职责校准：仅管理 SSO 服务端、`las-sso-spring-boot-starter` 和 `las-sso-reference-bff`。其他业务项目由其他会话负责；本文只记录它们需要的 SSO 接口和交付输入，不安排业务方实现。SSO 专属架构见[架构与交付计划](../architecture/sso-architecture-and-roadmap.md)。
- 当前执行入口：[正式生产上线计划](../zh-CN/implementation/production-launch-plan.md)／[English](production-launch-plan.md)。P01–P19按依赖列出模块、步骤、负责人类型、上线影响和验收证据；下方旧阶段保历史，不再作为当前任务排序。

## 1. 状态与边界

| 状态 | 含义 |
|---|---|
| 已实现 | 源码中存在对应能力，不表示没有缺陷 |
| 已验证 | 指定时间、指定范围的测试或演练有结果，必须同时说明边界 |
| 待修复 | 已发现问题，尚未提交修复和通过回归验收 |
| 待实施 | 已规划但没有对应完整实现 |
| 待验收 | 有实现或配置基础，但缺少完整运行证据 |
| 延期/范围外 | 当前版本明确不做，不能计作遗漏功能 |

继续保持已有边界：

- SSO 拥有账号、凭据、会话、账号生命周期和第一方服务访问资格。
- LAS LOG 拥有业务主体映射、角色、项目、UUID 绑定、白名单、服务器封禁和最终业务授权。
- Back-channel Logout 清除既有会话；服务访问资格查询/事件回答未来能否进入服务，二者不可替代。
- 本轮资格能力仅包含查询和单调状态版本；用户已确认独立状态事件投递之后补，见[领域决策](../adr/0001-sso-owns-service-access-eligibility.md)。事件延期不放宽 LASLOG 的一分钟观察/拒绝要求；初期须定义并验证依赖方查询补偿和活动主体主动刷新，已有查询接口本身不等于传播保证。
- 不借 P1 修复扩展 Minecraft 接口、业务权限、邮箱验证、MFA 或新的客户端认证协议。

## 2. 已完成的实现

以下“已完成”指相应基础能力已存在；相关缺陷和验收边界仍须分别跟踪。

### 2.0 状态矩阵：当前与预期

最初矩阵来自源码/历史报告，S1/SSO-01/B2–B5/A3/S3–S6/D1/A1A2Servlet及S2/03/04/06交付见§3.2–3.17。全量非全部验收，完成仅范围，待修明确缺陷、延期非遗漏。

| 项目 | 分类 | 当前状况 | 预期状况／完成条件 |
|---|---|---|---|
| 工程、迁移与依赖锁 | .7 V15刷新退出关联/最终扫描已验 | V1–V15HMAC-onlyhinthistory/FKcascade；原Boot4.1.1Framework7.0.9Security7.1.1Tomcat11.0.26Jackson3.1.7等三锁保持，241/JAR157包0reportedvuln | V15部署/historysizing/pepper轮换及发布advisory/consumer图/productionimage日志DAST，不称所有依赖安全 |
| 稳定身份与账号基本流程 | 已完成基础／待验收 | UUID principal/sub、注册、用户名/资料、密码策略/ALTCHA 已实现 | 不可变主体契约保留，核心 UI/真实 HTTP/浏览器验收完成 |
| SSO 会话 | 已完成基础 | JDBC 会话、idle/absolute、sid、撤销及近期认证元数据已有 | 保留真实 PostgreSQL 重载/期限/安全版本测试；不等同 SDK 会话所有权已修 |
| OIDC / RT family | 已完成基础／待验收 | Code+PKCE、JWKS/claims/aud、RT 旋转/复用检测/安全版本已有 | SSO 协议及受控多会话/撤销证据完整，无业务权限 claim |
| OIDC 密码重认证 | 已完成 SSO 服务端 | prompt/max_age/none、密码页、V14 五分钟事务、固定 auth_time 已验证 | 保持签名密码时间、刷新不推进、浏览器/请求绑定；回传支持的服务端版本和契约 |
| 密码/人工恢复 | S1原子失效/S6熵与旧码兼容已修/受控验证 | 两生成入口四组16随机字符=80bit；旧HMAC/规范化不变，9JVM/33兼容数据库MockMvc通过；旧码仍60bit按原状态期限使用 | 保留单次/10m恢复/30m人工/撤销/同批保留政策，补真实浏览器/运维和发行实例一致升级 |
| 管理员/客户端 | .5finalJAR空库真维护firstchange/reset重验 | PowerShell/JARhash/PTYhiddenbootstrap/失败无变/真reset/Edge强改恢复download及旧SSOCookieToken拒，S2/S4原竞争规则保持 | populated晋升注销/live24hexpiry/production终端offline/clientrollout新grant竞争容量另验 |
| 注销/墓碑 | S3/S4/S5已修/受控验证 | user→request/原子batch、晋升门禁；S5凭据LEFT JOIN/三个明确null、墓碑页面无凭据/不适用，18项数据库/MockMvc/模板通过 | 保留终态可查/写拒绝，补真实浏览器/容量/多实例，不据此宣称全锁图无死锁 |
| 第一方资格查询 | epoch/wire/lifecycle及隔离真实恢复低counter新代次已验 | .20261006.1 pgdumprestore新epoch、实际查询version2→1区分；testgeneration拒旧reply，原17wire/load保持 | productionbackup/实际consumer缓存inflight屏障/容量观察SLA独立，不称一分钟保证 |
| Back-channel Logout | B2默认sid登记时序/B3发送/B4回执已修 | .3marker/check/index同短锁，早/兑换中通知及registry并发过；strictToken/process/失败重试保持，旧accept-only503 | customadapter/durable事务租约/crash/restartmultiinstance/productionTLS容量独立，非exactly-once |
| Starter自有链/替换/返回 | 公共组合及B5已实现/受控验证 | configurer/success/lifecycle/error/ReturnTo公共接点；目标state/Session/十分钟/单次，默认采用、自有显式，四存储SPI保留 | 真实消费/容器/存储及版本验收，不强制跳过业务success |
| Starter Token所有权 | 核心193回归/B2默认时序/.4参考committedtask持久已验，B1整体部分 | 公开sink不变，参考独立PG加密task/lease/retry/进程重启SSO真实撤销通过 | presubmitcrash/persistentSessionTokenindexreplay/customordering/multiinstance及业务采用独立 |
| Reference BFF | .7default/appowned5min TLSmixed/refresh重复global通过 | 5subject9Session/sentinel/actualCodePKCETokenUserInfo/45sfixtureAT，原queue37保持；旧initialIDhint由serverhistory支持，starterAPI/JAR不变 | 正式DNSCA/CDNfirewall/fullUI/keyrotationdeadoperatorrecoverypool/小时soak/外consumer |
| 本地制品发布 | .20261006.7刷新退出与Grafana负载开发交付／productionrelease待验 | source303/18artifactconfig/3unchangedlocks/nativePOMcompile/735绿及scan，V1–V15/starterAPI不变；旧ID保 | committedsource/签名/托管immutable/外部consumerproduction未交，默认SNAPSHOT |
| 恢复维护与隔离演练 | SSODB事务屏障和真实dump/restore/key切换完成 | expectedepoch锁/随机换代/SessionAuthRTshort清理/永久facts保/释放outboxclaim，交互PowerShell、旧Token拒与后台任务恢复 | productionbackupkeyRPORToPITR/proxy摘流量/实际businesscacheSession及cachedkid屏障独立 |
| 生产部署/运维验收 | 整体未完成，.5隔离TLS/恢复/admin重验 | privateedge/最终hash/真实恢复维护；新增readinessdbgroup实际200503200/liveness200 | 正式DNSCAcert续期/firewallCDN/生产backupRPO/key/image日志DAST与consumer屏障 |
| 容量/监控 | .6core/3alert保，.7actualGrafana与十分钟mixed已验 | exactdashboardimport/privateDatasource/Viewer1440/390/八panelcanvas/写403；default/application301/302s共12,598operation/120refresh0reuse，finalqueue空/sentinel保；1unattributedsamplinggap | 非productionSLA/小时soak/leak排除，privateTLSoncall/Grafanaproductionauthretention/remainingprobe/largeDBhistorysizing待验 |
| 独立资格事件 | 延期 | SERVICE_ACCESS_CHANGED 未实现，已确认后续补 | 后续按独立事件/outbox/版本/重试契约交付，不塞进 Logout Token |
| 多实例与业务会话跨重启保留 | 延期 | 未形成生产完整 SDK 支持组合 | 后续独立验证持久 adapter/分布式协调；当前不将其当基础接入前置 |

### 2.0.1 交接六项：仅 SSO 责任

沿用对方交接需求编号，描述 SSO 的交付结果；不接管消费端数据库、业务门禁、页面或网关任务。

| 编号 | 分类 | 当前状况 | SSO 预期交付 |
|---|---|---|---|
| SSO-01 | 公共链/返回已实现和受控验证，真实接入待验 | configurer/success/生命周期/error/B5target已交源码双语，默认/包外链签名/异常/业务拒绝通过 | 消费/容器生命周期、安全reauth/可辨识版本独立，非生产完成 |
| SSO-02 | 发送/默认回执/B2sid时序/.4参考committask持久已验，整体部分 | 四SPI不变、参考profilePG/AEScommitlease/八次dead/真实restart撤销，默认仍进程内 | presubmitcrash/Sessionindexreplay持久/customstore时序/消费采用独立，不建业务表 |
| SSO-03 | server前置/public扩展/.2协议browser已验，RP门禁未实现 | trustedbuilder/API fixture证据保持，真实SSO browser-bound prompt/max_age密码重输及signed auth_time/BFF callback通过 | RPpending/browserSession/15m/revoke/highriskwrite仍own，harness参数不等consumer门禁 |
| SSO-04 | 七字段/epoch生命周期/隔离真实backuprestore已验，productionconsumer独立 | .20261006.1真实dump/restore新keyepoch/低counter与旧Token拒；generation仅harness，原17wire及8worker80load保持 | productionbackupRPO/实际consumer cachegeneration/inflight观察/proxyTLS容量另验 |
| SSO-05 | 未完成具体登记 | 只确认现有开发/样例登记，尚无经核验的新客户端配置交付 | 等外部会话提供精确、可达的 URI 后登记独立 client，交 scope/audience/查询权限和 Secret 配置方法，不自行决定外部源 |
| SSO-06 | .20261006.7finalhint/Grafana/mixed开发交付，正式发行独立 | source303/18artifact3locks/505+193+37=735/nativePOM/scan/finalGrafanaTLSmixed/V15，旧adminrestorealert历史 | committedsource/release签名/托管immutable/外部consumerproduction待交 |

| 领域 | 已实现内容 | 验证/限制 |
|---|---|---|
| 工程基础 | Java 21、Spring Boot 4.1、Spring Security Authorization Server 7.1、PostgreSQL、Flyway V1–V14、依赖锁、服务端/Starter/Reference BFF 三个 Gradle 模块 | 服务端既有完整报告为 126 项通过；依赖告警仍未处理 |
| 身份与账号 | 稳定 UUID principal/sub、注册、用户名及资料、密码策略与 Argon2、ALTCHA、恢复码/人工恢复、幂等操作基础 | 恢复会话失效和恢复码熵存在 S1/S6 |
| SSO 会话 | Spring Session JDBC、安全会话元数据、空闲/绝对期限、记住登录、最近认证、单会话/全部会话撤销 | 数据库会话不等于业务 BFF Token 已按会话正确隔离 |
| OIDC 与令牌 | Discovery/JWKS、Authorization Code + PKCE、claims/aud、凭据/全局/client 安全版本、Refresh Token 旋转、复用检测和令牌族撤销 | 尚未完成真实业务 BFF 全链路和多浏览器验收 |
| OIDC 密码重新认证 | `prompt=login`、`max_age`、`prompt=none`、SSO 密码页面、五分钟浏览器/主体/请求绑定事务、V14 原子完成/消费、固定签名 `auth_time` | SSO 前置能力通过 PostgreSQL/MockMvc 与 JDBC 重载/竞争消费验证；不等于 LASLOG 已接入，也不关闭 B1 等既有缺陷 |
| 管理员与客户端 | bootstrap/reset、管理门禁、客户端登记/配置/Secret 轮换、管理审计与安全事件 | 存在 Secret 竞态、管理员晋升流程和墓碑展示问题 |
| 账号生命周期 | 冻结/解冻、注销申请/取消、暂缓/释放、到期执行、墓碑保留 | 存在注销锁顺序倒置；不等于 LAS LOG 已消费生命周期变化 |
| Back-channel Logout | 服务端 outbox、签名 Logout Token、超时/并发投递、重试/死信；Starter 基础验签、索引和 replay 检查 | B1–B4 未修复；接收侧及部分重试状态仅单 JVM |
| 服务访问资格第一阶段 | `GET /internal/v1/principals/{subject}/service-access`、最小 no-store 响应、未知主体 DENIED/version 0、统一资格判断、V13 单调版本及事务推进 | 已有集成测试通过；A1/A3 待修复；独立状态事件延期 |
| 页面与样例 | SSO 登录/注册/恢复/账号/管理页面、Reference BFF 配置与演示页面 | 本次未做真实浏览器 UI/E2E/可访问性验收；Reference BFF 无自身测试 |
| Starter 依赖交付 | Maven publication、版本覆盖、可选制品库配置、源码 composite 接入说明 | 当前[服务端 README](../../las-sso-server/README.md)记录源码 composite 与本地发布/消费已验证；此项不是前次 135 项测试的覆盖内容，本次文档整理没有独立复跑；托管不可变版本/远程发布与生产 SDK 尚未完成 |

查询接口当前用于第一方机密客户端的设计边界仍有效；A1 表明实际认证边界没有达到该设计，不能直接对外宣称“只允许第一方访问”已经安全验收。

以上第 2 节末的基础能力表保留原审查视图，最新状态以第 2.0/4.0 矩阵及逐项更新证据校准。未验证的代理/部署结果不可自动提升为验收通过。

### 2.1 本次完成：SSO 侧密码重新认证

- 已验证授权请求中的客户端、精确回调、scope、PKCE 和参数后，`prompt=login` 或 `max_age=0` 要求真实密码验证；正数 `max_age` 按实际密码认证时间判断，证据不足的 `prompt=none` 返回 `login_required`。
- `/oauth2/reauthenticate` 在 SSO 收集当前账号密码，要求 CSRF、同源、用户/IP 限流及当前凭据版本校验；成功轮换 HTTP Session ID，保留稳定 `sid`。错误密码不推进认证证据。
- 每个 SSO 浏览器只有一个五分钟待验证事务，关联主体/`sid` 及原授权参数；新事务替换旧事务。Spring Session JDBC 保存关联，V14 `oidc_password_challenges` 通过 PostgreSQL 条件更新原子完成/消费，重复或并发消费不能成功两次。
- 签名 ID Token 的 `auth_time` 来自初始 Authorization 固定的密码时间；Token 刷新、后续密码认证、其他浏览器的认证不升级旧授权/Token family。缺失可信证据不补造时间。
- 密码重新认证已提供 LASLOG D1 的 SSO 前置支持；LASLOG 本地会话关联、回调消费、十五分钟精确到期、退出/安全撤销失效、明确再次提交及真实浏览器 E2E 仍待完成。

### 2.2 已确认的产品与阶段决策

| 决策 | 当前规则 | 状态/归属 |
|---|---|---|
| 认证因素 | V1 不添加 MFA，保留近期 SSO 密码认证 | 因素选择已确定；LASLOG ADR-0023 |
| 近期认证复用 | 十五分钟，仅限发起操作的浏览器/独立登录会话；不同会话不共享，Token 刷新不延长 | SSO 证据已实现；LASLOG 门禁仍待实现 |
| S4 晋升与注销 | 用户先取消有效 `PENDING`/`HELD` 注销申请，再由主机操作员重试提升；不自动取消 | 规则2026-10-02确定；2026-10-04实现/数据库与命令fixture已验证，见[ADR-0002](../adr/0002-require-deletion-cancellation-before-admin-promotion.md)及§3.10 |
| 人工恢复联系 | 使用现有 QQ 群联系本人，由管理员人工核验并记录；不新增 QQ 登录/账号绑定 | 联系渠道已确认；恢复仍沿用既有流程 |
| 资格同步 | 先使用第一方资格查询/版本；独立 `SERVICE_ACCESS_CHANGED` 之后补 | 已延期，当前非依赖；查询对账契约仍待完成 |
| 业务网站重启 | LASLOG 初期重启后废弃旧本地登录/近期认证状态，重新走顶层 SSO；有效 SSO 主会话可免密码普通登录 | 行为已确认，接入待实现；不取消 SSO 自身 JDBC 会话持久化 |
| B4 Replay adapter 升级 | 增量 process 执行/成功回执；保留 accept 编译兼容，旧 accept-only 接收 503 直到升级 | 2026-10-04 用户明确选择，ADR-0003；默认已实现/单 JVM 验证，持久验收待完成 |
| B5返回目标 | 无效/重复400，目标state/Session/十分钟单次；默认采用，自有成功处理器始终执行并显式使用 | 2026-10-04用户确认“显式接入”，ADR-0004；受控已验，live/shared store待验 |
| A3独立失败预算 | 内部独立client10/network20十五分钟，hash前原子占用/成功退款，不共享OAuth；DB故障503/耗尽429 | 2026-10-04明确选择“独立预算”，ADR-0005；DB/MockMvc/25并发已验、生产容量待验 |
| S3批次事务 | 保留整批原子，不逐用户commit；candidate union user DB UUID排序先锁，再request重检/busy跳过，实际转换count | 2026-10-04用户选择“保留批次原子性”，ADR-0006；24实际DBrace/40相关已验 |
| 查询恢复代次 | stateEpoch复用security_epoch，version仅同issuer/sub/epoch单调；epochopaque不可排序，restore前换代/consumer屏障拒旧cache/inflight | 2026-10-05用户明确选新增stateEpoch，ADR-0007；无migration，server受控验证，消费屏障/live恢复待验 |

近期密码窗口、AT 到期/撤销兜底、账号资格观察是独立时钟。现有 BFF 契约的退出通知失败兜底仍为最多约十五分钟的 AT/刷新边界；B1 会破坏会话隔离及这个上界，必须修复，不用近期密码窗口替代它。

## 3. 已执行的检查与证据边界

| 检查 | 2026-10-01 审查结果 | 不覆盖的部分 |
|---|---|---|
| 服务端既有测试 | 115 项，0 失败、0 错误、0 跳过 | 不覆盖本次发现的所有负面/并发场景 |
| Starter 既有测试 | 20 项，0 失败、0 错误、0 跳过 | 不代表多浏览器、多实例或真实业务接入通过 |
| Reference BFF | 编译通过，`test NO-SOURCE` | 不能写成 BFF 自动化/E2E 测试通过 |
| PostgreSQL 集成 | Testcontainers 临时 PostgreSQL 18.4 实际运行 | 未使用或修改用户现有业务数据库，不是生产数据库验收 |
| 专项诊断 | 4 个方法确认错误行为：内部认证绕过、IP 风控绕过、Basic 入口无限流、旧恢复会话可用；最后一项覆盖改密和管理员晋升两条路径 | 诊断断言验证的是漏洞存在，不是验证已修复 |
| 编码路径 HTTP 探针 | MockMvc + PostgreSQL 下复现 | 尚未经过真实 Tomcat TCP、反向代理和生产网关 |
| 默认同意页面 XSS 探针 | 所尝试的注入没有反射，诊断预期未成立 | 不能列为本项目已复现 XSS，也不能据此否定依赖官方告警 |
| 格式检查 | `git diff --check` 通过 | LF/CRLF 提示不是安全或逻辑验证 |

以上表格保留 2026-10-01 的原始审查证据：115 项服务端与 20 项 starter 测试早于随后新增的 Starter 发布配置。源码 composite 与本地制品验证属于另一组记录，不能混入旧测试扩大覆盖。

### 3.1 2026-10-02 密码重新认证交付证据

| 检查 | 结果与可复核记录 | 边界 |
|---|---|---|
| 服务端完整测试 | `las-sso-server/build/reports/tests/test/index.html` 为 126 项、0 失败、0 跳过 | 本次核对既有结果，不是重新执行；不能据此关闭原 14 项问题 |
| OIDC 重认证集成 | `TEST-org.las.sso.config.OidcPasswordReauthenticationIntegrationTest.xml` 为 10 项、0 失败/错误/跳过；含签名 Code 兑换/Token/刷新、错误密码、参数、CSRF/Origin、请求绑定、到期、限流 | PostgreSQL + MockMvc；不是真实浏览器/生产反向代理验收 |
| JDBC 会话与竞争消费 | `TEST-org.las.sso.session.JdbcSessionPersistenceTest.xml` 为 2 项、0 失败/错误/跳过；含会话入库和 challenge 重载/竞争消费 | 数据库级重载/竞争验证；不是已部署多实例的演练 |
| PostgreSQL | XML 日志确认 `postgres:18.4-alpine` 的 Testcontainers 启动；上述两组报告时间约为 2026-10-02 03:02–03:03（Asia/Shanghai） | 临时测试数据库，不是用户已有数据库或生产 |
| Starter | 原 20 项报告保留；本次重认证交付任务沿用 up-to-date 结果 | 不是重新执行 20 项，也不覆盖 B1 修复或 LASLOG |
| JavaScript | 本次整理执行 `node --check "src/main/resources/static/assets/passport-ui.js"` 成功 | 仅语法，不是 UI/E2E/可访问性验收 |

交付说明未保留完整 Gradle 命令参数，本次不补造；基础复跑入口为在服务端目录执行 `.\gradlew.bat test`，数据库测试需 Docker。当前修改尚未提交或部署，LASLOG 真实浏览器端到端验收尚未完成。

完整 SCA/SAST、压力测试、真实浏览器 E2E、多实例和线上部署审计尚未完成。旧测试数字不得在新增/修改回归测试后继续当作“最新测试总数”。

### 3.2 2026-10-03 B1 运行期安全链修复与验证

- 实际安全链/manager 的新测试最初因测试运行时无 JSON converter 无法启动；只在 starter `testRuntimeOnly` 加入 Boot BOM 管理的 Jackson 3.1.4，并更新/审查该子项目测试运行时锁。没有替换生产 JSON 依赖，没有改根锁或业务 schema。
- 旧退出单测加强后红灯：实际顺序为远程提交→本地删除。另一个 `@Primary` Spring repository 存在时，整链退出也选错 repository，回归失败。修复为默认退出 bean 明确依赖 `LasSsoAuthorizedClientRepository`，Handler 先删除授权/清除认证/失效 Session，再用本会话 Token 快照提交 sink；sink 拒绝不恢复登录，日志只记非秘密失败类型。
- 执行 `.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoSessionChainIntegrationTest" --tests "org.las.sso.client.LasSsoCurrentApplicationLogoutHandlerTest" --no-daemon --console=plain`：修复后 12 项重点用例通过（10 项链路＋2 项 Handler）。
- 执行 `.\gradlew.bat :las-sso-spring-boot-starter:test :las-sso-reference-bff:compileJava --no-daemon --console=plain`：starter **37 项通过、零失败/跳过**，参考 BFF 编译为 `UP-TO-DATE`。前期锁更新测试使用 `--write-locks`，仅子项目测试运行时锁有新增项。
- 新 10 项组合测试：同主体双 Session 的真实 RT/AT 轮换；同 Session 并发 single-flight；不同 Session 同时 HTTP 刷新；invalid_grant 仅失效对应 Session；503 暂故障保留原状态并可重试；CSRF 退出/缺 CSRF 拒绝；sink 失败仍本地退出；自定义 repository save/load/remove；无关 Primary repository 不被采用。
- 验证层级：真实 Spring FilterChainProxy、OAuth2 manager/provider/repository 与实际 loopback HTTP endpoint；初始 OIDC 登录、入站 Servlet/Session 为 fixture，sink 是记录/失败测试实现，不是签名登录回调、真实浏览器/Tomcat/实际 SSO、数据库/持久撤销、重启、多实例或生产配置证据。
- 纠正此前报告：“两个 Mock Session ID 碰撞”未有证据，原并发测试超时是缺少 latch countDown，后来才修正；“本地先删除再提交”此前测试只检查调用存在，顺序本轮才真正验证并修复。当时 S1 普通改密缺口仍存在，不随晋升修复关闭；此后独立补完见第 3.3 节。

### 3.3 2026-10-03 S1 原子失效与数据库竞争验证

**系统模块与根因：**SSO 账号改密/受限恢复及管理员维护。普通/强制改密原事务虽更新密码/版本、撤销恢复码/人工凭据/SSO 会话和授权，却没有消费 `recovery_sessions`；旧恢复 token 可以在新密码提交后再次改写密码。新增回归还确认晋升未撤销尚未使用人工恢复凭据，它在晋升提交后仍可创建新恢复会话。

**已说明并落实的修改：**先显式锁同一账号的 `users` 行，再访问关联安全状态；恢复创建/完成的候选 UUID 读取不作为认证成功，锁后按同一主体重新检查凭据/会话条件及当前时间。改密在原事务内消费所有未消费恢复会话，晋升同时撤销人工恢复凭据；bootstrap/reset 保留维护 advisory lock 后用户→关联状态的顺序。公开便利重载也加事务注解，使实际 Spring 代理调用保持相同原子性。

**决策与兼容：**采用已有行锁＋同事务失效，未新增恢复版本字段/迁移，未改变密码策略、十分钟恢复期限、认证因素或恢复权限。新增版本字段是备选，但涉及 schema/旧会话处置；本轮采用已批准 S1 的等效原子方案。同账号安全操作短暂串行化，其他账号无该用户行竞争；吞吐/连接池容量尚未压测。自助恢复仍保留同批未使用普通码，普通改密/晋升/reset 仍废弃整批旧凭据。S3/S4 仍独立开放。

执行环境为 Java 21、真实 PostgreSQL 18.4 Testcontainers，均在 `las-sso-server/` 执行。无 Docker 跳过：

| 阶段 | 命令/实际结果 | 证据与边界 |
|---|---|---|
| 原始红灯，19:57（Asia/Shanghai） | `.\gradlew.bat :test --tests "org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest" --no-daemon --console=plain`；当时 3 项、2 失败、0 跳过 | 普通/强制改密两项未抛预期 `SESSION_EXPIRED`；失败改密对照通过。旧 token 真正完成密码覆盖，非 mock 断言 |
| 初次修复，20:04 | 同命令，当时 3 项全部通过 | 失效与已有密码事务一致 |
| 扩大竞争，20:18 | 同命令，当时 17 项全部通过 | `pg_blocking_pids` 确认竞争实际等待持锁事务，不用随机 sleep |
| 人工凭据晋升红灯，20:23 | `.\gradlew.bat :test --tests "org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest.credentialMutationCommitWinsOverWaitingRecoveryCreation" --tests "org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest.rejectedAdminCredentialAuditCommitsWhileRecoveryTransactionRollsBack" --no-daemon --console=plain`；8 项、1 失败、0 跳过 | 晋升提交后人工恢复返回 `CREATED`；其余七项及独立审计通过。此后在晋升事务中一并撤销人工凭据 |
| 相关专项，20:33 | `.\gradlew.bat :test --tests "org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest" --tests "org.las.sso.admin.AdminRecoveryCredentialServiceTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --no-daemon --console=plain`；**87 项全部通过、0 失败/错误/跳过** | 28 项新专项＋6 项管理员恢复＋53 项持久化/MockMvc；XML 当次时间为 12:33 UTC。扩展到期 fixture 曾违反数据库时间约束，修正 setup 后通过；该失败不算生产缺陷 |
| 全量强制重跑，20:35–20:36 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`；**服务端 158 项、starter 37 项，均 0 失败/错误/跳过** | 12 个 actionable tasks 全部执行；参考 BFF 确实重新编译，自身 `test NO-SOURCE`。这是本轮最新报告，不沿用 UP-TO-DATE |

28 项新专项覆盖：普通/强制改密旧会话拒绝、两类恢复来源、提交前回滚、普通/强制改密/晋升/reset 与恢复创建/完成的两种提交顺序、人工凭据创建竞争、两个恢复会话仅一次密码提交、等待后到期，以及失败审计/限流独立提交。过期或已消费会话不改密码或凭据版本。

最终报告：`las-sso-server/build/reports/tests/test/index.html` 及 starter 对应路径；`TEST-org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest.xml` 为 28 项零失败/错误/跳过。早期红灯 XML 被后续 Gradle 报告覆盖，原始命令输出及本节分阶段记录保留，不虚构独立红灯报告文件。改动未提交、发布或部署；尚未验证真实浏览器/代理、交互维护命令演练、生产容量、多实例部署及最终退出投递。

### 3.4 2026-10-03 SSO-01/B2 公开组合与签名回调验证

用户在 S1 完成报告后要求“推进”，本步骤范围为 SSO starter 和参考 BFF。已说明现有流：Spring 成功回调保存 Token/防固定/认证后直接跳转，后置登记 Filter 不继续执行；计划层为成功 Handler、公共安全链组合、生命周期与错误接点，无业务 schema/服务端迁移。公开 `LasSsoSecurityConfigurer` 与默认链复用 PKCE/repository/刷新/登记/当前退出，应用保留路由授权和成功 delegate；`LasSsoAuthenticationSuccessHandler` 在成功跳转前登记，失败结束本地登录后返回 `503 SESSION_REGISTRATION_FAILED`。`LasSsoSessionLifecycleListener` 公开处理销毁/换号；重新登录移除当前 Session 的旧 sid 映射；错误 Hook `LasSsoClientErrorHandler` 只输出响应，不改变清理/暂故障规则。

| 阶段 | 实际命令/结果 | 边界 |
|---|---|---|
| B2 原始红灯 | `.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest" --no-daemon --console=plain`：当时单项失败 | 登录 Code/PKCE、RSA/JWKS、Token 保存、Session 换号都通过；立即签名 Logout 204 后目标 Session 仍有效。失败触及原窗口 |
| 默认链修复转绿 | `.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest" --tests "org.las.sso.client.LasSsoSessionChainIntegrationTest" --tests "org.las.sso.client.LasSsoAuthorizedClientRefreshFilterTest" --no-daemon --console=plain` | 后续扩大此三套，22:01 时 13 + 10 + 8 = 31 项通过；再新增一项请求登记失败进入最终全量 |
| 扩大回调 | 7 项阶段两项因 fixture 重复声明 `LasSsoRevocationTaskSink` 导致 context 启动失败；移除 fixture 重复声明后全部通过 | 该失败是测试 setup，不是 adapter 接线缺陷；不以允许 Bean override 掩盖问题 |
| 最终全量，22:04–22:05 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`：服务端 **158** + starter **53**，**0 失败/错误/跳过**；12 actionable tasks 全执行 | 参考 BFF 默认及可选 `application-security` profile 源码重新编译，自身 `test NO-SOURCE`；没有运行 profile/浏览器验收 |

新增 `LasSsoOidcCallbackIntegrationTest` 最终 14 项（XML 14:05 UTC）和刷新 error Hook 2 项。覆盖默认签名登录后无 GET 立即签名退出、同主体双 Session、包外自有安全链/业务角色拒绝/应用成功 delegate、替换 Token/index/error adapter、回调/请求登记失败清理、缺 sid、错误签名/nonce、换号/销毁/重建失败、再次登录、重复组合拒绝、CSRF 当前退出及自有链真实 HTTP 暂故障/终态刷新错误。

**验证层级：**真实 Spring FilterChainProxy/OAuth2 Login provider/repository/manager，loopback HTTP OP fixture 核验 Basic/redirect/PKCE，真实 RSA JWKS/ID Token/Logout Token 验证。入站 Mock Servlet 显式 dispatch Session ID listener，Back-channel 通过 fixture terminal servlet 调实际 Controller；没有 MVC 参数绑定、真实容器/浏览器/SSO、持久数据库 adapter/重启、多实例/生产证据。早期红灯 XML 被后续覆盖，保存命令输出与本节阶段记录，不虚构红灯文件。

**兼容和未解决边界：**增加公开组合、成功包装/监听/response-only error 接点，既有公开 refresh 构造器、四类 SPI 和 Bean 替换策略保留；本步骤没有依赖/锁/schema 更改。B2 的回调返回→下一请求窗口已修，**不宣称 Token 保存＋索引登记是跨存储原子事务**，通知先于/并发于登记、在途请求/崩溃/持久协调仍开放；`register` 的重复/正常返回可立即 invalidate 契约已文档化，没有擅自加 tombstone/cache 策略。B3/B4/B5、SSO-03 reauth 消费/版本交付独立跟踪。文档与代码示例见[公开组合契约](../integration/starter-security-composition.md)及[中文](../zh-CN/integration/starter-security-composition.md)。未提交、发布或部署。

### 3.5 2026-10-03 至 2026-10-04 B3 发送成功确认

**范围/方案已说明：**服务端 `BackChannelLogoutDelivery` outbox 发送和 starter `LasSsoTokenRevocationQueue`，从实际 HTTP 状态判断确认并保留既有重试状态机。服务端只接受契约 200/204；撤销只接受 RFC 7009 §2.2 的 200，忽略响应体。202/其他未约定 2xx/3xx/4xx/5xx 都重试；保留工厂/3秒连接/5秒读取/八次/随机指数退避，显式禁跳转。没有新增 migration/HTTP 依赖/cache/持久 schema/按状态提前死信或 Retry-After 策略。三个候选原因（3xx 不抛错、自动跳转、重试状态丢失）由原端点/跳转目标/DB 状态共同区分。

| 阶段 | 实际命令/结果 | 证据 |
|---|---|---|
| 两条红灯，2026-10-03 23:55 | `.\gradlew.bat :test --tests "org.las.sso.logout.BackChannelLogoutDeliveryIntegrationTest" --no-daemon --console=plain` 和 `.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoTokenRevocationQueueTest" --no-daemon --console=plain`，各单项失败/0 跳过 | PostgreSQL 下 302 被标 processed；生产调度器原端点无第二次请求，确认 sender 不检查状态 |
| 最小修复转绿，23:59 | 同两命令，各单项通过 | `exchange` 显式成功集合、工厂禁跳转，响应异常只存状态 |
| 扩大相关专项，2026-10-04 00:08–00:09 | `.\gradlew.bat :test --tests "org.las.sso.logout.BackChannelLogoutDeliveryIntegrationTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --no-daemon --console=plain`：**73 项**；`.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoTokenRevocationQueueTest" --tests "org.las.sso.client.LasSsoCurrentApplicationLogoutHandlerTest" --no-daemon --console=plain`：**20 项**，均 0 失败/错误/跳过 | 20 新 DB/HTTP + 53 既有 persistence，18 新队列 + 两项 Handler；中途测试 assertThat 对 Comparable/Future 编译歧义修正，不算生产缺陷 |
| 全量强制重跑，00:10–00:12 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`：**178 server + 71 starter**，0 失败/错误/跳过，12 actionable tasks 全执行 | BFF 重新编译，自身 `test NO-SOURCE`；XML 新 DB 20 项 16:11:24 UTC、queue 18 项 16:11:56 UTC |

**已验证：**Back-channel 200/204 完成；201/202/206、301/302/303/307/308、400/401/429/500/503 不确认；claim 清理/未来可用、恢复后成功、302/500 八次死信不再认领、断连失败保持可重试、另一 receiver 正常完成。撤销精确 200 与 Basic/form、其他状态/断连、八次/七退避、恢复后成功。跳转目标请求数零；响应 canary/实际 Logout Token/请求 Token/Secret 不进入耗尽日志或 DB error；只记录 `HTTP_<status>` 或传输类别。

**测试层/剩余：**服务端 Spring/签名/实际 PostgreSQL 18.4/loopback HTTP；DB fixture 提前设可用时间不等待随机退避。队列一项用真实生产 scheduler，其余注入包内可控 scheduler 执行实际 HTTP并记录延迟，生产 public constructor/策略保留。没有真实 TLS/反向代理/正式 BFF/live OP、长时/超时/慢响应/容量、claim 竞争、崩溃重启/多实例验收。B3 是发送侧回执修复，不关闭 B4 接收/重放/执行或 B2 更广竞争。服务端有持久死信；starter 八次后仅日志/停止，无持久回执/死信/重启保证，sink 接受不等于远程成功。红灯 XML 后续覆盖，保留命令输出/本节记录。改动未提交/发布/部署。详见[英文回执契约](../integration/logout-delivery-acknowledgements.md)／[中文](../zh-CN/integration/logout-delivery-acknowledgements.md)。

### 3.6 2026-10-04 B4 严格接收与执行回执

**范围和根因：**starter 默认 decoder/Back-channel Controller/Replay SPI/Session Registry。合法签名 Token 未强制 iat/短寿命/第一方 sid/空事件对象；accept 只记摘要、不记成功，相同重传再执行；adapter IllegalArgumentException 在执行阶段误 400，索引先移除/吞 IllegalStateException 可使失败假 204。已先说明改验证/回执/失效顺序，不改业务 DB、SSO 凭据/部署。

**用户决策：**question 工具明确选择“增量 SPI（推荐）”。增加 default `process(..., Runnable action)` 与 SUCCESS/CONFLICT/IN_PROGRESS，保留 accept 源码兼容；只有 accept 的旧自有 adapter 返回 503、不执行会话直到升级，不能静默把摘要绑定当完成。见 ADR-0003 双语。默认 issuer/jti 原子占用/digest、action 外执行、成功回执、失败释放占用保留摘要、运行项不因到期清掉，保留 exp+60 秒。持久事务/租约/崩溃归 adapter，不强制业务 schema，不称跨存储 exactly-once。

**实现：**固定 issuer 配置必需；Spring/Nimbus RS256/JWKS/typ/time 后用 Nimbus verified payload API 检查原始身份类型（numeric sub 原被 Nimbus 提前转文本，不是仅 Spring converter，回归确认）。接收强制 nonblank sid/jti、empty logout event、iat/exp/120秒寿命/60秒偏差；验证后 replay，只有成功返回 204、冲突/无效 400、执行中/存储或decoder基础设施失败 503，均 no-store，日志仅类别。默认 registry 成功才删、未失效索引保留，partial failure 重试剩余目标。当前无 replay 容量/lease/持久新策略。

| 阶段 | 命令/实际结果 | 说明 |
|---|---|---|
| 初始红灯 | `.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoLogoutTokenValidationIntegrationTest" --tests "org.las.sso.client.LasSsoBackChannelLogoutControllerTest" --no-daemon --console=plain`：18 项/13 失败/0 跳过 | 合法签名异常被接受、数组事件抛错、成功重传二次执行、执行错 400/204，非单纯模拟未签名攻击 |
| 首修 | 同命令 18 项仅旧自有 accept 测试失败 | 预期兼容变化的 503；升级 fixture process 并保留独立旧 adapter 503 测试 |
| 扩大负面/并发 | `.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoLogoutTokenValidationIntegrationTest" --tests "org.las.sso.client.LasSsoBackChannelLogoutControllerTest" --tests "org.las.sso.client.DefaultLasSsoLogoutReplayStoreTest" --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest" --tests "org.las.sso.client.LasSsoCustomAdapterAutoConfigurationTest" --no-daemon --console=plain` | 62/65 项阶段 numeric sub 单项仍红；确认 payload 数值且 Nimbus parse 提前 coercion，Spring converter 不足；改 verified payload 校验后 65 绿，补 JWKS 503/失败后冲突后 67 绿 |
| 最终全量 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`：178 server + **117 starter**，0 失败/错误/跳过，12 actionable tasks 全执行 | 约 01:59–02:02 Asia/Shanghai，BFF 重编译/NO-SOURCE。后补自有 decoder 固定 issuer 断言包含在此全量；相关子集为 68（33+15+5+14+1） |

最终 33 项 RSA/JWKS/实际 controller：30 invalid claims/signature/kid/alg/issuer/aud/typ/nonce/nbf、cached success、sid-only positive、actual JWKS 503；15 接收测试含并发同 Token 503→204、失败/partial/冲突/legacy/process/固定 issuer/log；5 默认 replay 含固定 Clock/latch 的 expiry/running/failure/accept 行为。46 新增相对 71 项；decoder/Session fixtures 所在层明示，已保存 raw numeric sub 断言。

**限制：**签名层调用 Controller 而非 live Servlet/MVC，B2 回调仍 fixture event/request；真实 SSO/TLS/代理/BFF/浏览器、持久/数据库 replay/租约/崩溃/多实例、容量/通知先于登记/在途请求未验收。服务端 DB 既有178实际重跑，B4 没新持久实现。自有 decoder 须遵守 raw/crypto contract，接收无法恢复它已转换的数据。旧红灯 XML 覆盖，保留命令输出/此节阶段证据。未提交/发布/部署。升级见[英文](../integration/backchannel-logout-receipts.md)／[中文](../zh-CN/integration/backchannel-logout-receipts.md)。

### 3.7 2026-10-04 B5 安全返回与本地授权事务

**范围/批准：**用户要求继续后推进starter登录入口/授权resolver与repository/成功跳转、参考BFF样例。已说明原流程丢returnTo/固定首页及禁止外部和歧义编码、绑定state/期限/单次方案，用户question选择“显式接入（推荐）”：非法/重复目标400，默认采用、自有success始终执行后自选公共目标/final handler，不能强制绕过业务检查。ADR-0004中英文同步；无业务权限/数据库或SSO密码证据改动。

**实现：**LasSsoReturnTo校验应用context内路径/2048字符/query（Unicode转ASCII），拒authority/fragment/反斜杠/raw和decoded控制空白/dot segments/semicolon/路径编码分隔符与残留percent。入口编码完整目标到本地授权URL，直接入口专用filter同400/no-store。resolver只将目标/创建时间加Spring request内部attributes（不发OP），repository委托Spring Session+匹配单state、同对象synchronized移除一次、十分钟精确到期/未来拒绝，新授权替换同Session旧请求。provider验证/登记后才公开目标；callback returnTo不起作用。默认final handler采用目标，无目标首页；自有delegate必须显式采用，业务403不会被改跳转。BFF profile明确采用、首页登录请求/protected。

| 阶段 | 命令/结果 | 证据 |
|---|---|---|
| 原始红灯 | `.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest.defaultSignedLoginReturnsToTheValidatedOriginalTarget" --no-daemon --console=plain`：2项/2失败/0跳过 | 实际签名Code/PKCE登录，路径与query目标均丢弃回/ |
| 初修/扩展 | callback+session专项18绿；target/callback66项阶段一失败 | 初修时补丁同文件重复operation使新增overload丢失、编译失败已修（setup非生产）。直接授权非法目标原断言假定有redirect失败，核对实为框架500，filter修400，已有有效事务仍能完成 |
| 最后专项 | `.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoReturnToTest" --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest" --tests "org.las.sso.client.LasSsoSessionChainIntegrationTest" --no-daemon --console=plain`：**83项**零失败/错误/跳过 | 最终45target+28callback+10既有链，含固定Clock/latch及完整并发Code回调 |
| 全量强制 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`：**178server+176starter**零失败/错误/跳过 | 约14:23–14:26Asia/Shanghai，12actionable全执行，BFF重编译/资源处理、NO-SOURCE；XMLtarget45在06:26:15UTC、callback28在06:26:04UTC |

**覆盖：**URI允许/拒绝矩阵、UTF8/query/context、重复/长度/缺省、十分钟/未来、错/重复state、跨Session/新请求替换、callback篡改/重放、nonce/OAuth错/过期兑换前拒绝、完整并发仅一exchange、自有success保留/opt-in/403和登记失败。目标不进OP参数，导航不重放写操作/产生近期密码证据/授予目标权限；新增59（45+14）相对117。

**兼容/限制：**无目标/default及原自有delegate保持；非法从忽略变400、显式默认目标生效，旧不带created属性的pending request须重新登录。配置公共签名保持，手动替换authorization storage/resolver须保留契约。consume仅同JVM相同Servlet Session对象，不称共享存储/多实例原子；真实MVC绑定/浏览器/SSO/代理context规范化、UI模板渲染/profile运行/消费二次redirect/重启未验。测试入站controller/Servlet/ID事件fixture，实际Spring链/provider/HTTP/RSA执行；server既有178DB重跑，未改schema/deps/locks，无提交/发布/部署。早期red XML覆盖，命令输出/此节保留。详见[English](../integration/login-return-target.md)／[中文](../zh-CN/integration/login-return-target.md)。

### 3.8 2026-10-04 A3 内部 Basic 哈希前独立预算

**范围/根因：**服务端FirstPartyClientAuthenticationFilter、专用budget和配置/错误路径。内部查询原直接lookup/hash，未接OAuth既有限流。已说明不能把10/20失败值直接套所有成功查询、失败后才加会并发超额；用户question明确选择内部独立预算，不共享OAuth失败scope。ADR-0005双语接受：现有短期表/HMAC/network、新scope，client10/network20/15m configurable；短新事务network→client原子条件占用/失败全回滚，hash在提交后无行锁，成功另一短事务仅退本次/失败崩溃成本留至到期。未知/非法输入有界解析共anonymous，不lookup/hash；active第一方先于hash。未改OAuth端点/权限/cache/schema/依赖/代理。

**响应/代价：**满桶429/RATE_LIMITED+Retry-After，即使Secret正确也hash前拒；DB reserve/auth/refund失败503/IDENTITY_SERVICE_UNAVAILABLE，no-store/problem+requestId/X-Request-Id/清SecurityContext。原401Basic challenge保留。Authorization4096/client128界限和多头/控制异常适用匿名budget。正常成功不累计也不清旧失败、无缓存；有效认证两短写，固定窗口边界/多键/合法高流量非全系统cap，崩溃占用至expiry、配置滚动需运维。无新metrics/alert完整实现，日志仅静态/类别。

| 阶段 | 命令/实际结果 | 证据 |
|---|---|---|
| 原始单红，15:23 | `.\gradlew.bat :test --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --no-daemon --console=plain`，1项/1失败/0跳过 | 第11错误Secret仍401，旧诊断25错误均401历史保留 |
| 初修，15:29 | 同新测试及ServiceAccess套件7项通过 | 第11为429且encoder.matches只有10，合法资格既有6项不退化 |
| 扩大，15:35 | 新19+资格6+OAuthconverter1，26通过 | success不累计、fail不清/partial rollback、IPv4/IPv6/匿名和编码、scope独立、25并发10hash/15拒、spy故障、Clock/window |
| 最后专项，15:44 | `.\gradlew.bat :test --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --tests "org.las.sso.security.ClientAuthenticationRateLimitConverterTest" --no-daemon --console=plain`：**32项**零失败/错误/跳过 | 新25＋6＋1，真实SQL第二桶check/refundtrigger故障两桶rollback、503/log/requestId/config/expiry cleanup |
| 强制全量，15:46–15:49 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`，**203server+176starter**零失败/错误/跳过，12task全执行 | BFF重编译/NO-SOURCE，新XML25项07:48:13UTC，未用UP-TO-DATE或skip充DB验收 |

**层级/限制：**真实PostgreSQL18.4/MockMvc/Spring security/Argon2；encoder/budget spy仅明示instrument/fault，25concurrent hash latch暂停证gate；Clock直构budget仍真实TransactionTemplate；SQLcheck/function/trigger仅独立一次性库finally删除、非migration/用户DB。不是liveServlet/TLS/proxyaddress、kill/restart/容量/连接池/多实例deploy、global跨窗口并发或所有query吞吐cap。HMAC/日志canary定向验非全量日志/SAST/metrics报警；OAuthconcurrency不顺带关闭，S3–S6/D1和SSO-04恢复/吞吐独立。红XML后覆盖，输出/此节保留。未提交/发布/部署，见[English](../integration/internal-basic-authentication-budget.md)／[中文](../zh-CN/integration/internal-basic-authentication-budget.md)。

### 3.9 2026-10-04 S3 注销用户优先锁与批次原子

**范围/根因/批准：**DeletionRequestService(request/cancel/execute/batch)、AdminDeletionRequestService(hold/release)、DeletionExecutionJob(expiry+execute)。原user→request与admin/job request→user(资格version)倒置，候选过期未重检/跨phase积累不同user。已说明改锁序/status/owner/deadline而保留30d/90d/API权限/S4边界；用户question选择“保留批次原子性（推荐）”，不逐用户commit，候选先读/DB UUID user集合先锁、busy跳过，job两phase预选并集避免后加user。ADR-0006中英。

**实施：**admin非锁candidate owner→lockuser→lock/recheck同request/owner/status、hold deadline等锁后重验；request时间user锁后，cancel已user优先；execute direct owned/PENDING/due，否则no-op，原promotedadmin protective cancel保持。batch有限candidate nonlocking，users ORDER BY id FOR UPDATE SKIP LOCKED一次预锁、request SKIP/recheck，status变更/version/audit/event/credentials/logoutoutbox同事务，jobadvisory保持/定时100expiry+100pending选/最多100execution；phase1后不加新user。count实际processed(含admincancel)，jobrelease+processed，同条两阶段计2，busy计0非backlog空。publicpositive limit未新cap100，公开构造/方法仍可用(旧jobid/eligibility构造参数兼容保留)，无schema/deps/locks/auth/permission/期限策略变。

| 阶段 | 命令/实际结果 | 证据 |
|---|---|---|
| 原3红，17:08 | `.\gradlew.bat :test --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --no-daemon --console=plain`：3/3失败/0跳过 | pg_blocking_pids证adminwaituser同时持request，独立SKIP probe失败；expirybusyuser未skip Future超时，不虚构deadlockvictim生产事故 |
| 首修，17:17 | 新3+旧注销10共13绿 | user-first/refactor两phasecoordinator；移动now声明编译错已纠正，非原缺陷 |
| 扩大，17:25/17:37 | 16新+10+6=32绿、后23新+10+6=39绿 | 双向commit/lockedcandidate/retry/partialbatch/directownerdeadline/realSQLrollback，扩大phaseunion/advisory/newrequest/owner/renewal |
| 最后专项，17:49–17:50 | `.\gradlew.bat :test --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --no-daemon --console=plain`：**40(24+10+6)**零失败/错误/跳过 | Clock在真实wait中advance，过期hold无version/audit；全部受控竞争 |
| 强制全量，17:54–17:57 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`：**227server/176starter**零失败/错误/跳过，12tasks全执行 | BFF重compile/NO-SOURCE，新24XML09:54:51UTC、旧10XML09:55:16UTC |

24新范围：admin先user后request、cancel/admin/delete双向、events/audit/version/credentials正确、lockeduser/request跳过再单次执行、hold延期/取消不被旧expiry、uncommittedhold、requestreplacement/腐化owner重检、directnotdue/wrongpair/held、批次countlimit；firstmutation前观察两phaseuser已锁、advisory/concurrentbatch去重；实际SQLtrigger第二deletion故障两targetrollback、明示spyphase2faultexpiry也rollback、交互rollback、Clockwaitdeadline。latch/pg_blocking_pids/独立probe为真实DB，非mock锁/randomsleep；SQLfault/owner腐化仅一次性DB并清，Clockmanualservice真实TransactionTemplate，eligibilityspy非注入时delegate。

**边界：**不是liveHTTP/browser/TLS/proxy/multi-instanceScheduler/crashrestore、锁容量/饥饿/latency或全系统lockgraph。busy可延迟，selection before skip可少于limit且不fill新user；batch持锁到commit/failure整批undo是用户取舍。caller已持其他user/request的组合事务需自守顺序。S3阶段S4–S6尚未实施，随后分别见§3.10–3.12；D1、B1持久/真实消费/版本和B2时序仍开放。原redXML后覆盖，输出/此节保留证据，无commit/发布/部署。见[English](deletion-concurrency.md)／[中文](../zh-CN/implementation/deletion-concurrency.md)。

### 3.10 2026-10-04 S4 管理员晋升前有效注销门禁

**范围/规则：**沿用2026-10-02已确认的ADR-0002；S3完成后继续执行S4。目标有有效PENDING/HELD时，所有者先认证取消、主机操作员再重试，不由晋升自动取消/释放。不新增产品规则、认证因素、公开晋升端点、schema或依赖。

**实现：**`AdminBootstrapService.promoteExistingUser`保留维护advisory lock和无管理员条件，先锁ACTIVE用户，再锁/检查有效注销请求，随后才访问用户名/验证临时密码及变更角色/安全状态。按状态而非期限判断：已到期PENDING、过期但仍HELD、释放暂缓后的PENDING均拒绝。已取消历史不阻止合法ACTIVE用户，新的有效请求仍阻止；冻结/删除/未知和已有管理员沿用原拒绝。

拒绝码`DELETION_CANCELLATION_REQUIRED`，完整行快照验证角色/hash/凭据及资格版本、注销申请、恢复状态、会话/Authorization、审计/安全事件/outbox均不变。取消后成功晋升保留终态申请历史，继续临时密码/安全失效/成功审计。命令提示先取消再重试、不输出成功并继续抛错；包内Console supplier fixture验证两次密码缓冲清零和输出无密码，生产公开构造器仍用System.console()。

| 阶段 | 命令/实际结果 | 证据与边界 |
|---|---|---|
| 原PENDING/HELD红灯 | `.\gradlew.bat :test --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --no-daemon --console=plain`；初期两条目标回归因未抛拒绝而失败 | 第一轮HELD setup复用RegisteredClient Secret先失败，改唯一fixture Secret后两条才确认实际缺陷；不把setup错误算生产漏洞。红XML后来覆盖 |
| 最后专项，约19:23 Asia/Shanghai | `.\gradlew.bat :test --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --tests "org.las.sso.maintenance.AdminBootstrapCommandTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --tests "org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest" --no-daemon --console=plain`；**90（25+3+10+24+28）**零失败/错误/跳过 | 实际PostgreSQL18.4/Spring代理服务；Console为mock，不是真实终端 |
| 强制全量，约19:25–19:28 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`；**255 server / 176 starter**零失败/错误/跳过，12 actionable全执行 | BFF重新编译，自身`test NO-SOURCE`；新增晋升XML25项11:26:48UTC、命令XML3项11:26:46UTC。相对227 server新增28项 |

25项数据库覆盖PENDING/HELD无变更、到期状态、取消后重试/新申请、无申请/非ACTIVE/未知/已有管理员、门禁先于密码策略，以及申请/晋升/取消/删除执行/HELD/冻结受控交错。`pg_blocking_pids`/latch确认实际用户锁等待，独立SKIP probe证明等用户时没有请求锁。多个合法维护竞争仅一管理员；外层事务失败和实际SQL审计trigger失败证明早期角色/凭据/安全变更全部回滚并可重试。冻结状态变化为明示隔离库SQL fixture，不是freeze HTTP流程验收。

旧`DeletionRequestServiceTest`改名`executionProtectsAdministratorInLegacyInconsistentDeletionState`，隔离库直接造历史/腐化管理员与有效申请组合验证执行防御，不再通过支持的晋升制造该状态。命令另3项验证新拒绝/无关失败/成功、失败传播和密码数组清理。

**边界：**未运行真实JVM隐藏输入/PowerShell/JAR退出码、浏览器取消→晋升→首次改密、多实例维护/崩溃恢复/容量或生产部署。包装脚本只读未改；SQL故障fixture仅一次性测试库并清理。服务级空库测试仍通过，不代表真实交互验收。S4阶段S5/S6尚未实施，随后见§3.11/3.12；D1、完整B1/真实消费/版本及B2更广时序独立开放；改动未提交/发布/部署。详见[English晋升契约](admin-promotion-deletion.md)／[中文](../zh-CN/implementation/admin-promotion-deletion.md)。

### 3.11 2026-10-04 S5 管理员墓碑详情与明确无凭据展示

**范围/根因：**注销执行删除password_credentials但保留users/当前用户名；`AdminUserManagementService.user`原INNER JOIN丢失墓碑，API404、`/admin/users`自动/显式选择同样404。反馈闭环先真实注册→申请→到期fixture→service执行删除；列表有账号/凭据为零、详情却ADMIN_TARGET_NOT_FOUND，后扩大API/页面三红。目标确为原S5，不是用户名丢失或纯mock映射。

**实现：**仅将详情凭据关联改LEFT JOIN，原DTO/端点不加字段；无凭据时credentialState/passwordChangedAt/lockedUntil明确null、不制造状态/时间。模板显示无凭据/不适用，有凭据无锁仍未锁定。墓碑已删除状态/去标识显示名保留；原写门禁/按钮条件不变。非终态缺凭据fixture也可检查但登录和资格仍拒绝；不自动创建/恢复凭据。无schema/deps/认证因素/权限/保留期改动。

| 阶段 | 命令/实际结果 | 证据 |
|---|---|---|
| 最小红灯，20:31–20:32 | `.\gradlew.bat :test --tests "org.las.sso.admin.AdminUserTombstoneIntegrationTest" --no-daemon --console=plain`；1项/1失败/0错误/跳过 | 实际PostgreSQL18.4，原XML12:31:59UTC；列表保留同目标、密码行删除后详情抛ADMIN_TARGET_NOT_FOUND |
| 扩大红绿，约20:35–20:37 | 同命令：3项/3失败；仅改join后2绿1红；模板fallback后3绿 | 两阶段区分丢行与空白/不正确无值展示，实际MVC JSON与Thymeleaf |
| 18项专项，约20:45 | 同命令：18项零失败/错误/跳过 | 扩大终态写拒绝、只读快照、分页/原凭据/冻结/授权及提交前后读取 |
| 相关专项，20:46–20:47 | `.\gradlew.bat :test --tests "org.las.sso.admin.AdminUserTombstoneIntegrationTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --tests "org.las.sso.admin.AdminUsersTemplateContractTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --no-daemon --console=plain`；**138（18+10+24+25+2+53+6）**零失败/错误/跳过 | 18项XML12:46:46UTC，既有管理/分页/注销/晋升/资格回归实际运行 |
| 强制全量，20:48–20:50 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`；**273 server/176 starter**零失败/错误/跳过，12task全执行 | BFF重编译/NO-SOURCE，新18项XML12:48:55.549UTC；相对S4的255新增18 |

18项覆盖实际注销后的列表/详情、API200/no-store/三个字段确有null/无hash恢复码、模板自动/显式/被filter排除选择、未知404、反复读取目标十二表快照不变、四写409/快照不变、普通/匿名不可读、三种凭据原状态和时间、冻结和解冻按钮、混合/DELETED cursor页无漏重复、不一致缺凭据仍资格DENIED/无法load登录；另一实际事务在删除未提交时读旧完整详情，commit后墓碑。读取不持写锁，不把list/detail多次查询说成统一快照。

**边界：**实际Spring安全/MVC/JSON/Thymeleaf/PostgreSQL，入站Servlet/管理员role/认证/近期时间为fixture，非真实密码/bootstrap/浏览器TCP/JS/可访问性/proxy/部署。目标通过真实owning services，缺凭据非终态/管理role是明示隔离SQL。快照覆盖目标不声称actor Session/每个辅助表不变。当前用户名历史仍保留查询前提，不重建损坏username；未做容量/崩溃/多实例，也不关闭完整SSO-04终态/恢复/吞吐交付。S5阶段S6尚未修复，随后见§3.12；D1/B1完整消费版本开放，未提交/发布/部署。红XML被后续覆盖，命令输出/本节保留阶段；详见[English](admin-tombstone-views.md)／[中文](../zh-CN/implementation/admin-tombstone-views.md)。

### 3.12 2026-10-04 S6 恢复凭据80bit与旧摘要兼容

**范围/修复：**原两生成入口三组×四个32字符随机位=60bit，低于既有80bit规范。先跑随机字符预算（去固定前缀/分隔符），两条均红。现仅加第四组，保留SecureRandom.nextInt(32)逐字符均匀选择，16×5=80bit；注册/普通rotation/人工issue均用同生成器，普通仍十枚。恢复页placeholder更新，64长度与JS按前缀type判断不变。无schema/依赖/pepper/摘要/规范化/权限/认证因素/公开入口改变。

**旧码规则：**既有三组码不批量强制撤销、不自动重写或续期，仍为60bit；同当前用户名/UUID/type摘要匹配、未用未撤销及人工原expires条件才接受。普通显式rotation废除旧批次，新十枚80bit；人工reissue废以前未用码，新30m80bit。恢复start单次/10mSession、普通完成保留同批其他未用码、改密/维护失效原样。升级不提高旧码本身强度；旧版本实例仍可签60bit，全部签发实例更新后才可称所有新签发80bit，live混合版本未演练。

| 阶段 | 命令/实际结果 | 证据 |
|---|---|---|
| 熵红灯 | `.\gradlew.bat :test --tests "org.las.sso.account.RecoveryCodeServiceTest" --no-daemon --console=plain`；当时3项/2失败/0错误跳过 | 普通/人工去前缀随机字符预算仅60；原生成/HMAC对照通过 |
| 初兼容扩大，约21:18 | generator+compatibility33项阶段2失败 | test helper直接nullable used_at scalar引JdbcClient TypeMismatch；改nullable行record解决，非业务漏洞 |
| 生成/兼容/admin，约21:23 | 三套最终前一阶段46项绿（9JVM+31兼容+6admin） | 随后补2限流兼容项、强化ALREADY_USED阶段审计 |
| 最后相关，21:25–21:26 | `.\gradlew.bat :test --tests "org.las.sso.account.RecoveryCodeServiceTest" --tests "org.las.sso.account.RecoveryCredentialCompatibilityIntegrationTest" --tests "org.las.sso.account.PasswordRecoveryInvalidationIntegrationTest" --tests "org.las.sso.admin.AdminRecoveryCredentialServiceTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --no-daemon --console=plain`；**164（9+33+28+6+53+25+10）**零失败/错误/跳过 | JVM9为原1+8新增；兼容33新增，实际PostgreSQL18.4、Spring代理/MVC/原恢复Controller |
| 强制全量，21:27–21:30 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`；**314 server/176 starter**零失败/错误/跳过，12task全执行 | BFF重编译/NO-SOURCE，9JVM XML13:27:56.325UTC、33兼容XML13:27:56.343UTC；相对273新增41 |

JVM固定向量独立计算：普通旧/人工旧/普通新HMAC，横杠/Locale.ROOT大写、前缀区别、digest array copy与格式/十枚。密码学熵结论由源码CSPRNG均匀选择＋格式预算，不称统计认证或只看无重复就证明强度。

33数据库/MockMvc覆盖旧新×普通人工完整恢复/code/Session重放、ALREADY_USED/成功request审计、旧规范化/撤销/过期与行不变、过期审计无固定明文、普通rotation/人工reissue/新摘要/30m、外层rollback还原旧有效和去新行审计、同批第二旧码保持、改密未用旧码/已建Session失效、10mSession过期不改密、错用户/type、旧兼容不绕用户CAPTCHA/预算、四HTTP204/no-store/SecureHttpOnlyStrictCookie/实际改密、旧码并发仅一Session、恢复页实际模板。

旧明文为明示固定fixture，用test profile pepper/JCA独立HMAC后替换owning service已建行的摘要，不用新生成器当旧兼容。目标注册/当前凭据走真实service；service人工issuer仅registered actor fixture，protected admin API另原套件验。并发barrier/Future真实DB原user lock，不是每种锁先后穷举。更早redXML被后续覆盖，输出/此节保留阶段。

**边界：**无真实browser/TCP/TLS/proxy、JS打印下载/clipboard/可访问性、混合发行live/容量/崩溃/迁移；rollback为显式外层异常。定向audit无明文非全量Secret扫描。已有S1双向/维护/注册rotation一次性API在原范围重跑；不顺带关闭D1/A1A2S2更广/完整B1消费版本/SSO-04与release。改动未提交/发布/部署；见[English](recovery-credential-entropy.md)／[中文](../zh-CN/implementation/recovery-credential-entropy.md)。

### 3.13 2026-10-04 D1 默认同意页补丁与依赖消费对齐

**官方核验/范围：**重新读取Spring CVE-2026-47877公告，7.1.0受影响、OSS7.1.1修复；7.1.1BOM包含Authorization Server，7.1.0/7.1.1源码确认raw拼接→HtmlUtils escape。LAS reviewed partner沿默认consent，本轮不做custom consent绕过。早期应用注入未复现保留；新增fixture renderer证明依赖缺陷，不称每个输入可通过LAS client/scope/UUID门禁或已浏览器XSS。

**升级/锁：**catalog新增Security7.1.1和BOM/config/client/JOSE坐标；server/BFF导入Security BOM，starter本地Boot security属性覆盖并导出native api platform+直接使用的三个Security API版本。三锁由Gradle重写/审查：server九Security项/另两各八均7.1.0→7.1.1，三JOSE10.9→10.9.1、client/BFF SDK11.37.2→11.38.2、后两加BOM项。starter既有四testJackson新增保留/非D1，其余无锁坐标/version/config变化。Boot4.1.0/Framework7.0.8/Jackson3.1.4/Java21不变；上游部分transitive更高版本仍受Boot管理，本组合patch override实测，不称官方默认组合。无生产源码/schema/权限/因素/配置Secret修改。

**元数据缺口并修复：**仅DM BOM源码绿、.module无patch约束；加native api platform后独立Gradle原生metadata消费者7.1.1，POM-only仍7.1.0。starter显式config/client/JOSE catalog依赖使原生与POM-only均全Security7.1.1。外部一次性file repo本地probe发0.1.0-d1-probe再新0.1.0-d1-probe-2；消费原生Boot4.1.0platform、无composite/SSO锁/SSO解析rule，分别gradleMetadata与mavenPom/ignoreGradleMetadataRedirection，实际runtime模块断言。无MavenCLI（不可用）、远程upload/全局MavenLocal或托管release；不保证consumer强制旧锁/override，默认版本仍0.1.0-SNAPSHOT、最终普通metadata已生成。PowerShell第一发未quote属性导致task解析失败，quote后成功，非publication逻辑缺陷。

| 阶段 | 实际命令/结果 | 证据/边界 |
|---|---|---|
| 原renderer红灯，约21:57–22:00 | `.\gradlew.bat :test --tests "org.las.sso.config.DefaultConsentPageDependencyTest" --no-daemon --console=plain`；最终旧7.1.0 **5/5失败/0错误跳过** | 第一轮六400是converter fixture未到renderer，隔离converter并删Code流不传的user_code后五项都因rawHTML红，非虚构应用XSS |
| 依赖锁生成 | `.\gradlew.bat dependencies :las-sso-spring-boot-starter:dependencies :las-sso-reference-bff:dependencies --write-locks --no-daemon --console=plain` | 逐次生成/最终审查三锁；dependencyInsight也验三个runtime实际7.1.1 |
| 初绿/consent，约22:08 | renderer5+真实consent4+reauth10 **19项通过** | 7.1.1 renderer escape、实际配置partner批准/取消/不同主体/一方免同意，非browser执行 |
| 相关专项，约22:22–22:23 | server **110=5+4+10+53+2+25+11**；starter **131=28+10+33+15+45**，均0失败/错误/跳过，BFFcompile | 命令见双语patch记录；JDBC/密码重认证/RT/Basic预算/clientSecret/签名logout/return与自有链兼容 |
| 强制全量/打包，22:24–22:26 | `.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication --rerun-tasks --no-daemon --console=plain`；**323server/176starter**零失败/错误/跳过，18task全执行 | BFF重编译/NO-SOURCE；renderer5 XML14:25:25.244UTC/consent4 14:25:21.810UTC；实际PostgreSQL18.4，较314新增9 |

五renderer覆盖clientId/state/principal/requested/approved scope，converter/authentication结果fixture、实际依赖filter/default page；四配置真实provider/JDBC/MVC覆盖合作方approve/codePKCE签名UUIDaudnonceRT/Consent保存/原caller state、取消无批准、别主体不可approve、一方skip。真实caller注入state不渲染因原consent生成新state，不将它算漏洞red/green。无device/requestURI renderer宣称。两bootJar BOOT-INF/lib Security全部7.1.1，server含fixedAS，JOSE10.9.1/SDK11.38.2实查；只打包未启动Jar。

**边界：**指定D1受影响依赖与checked compatibility已修，完整SCA/SAST/Secretlog和新告警扫描未完成。非liveTCP/browser/scriptexecution/consentJS/CSP/TLSproxy/生产key、liveartifactconsumer/备份restartdeploy；A1A2S2更广/B1durableversion/SSO交付仍开放。本轮只有一次性file publication probe，未托管发布/部署/commit/用户库操作。红XML后续覆盖，输出/本节保留阶段。详见[English](spring-security-consent-patch.md)／[中文](../zh-CN/implementation/spring-security-consent-patch.md)。

### 3.14 2026-10-04 A1/A2 真实Servlet HTTP编码路径与风控

**范围：**既有生产matcher/role与login解码修复扩大到实际TCP/Tomcat11.0.22/Security7.1.1/MVC/Argon2/SpringSessionJDBC/PostgreSQL18.4，非MockMvc/注入auth。新增ServletPathSecurityIntegrationTest48项＋继承矩阵ServletContextPathSecurityIntegrationTest48项context=/passport。当前无新业务偏差，仅test/docs，不改源码/schema/deps/权限/凭据/proxy。

**环境/fixture：**RANDOM_PORT明确server.address=127.0.0.1/forward-headers-strategy=none、停scheduling/cleanup；test threshold CAPTCHA2/block3/jitter0、window240h避免短窗口边界，每case清一次性库相关scope。真实账号/password/CSRF/form/cookie/JDBC后续重载；HTTP helper显式转发SecureCookie于HTTP，不声称browserSecure/SameSite/__Host/TLS验收。CAPTCHA实际HTTPOrigin用固定testissuer9000非随机监听port，是明示fixture输入，非proxyorigin验收。clienttype/status metadata与负proof purpose/network/time为SQL/servicefixture，正proof真实HTTP签发/ALTCHA求解。

**A1每实例20项：**plain/%70rincipals/%69nternal/service-%61ccess，合法ACTIVEFIRSTPARTY Basic known/unknown200/minimalno-store/成功退款；匿名401Basic challenge；真实普通Cookie不可替且之后自己profile仍有效；wrongsecret/unknown/partner/disabled/缺metadata/不支持Basic/malformed/Bearer拒；服务Basic＋人Cookie不覆盖持久auth；十错耗尽clientbudget正确Basic普通编码429；double/semicolon/encodedslash/NUL/backslash即正确BasicCookie也400，无decision/subject/stateVersion。400可回显caller输入path/UUID不是资格泄漏，不把它算业务缺陷。

**A2每实例28项：**/login,/log%69n,/%6cogin：普通正确认证/一userSession一subjectJDBCSession/profile后续；missingCSRF403不计失败；错password同userIP计数；block429不认证；CAPTCHA missing/malformed不验password、合法HTTPproof一次/另一helper重放拒；proof＋错password消费并记失败，不能重用；CSRF在proof消费前且proof后可用；错purpose/network/expiry拒未消费；真实错误跨path进入CAPTCHA再block；block先于proof，forwardheadersnone时spoofXFF/Forwarded不绕；五歧义login不认证/不改用户失败。非完整UIcontext/forwardproxy/生产窗口验收。

| 阶段 | 命令/实际结果 | 证据/限制 |
|---|---|---|
| 初3项，约23:05 | `.\gradlew.bat :test --tests "org.las.sso.security.ServletPathSecurityIntegrationTest" --no-daemon --console=plain`；blocked三路径绿 | 实际Tomcat/TCP与DB，无新增red漏洞 |
| root40扩大，约23:12 | 同命令40/12失败，均fixture | helper未实现/api/v1/me导致profile404；Origin随机port与固定issuer冲突403。改现有/profile及显式issuerOrigin |
| 双40，约23:15 | `.\gradlew.bat :test --tests "org.las.sso.security.ServletPathSecurityIntegrationTest" --tests "org.las.sso.security.ServletContextPathSecurityIntegrationTest" --no-daemon --console=plain`；80绿 | 后补startup属性断言才发现子注解覆盖parentproperties，不将本阶段算最终网络配置验收 |
| 双48扩大/校准，约23:22–23:26 | 同两套；96/50失败→96绿 | child48因server.address缺失setup、root2因通用400回显path断言过严；共享@TestPropertySource继承/资格字段断言修正，最终assertloopback/forwardnone/window240h |
| 相关专项，23:27–23:28 | `.\gradlew.bat :test --tests "org.las.sso.security.ServletPathSecurityIntegrationTest" --tests "org.las.sso.security.ServletContextPathSecurityIntegrationTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.security.LoginFailureResponseJitterTest" --no-daemon --console=plain`；**181=48+48+6+25+53+1**零失败/错误/跳过 | 既有MockMvc/预算/登录/会话回归实际执行 |
| 强制全量，23:30–23:34 | `.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain`；**419server/176starter**零失败/错误/跳过，12task全执行 | BFFcompile/NO-SOURCE，root48XML15:32:45.005UTC、context48XML15:32:33.975UTC，较323新增96 |

**状态/边界：**A1/A2既有修复现有直连Servlet根/context证据，本任务不造旧code漏洞red，不把fixture失败当生产缺陷；实际target与filter/MVC闭环补原MockMvc。productionreverseproxy归一/信任、TLS/browsercookie/JS/可访问性、所有container/dispatch/method/URI fuzz、并发CAPTCHA/生产windowthreshold/容量/崩溃restart/live多实例/fullSecretlog未验。SSO-04version恢复终态吞吐/观察保证、S2更广/B1消费持久version独立。未commit/发布deploy/用户库操作。早XML后覆盖，命令输出/本节保留阶段，见[English](servlet-path-security.md)／[中文](../zh-CN/implementation/servlet-path-security.md)。

### 3.15 2026-10-05 S2 客户端Secret竞争与失败回滚扩大

新增AdminClientConcurrencyIntegrationTest23实际PostgreSQL18.4/Spring代理/MockMvc：name/rotation、shrink/rotation、disable/rotation、enable/rotation双向＋rotation/rotation九pair，pg_blocking_pids/latch观察持锁等待后终态Secret/version/name/scopes/status/authconsent/outbox；不同client不阻。原patch/rotate/disable先metadata后最新RegisteredClient，enable仅metadataUPDATE不回写Secret，无新生产修复。

另实际Basicold401/new200、OAuth token故意不存在Code旧invalid_client/newinvalid_grant、disable-rotate-enable不复旧auth、五auditSQLtrigger/outbox/idempotencytrigger失败九表快照rollback、首rotationrollback等待edit、并发同key一明文一replay/材料改变拒/失败后重试。actor只是已注册servicefixture非browseradmin授权，auth/consentowningservice/sid明示fixture，fault仅一次性库finally清。非直接repository/seeder/manualSQL/新授权发行race/全系统lockgraph。

初17绿；扩大23＋client11＋persistence53＋consent4的91项一断言错：OAuthhandler原invalid_client400而test假定401，改400/error码对照非handler变更。最后91绿、clientquerypersistence组合110绿；无skip。共同devtupleforced460server178starter全绿18task，23XML2026-10-04T17:46:24.196Z（上海10-05）。命令/界限见[English](client-secret-concurrency.md)／[中文](../zh-CN/implementation/client-secret-concurrency.md)，不造新增漏洞red，liveclientrollout/grantmutationrace/capacitycrash仍独立。

### 3.16 2026-10-05 SSO-04 查询epoch/终态wire与负载观察

用户question明确选择新增stateEpoch复用security_epoch，ADR-0007；新第七字段，同SQLsnapshot取epoch/user/credential/deletion，unknowncurrentepoch/version0/changedAtnull，missingrow/blankepochDataAccess类别503/no-store/IDENTITY_SERVICE_UNAVAILABLE。version仅同issuer/sub/epoch单调，opaque不排序、changedAt不排序；restore前new epoch，consumerfreshgeneration屏障废旧cache/inflight/拒迟到旧代次，不随任意响应切换。无migration。server只交facts，RPcache/真实restore未实现。

新增ServiceAccessLifecycleContractIntegrationTest17actualDB/MockMvc：ACTIVEfreezeunfreeze/requestholdreleasecancel/re-requestexecute版本1→9、reasonpriority/非法共存SQL、缺disabledrequiredcredential、七字段minimal/no-store、rollback/rejected/changedAt稳定、ACTIVE普通改密recover仅credentials_version推进不改资格、requiredexit推进、未commitfreeze并发旧snapshot、低counternew epoch恢复shape/rollback/missingblank503。无实际pgdump/restore/livecache验收。

新增ServiceAccessQueryLoadIntegrationTest1实际loopbackTomcat/TCP/Argon2/DB：warmup4+8worker×10=80合法query全200，无counter累积，最后观测总1343.0ms/59.57rps/p50112.9/p95203.5/p99232.1ms。本机观察非productionSLA/10m持续/超budget并发capacity/consumer一分钟保证。17+6query基础+25budget共48绿，combined110亦绿；最终query17XML17:47:04.060UTC/load1 17:47:10.736UTC。详见[English](service-access-epoch-contract.md)／[中文](../zh-CN/implementation/service-access-epoch-contract.md)。

### 3.17 2026-10-05 SSO-03/06 公共参数接点与可辨识开发组合

publicLasSsoSecurityConfigurer新configure(http,success,Consumer<OAuth2AuthorizationRequest.Builder>)，原双参数保留；PKCEcustomizer后trustedapplicationparams，localstate/target属性保持。示例promptlogin/max_age0意味着该configuredchain每authorization重认证，不自动按操作识别，default不透传browserprompt/max_age/auth_time。应用须保留clientredirectstate noncePKCE，成功Spring验证/register后OidcUser.idToken.authenticatedAt可取；pendingbrowser/login/15mexpiry/revoke及再次确认highriskwrite归RP，无MFA/permissionclaim。

两新增签名callbackfixture/package-externalconsumer验可信prompt/max_age保持PKCE/state/nonce/returnTo、success访问auth_time/立即logout与default不透传任意参数，callback30新增2/related85（30+45target+10chain）绿。OP签名loopback/入站Servlet事件fixture非liveLAS/browserevidence消费。server原reauth/JDBC证据独立。

开发ID **0.1.0-dev.20261005.1**，serverversion新增可选lasSsoServerVersion/refBFF同；starter原lasSsoStarterVersion，默认仍SNAPSHOT。2026-10-05约01:45–01:49 `.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261005.1" "-PlasSsoStarterVersion=0.1.0-dev.20261005.1" --rerun-tasks --no-daemon --console=plain`：**460server/178starter零失败/错误/跳过，18task执行**，BFFcompileNO-SOURCE，两bootJar/metadata。server新增41(23+17+1)、starter2，较419/176。

临时filedevrepo实际发上述startercoordinate，独立两Gradleconsumer nativeBoot4.1.0无SSOcomposite/继承锁，原生module/POM-only各编译publicold/new方法包外源码并Security全7.1.1，非MavenCLI/liveconsumer/托管发行。manifest记录basecommit8cee3ed...加未提交worktree、263file sourcefingerprint96c475...、三lock/Jar/POM/moduleSHA256；HEAD不等源码、ID不得覆盖不同内容。无commitpush/productionDB/托管release/deploy，仅本地identifieddevpublication。见[English开发交付](../integration/development-delivery-2026-10-05.md)／[中文](../zh-CN/integration/development-delivery-2026-10-05.md)及[JSONmanifest](../integration/development-delivery-2026-10-05.json)。

### 3.18 2026-10-05 UserInfo修复、真实浏览器与.2交付

真实openid profile参考BFF在Code兑换后invalid_user_info_response：/userinfo Bearer302到/login。AS链缺resource-server；只加JWT仍红，因为Security7.1.1自动选client-secret budget AuthenticationConverter Bean，其返回null。正确AS链Filter探针确认Bearer无认证；显式BearerTokenAuthenticationConverter/既有JWTdecoder/UserInfo Bearer401 entry point修复，临时diagnostics已从源码删除。无dependencylock/schema/scope变化，starter原签名OP fixture仅openid+Token/JWKS，未覆盖UserInfo。

新增真实账号/登录/CodeS256/AT UserInfo回归先反复200vs302红→显式converter绿，扩大GET/POST200 UUIDsub/profile/noemail，missing/HTML/cookie-only/revoked/malformed401。reauth11+consent4专项15零失败/错误/跳过约03:36。真实.2 server/BFF Jar、local profile/loopbackHTTP/临时PostgreSQL18.4/Edge Playwright1.55.1，每次两独立Context/新注册/实际ALTCHA，默认与application-security均验双密码登录/protected/cookie不同/两SSO sid、别browser challenge401/错误密码不升级/正确重输签名auth_time更新sid保持、当前退出仅A/SSO免密码返回、全局退出真实Back-channel清B/SSO活动0。reauth参数为脚本在BFF Location加标准prompt/max_age保持statePKCE，非RP pending/15m门禁。

早429为一次性注册桶耗尽，仅fixture reset；Playwrightroute注入未加参数导致普通登录为harness校准，不造生产红。强制.2全量461server178starter零失败/错误/跳过18tasks，BFFNO-SOURCE/browser独立脚本；source263fingerprint64bee2.../三锁/JAR/POM/modulemanifest、两包外metadata制品compile/Security7.1.1。本地HTTP非productionTLSproxySecureCookie/完整UI/刷新liveexpiry/durableordering/restartmultiinstance/actualrestore/fullscan；.1ID内容不覆盖，.1manifest历史不等current。命令/来源/完整边界见[English .2](../integration/development-delivery-2026-10-05-2.md)／[中文](../zh-CN/integration/development-delivery-2026-10-05-2.md)／[manifest](../integration/development-delivery-2026-10-05-2.json)。

### 3.19 2026-10-05 B2 sid时序与维护.3

用户question明确选择默认sid marker，保留配置sessionAbsoluteLifetime默认14天/noargSPI保留/customstore自保。两真实签名default/appchain早Logout204后迟到callback仍success回归先红；marker/check与indexsnapshotpublish短锁、Sessioninvalidate/listener锁外、无匹配及失败仍标记，late register抛错沿503清本TokenSession/submitsink无业务success；别sid不影响/unregister不删marker/重复延长/exactexpiry。registry10新受控Clock/latch/两order/reentrant/failedretry；callback增5至35，含HTTPexchange中Logout两chain/错误签名不污染。中间59绿，最终654全量零failerror skip18task；no新migrationdep锁。

bootstrap/reset新增JarPath/ExpectedSha256共享helper，缺失/hash不符Java前拒，不选latest保SNAPSHOT旧默认；三个scripts移交hash。9PowerShelllaunchfixture与两实际.3JAR非交互local/临时DB拒INTERACTIVE_CONSOLE_REQUIRED/securitysnapshot不变，非成功隐藏输入演练。最终.3默认自有Edge双context/ALTCHA/login/reauth/currentglobalBack-channel皆过，四logtargetedcanary无命中/临时进程DB已清。开发.3manifest267scope含scripts/8artifact3locks，两nativePOMcompileSecurity7.1.1；.2历史不等currentsource。非durable/restartmulti-instance/customadapter/在途business/expiry后永久保护，容量内存/墙钟独立。见[English.3](../integration/development-delivery-2026-10-05-3.md)／[中文](../zh-CN/integration/development-delivery-2026-10-05-3.md)／[ADR-0008](../adr/0008-retain-sid-revocations-before-local-registration.md)。

### 3.20 2026-10-05参考持久撤销/.4

用户question选参考BFF profile而非新JDBC库/LASLOG表；独立PG/schemaV1/外keyAES256GCM nonce96/tag128/lengthprefixAAD绑定task身份地址hintkeyId，ClientSecret不入库/currentregistration核issuerclientendpoint再发送。sink REQUIRES_NEWcommit返回，default不DB。每5秒串行最多10、即时SKIPLOCKEDclaim/DBtime两分钟lease/tokenfence、领取即计8attempt，禁redirect/仅200、随机retry/category/dead，第8crash到期LEASE_EXHAUSTED，成功擦ciphertextnonce。local先退出不变presubmitcrash仍缺；singlekey/operatordead恢复/cleanup/productioncapacityTLS独立。

34actualPG/AESHTTP+3context37：独立提交/outerrollback/SQLinsertclaimretryreceiptfault、14statusmatrix/noredirect、leaseoldowner/concurrent、8crash、200DBfail重复、transport/config/key/tamper/differenttask、currentSecret/本地logout失败/defaultnoDB/missingfail。初contextregistration/JWKS缺为fixture，初browser误期待SSO撤销删行实际标invalidated，改metadata＋真实refreshinvalid_grant非SSO缺陷。最终强制22:03–22:11 .4 server461/starter193/ref37=691零failerror skip20tasks/两JARmetadata；ref独加JDBCFlywayPG/TC锁，starterserverAPI锁不变。实际Edge default/appchain暂停worker提交→停BFF→新JVM同DBkey完成擦cipher→旧RT400invalid_grant/另一授权保持/SSO免密码返回globallogout过，最终构建重验；非liveleasecrash每指令/multiinstanceSession。278source/9artifactrefV1/3lockmanifest与两artifactcompile全Security7.1.1，参考37不再NO-SOURCE。见[English.4](../integration/development-delivery-2026-10-05-4.md)／[中文](../zh-CN/integration/development-delivery-2026-10-05-4.md)／[ADR-0009](../adr/0009-reference-bff-durable-token-revocation.md)。

### 3.21 2026-10-06可执行恢复屏障/真实演练

新增SSO SecurityEpochRecoveryService/command/script：expected旧epoch匹配FORUPDATE/重复并发拒、非Web/禁调度/console确认/有界reason，同事务换epoch、revokemetadata/deleteSpringSessionAuth/所有RT history、清OIDCchallenges/rateCAPTCHArecoveryregistrationoperation短事务，保userscredentialsclientsconsentdeletionaudit/outboxpayloadreceipt，只释pendingclaim/attemptscheduleddead不变。18新增=12actualPG/6consolefixture；六SQLfault全状态rollback、永久facts/claim与terminal保留、concurrentexpected唯一。无migrationdep锁。

实际PG18.4 source→pg_dump-Fc→新targetpg_restore-exit-on-error、两RSA3072 PKCS#12，真实PTY hiddenconsolebootstrap/PowerShell精确JAR/hash恢复confirm、oldSSO/BFFcookie/CodePKCE/ATuserinfo/RT拒、新keykid/signedID旧keypass新keyfail、恢复资格version2→1属newopaqueepoch，testgeneration屏障拒storedoldreply非LASLOGruntime。原UUID/password/client/credential/recovery/adminfacts保，开调度保outbox处理/近到期deletionfixture执行，fresh双browser密码login/globalBack-channel清两Session。最后JAR重复全流程，true no-console/cancel/repeatoldepoch nonzero快照不变；12logtargetedfixturepassword/keypassword/pepper/token等无命中。源码constructor/finalproxy/FKfixture/challenge开发错误已修非旧发行漏洞，Nodepoolrestartclosedsocket一次transportretry为harness。

最终02:08–02:14 .20261006.1 479server193starter37ref=709零failerror skip20tasks，两bootJarmetadata/filepubnativePOMcompile，284sourcefingerprint29dcfbc.../10artifactscriptmigration3unchangedlocks，旧IDsnapshot保留。来源[English](../integration/development-delivery-2026-10-06-1.md)／[中文](../zh-CN/integration/development-delivery-2026-10-06-1.md)／[ADR-0010](../adr/0010-executable-restore-epoch-barrier.md)。非productionbackupRPOkey/proxyTLScachedoldkid/真实consumerbarrier；不复原backup后改密/凭据使用/账号事实，firstchange/resetmaintenanceUI/全scan容量仍开，无commitremote生产DB。

### 3.22 2026-10-06管理员维护/OIDC撤销Cookie续行/.2

实际PowerShell/JARhash/WindowsPTY空库hiddenbootstrap错确认nonzero不改/成功uniqueadminversion1临时24h，Edge两Context强改、managementaccountOIDC拒/recovery403、缺CSRF外Origin403/错临时401/前端confirm拒不改；successUI204ACTIVE2tempNULL/其他SSOCookie与临时password拒。freshnew登录管理页/recovery十fourgroup80bit展示/真downloadfile内容匹配删除/reload不重显DBdigest、双adminBFF CodePKCETokenUserInfo。停SSO做真resetcancelmismatchreuseinvalidshortnonzerosecuritysnapshot不变/hidden successACTIVE3oneaudit/recovery旧rowsrevoked/SessionsAuthRTfamily废/outbox，samekey重启真实Back-channel清双BFF、oldCookieAT401RTinvalid_grantpassword拒/newreset密码管理/双BFFfresh/global清两。非promotionlive24hexpiry/operatorofflinecopyprint/旧码fullrecoverybrowser/productionTLS/completeUI。

实际偏差：reset后oldSSOCookie新OIDC触发AccountSessionValidationFilter在成熟请求验证/缓存前直接redirectlogin，真密码后account非BFF。两actualPGMockMvc normal/forced先red缺freshpending→filter仅canonicalGETauthorize清旧session/context后anonymous继续成熟Spring验证/原OIDCfilter创建newbrowsertransaction转绿，不抄旧savedrequest/challenge/auth_time、不缓存未验redirect，普通APIbrowserexpiry保持拒。新7=revokedsid2/credentialversion2/none/eviloldchallenge/badmaxage，reauth18、related71阶段绿/最终716；compile StringHttpMethod修，testnetCAPTCHA累积隔离fixture非产品新缺陷，browserlogin401误期望原302loginerror已校准。

最终03:46–03:51强制.20261006.2 server486starter193ref37=716零failerror skip20tasks/bootJarmetadata/localfilepub两nativePOMcompileSecurity7.1.1，284source22581.../10artifact3unchangedlocks。finalJAR重验full维护，四logfixturepasswordkeypepperToken等无命中，临时环境清理记录；无migrationdependencylock/SPIMFApermissions改变/noUIrewrite，source未commit，无生产DBremote deploy。见[English.2](../integration/development-delivery-2026-10-06-2.md)／[中文](../zh-CN/integration/development-delivery-2026-10-06-2.md)／[维护验收](../operations/administrator-maintenance-acceptance.md)。

### 3.23 2026-10-06 TLS/代理header/SecureCookie/.3

本轮真实Nginx1.29.8/privateDocker/Temurin21.0.12.1/PG18.4，两hostTLSCAleaf/持久RSA3072signkey，initial.2JAR按旧guide头覆盖但未清ForwardedPrefix probe生成HTTPevilhost/forgedcallback，精确SSO仍拒非registered不称窃Code。repodeploy/nginx完整include清alternate+canonicalserver_name/schemeport/observedIP，sameprobe正确HTTPS绿；guide public/actuator全组修。可选refhttpsprofile外issuerclientsecret/SecureHttpOnlyLax__HostPath/与logoutCookie一致，defaultlocal不变，无Javacore/SPImigrationdep锁business权限变。

final.3真实profile/repoinclude default/appchain EdgePlaywright1.55.1 twincontextALTCHA/密码login/CodePKCEJWKSUserInfo，hostonlySecureHttpOnlyLax/JS两origin不可读/browserHTTP不发，wrongCSRFOrigin拒、reauth别browser/wrong密码/真time、currentownCookie清/other保持SSO免密码return/global精确stateforged400/CAHTTPSsignedBack-channel。fixtureIP2CAPTCHAproof/3block，伪ForwardedForRealIP不同仍同observednetwork/正确plain两encoded429。appsDB不hostpublish，proxy仅loopback，finaledge仅TLSconfigkey不SSOkey/secretmount；NodeCAhostname反例、JVMtruststore无trustall，Edge单leafSPKIprocess例外/privateCA非正式browserOSCA/crosssite。启动ordering502/BusyBoxCA/Pythonready/probetimeout为fixture非product。

强制10:39–10:43 .20261006.3 486server193starter37ref716零failerror skip20tasks/twoJARmetadata/本地pubnativePOMcompileSecurity7.1.1，286source新含deploy/12artifact3locks，sourcecdd576...；八finalcontainerlogtargetedkeysecretTokenpattern无命中。真TLS证据外harness不加mirror配置mock。officialDNSpublicCArenew/CDN多级prefix/firewall/hostcontainerhardening/prodwindowcapacityscan与consumergate仍另验；无commitremote/prodDB/globaltrusthosts修改。见[English.3](../integration/development-delivery-2026-10-06-3.md)／[中文](../zh-CN/integration/development-delivery-2026-10-06-3.md)／[TLS记录](../operations/tls-proxy-acceptance.md)。

### 3.24 2026-10-06活跃构建安全扫描/依赖处置/.4

基线.3 Trivy0.75.0三锁232package/33finding（9Critical13High11Medium），解包JAR148package/32finding（9Critical13High10Medium），同17不同CVE；opaquebootJAR fs0target0package无有效覆盖，显式提取补足。官方BootBOM/Tomcat/Spring/PGdriver/BC/Log4j/Jackson版本公告核对，厂商级别/功能条件与DB严重级别分开，不虚构应用RCE/DIGEST/FORM可利用。

Boot4.1.1/Framework7.0.9、overrideTomcat11.0.26、JacksonBOM3.1.7、BC1.86、PGdriver42.7.13/Log4j2.25.5；Security/AS7.1.1和Nimbus保持，三锁实际重生成。13:41:00–13:45:20强制.4 486server193starter37reference=716零失败错误跳过20tasks，两bootJAR/metadata；实际PG/序列化/consent/rootcontextTomcat/reauth/callback/durable回归，nativePOM独立compile解析Boot4.1.1/Framework7.0.9/Security7.1.1/Log4j2.25.5。source286 fingerprint de942823.../12artifact3newlocks，旧ID/JAR保留，不用HEAD还原未提交内容。

同DB2026-10-06T01:08Z最终三锁234package/解包150package均0vuln；实际新server84+BFF63nestedlibs，package与file不同指标。Semgrep1.179.0 p/java/javascript/security-audit 221path/1warning/0error：LoginFailureResponseJitter.java45唯一生产调用LasAuthenticationFailureHandler68容器context+固定/login?error，当前调用图误报，保原报告不屏蔽；新增用户输入caller需重审。Gitleaks8.30.1 redact100 source/JARdepth2/allhistory各0match；零匹配非任意秘密/ignored外配置安全，localexample与模板/SQL/PowerShell/bytecode覆盖限制明示。最终快照287含Compose/wrappers，fingerprint范围286不含Compose。

EXE重写与并行扫描竞争为准备工具错误，核samebytes避免重写、最终inventory重生成/secret重跑。原基线与最终report/cache/inventory/buildlog私有临时保留/hash锚点双语，不上传源、不打印秘密；scanner --rm，最终Docker无runningfixture。原.3外部TLS/browser/admin/restore未用.4重验；productionimageOSJDKproxy/log/DAST/capacity/监控、发布公告refresh/最终consumer图与签名committed发行仍开，无commitremote/prodDB/consumer实施。见[English记录](security-scan-2026-10-06.md)／[中文](../zh-CN/implementation/security-scan-2026-10-06.md)／[交付.4](../integration/development-delivery-2026-10-06-4.md)／[manifest](../integration/development-delivery-2026-10-06-4.json)。

### 3.25 2026-10-06最终制品重验/容量与readiness修复/.5

.4实际依赖升级JAR重跑TLS双链/admin/PGrestore通过后，workstation liveDBstop query503但readiness200暴露实际配置缺陷：probeenabled未纳db。新增DatabaseReadinessIntegrationTest先red旧down仍200，basegroup include readinessState,db修green；完善同PG固定loopbackstop/restart，readiness200→503→200/liveness始终200/精确coarsestatus无详情。socketready初始化重启、动态Docker端口restart变更、login无savedrequest却期望account、匿名metrics自动跟随loginpage200均fixture校准，不作产品新漏洞。一次admin与重build并行bootstraptranscript断言失败，隔离repeat通过，未确立持续缺陷/泄漏；原失败证据留。

最终19:43:45–19:49:43强制.20261006.5 487server193starter37reference=717零failerror skip20tasks/twoJARmetadata/localfilepubnativePOMcompile，3locks保持.4、source287 fingerprint f23381a9.../12artifact；后完善test重build三个runtimehash不变。finalhashJAR TLS19:33–19:34 default/appchain真实NginxCAHTTPS/browserCookie/reauth/currentglobalHTTPSBack-channel/伪IPencodedbudget，admin19:52–19:55真hiddenbootstrap强改download/offline reset负例无变废旧，restore19:41–19:43真dump/restore/newkeyepoch/oldCookieCodeATRT拒/freshlogin/scheduler/global/guards全过，细层保原限制。

截至20:05:11有界exactJAR/localHTTP/PG18.4/20logicalCPU/heap1GiB/pool10min2timeout2s观察：query96/phase1,4,8workers全200，8worker60.93req/s/p95187.02ms，16worker91×200/5×429含拒60.65req/s/全部refund无bucket。login32/phase1,4,8worker全302savedaccount，8workerworkflow5.51/s、POSTp95343.44ms；单subject+CSRFprepare非OIDCmixed/SLA。hash8worker128samplep9572.13ms、31localJMXsampleCPU.2647heap438.5MiB/thread38/GC+831ms/pool10active1waiting，SQL96Session0outbox11conn0lockwait。DBstop/readiness503query503/liveness200，samefixedport恢复query/readiness200，anonymousmetrics302不跟redirect。

未新增publicmetrics/remoteJMX/生产阈值hash调参，未找到/未实施domaincountertimerGauge/privateexport/scrape/dashboard/alertdelivery；runbook目标与实际状态明确。final三锁234/JAR150包0reportedvuln，Semgrep2211warning0error、Gitleaks三路径0match，scan288含Compose/fingerprint287不含。finalruntime定向secret8TLS4admin20restore2capacity无命中；容器DBvolume/network/detachapp/keycerttrust/settingsstate清，9000/8088/9080/9443free，无globalCAhosts/commitremoteproductionDB。private报告缓存logsinventory保临时hash锚点。

见[English交付](../integration/development-delivery-2026-10-06-5.md)／[中文](../zh-CN/integration/development-delivery-2026-10-06-5.md)／[最终验收](../operations/final-artifact-acceptance-2026-10-06.md)／[容量readiness](../operations/capacity-readiness-2026-10-06.md)。下一domainmetricsalert/持续productionhardwareload、imageOSJDKproxy/logDASTfullUI、officialDNSCArenewfirewallCDN/productionbackupkey/actualconsumer15mepoch和signedcommitted发行仍独立，完整B1不自动关闭。

### 3.26 2026-10-06私有领域监控/实际告警恢复/.6

coreSsoMetrics闭合enumtag formLogin/knownadmin失败/internalauthquery/refreshoutcomecommittedreuse/新HTTPregistration/backchannel一行receipttransition，MeteredPasswordEncoder保Argon2/bcryptformatupgradeerror并计耗时，事务敏感aftercommit无rollback假事件。optionalMonitoringSnapshot webready独立daemon不依业务scheduling/nonweb不跑，singleSQL2s+poolacquire/默认15s缓存ACTIVEexpirySession/pendingdeadexpiredclaimoldest/logoutcount，fail保lastknown但available0/lastsuccess不推进，scrape不SQL。新监控profileseparate9091loopbackrequiredcost10–14独立hash/固定collectoruser/statelessGETonlynoCookieSavedRequest；ordinaryCookie/OIDCclientsecret/appport/nonGET/businessquery拒，disabled手工expose亦deny。health迁managementlistener需文档，publicproxy不转发。

Prometheusregistry1.17.1/SDK1.7.0加7serverruntime锁row，Bootsecuritybaseline和starter/BFF锁/API无变。repo5monitorinputs：15scollector/7rule、promtooltest、privatebearerAMexample、8panel10queryGrafana。真实17new=config6/commitencoder3/disabled1/dualportHTTPPG7，原refresh/receipt加counter与DB一致断言；最终21:42:33–21:48:35 504server193starter37ref=734零failerror skip20tasks/actualPG/twoJARmetadata/localfilepubnativePOMcompile。source302fingerprint75864917.../18artifact3锁1change，serverJAR ee73250.../BFF b151301.../starterhash保，旧ID保。

finalexactJARmonitor22:01:03–22:06:30私有network/PG18.4/Prometheus3.6.0/AM0.28.1/Nodebearerwebhook；localephemeral身份非productionkey，采集5s/eval2s/snapshot1s/notify1s2s为fixture但repoexpression/for1m2m不变。collector独立authportmethodSession/真loginhashquerySession/poolJVMseries/10panelquery过；fixture6moldoutbox满1mbacklogfiring/processedresolved，DBstopreadiness503/liveness200/snapshotunavailable满1m→DBrecoverresolved，collectorfile错满2mscrapedown→correctresolved。6actualwebhook通知只alertnamejobinstanceseverity。早appporthealthtimeout和2sinterval5stimeout无效为工具校准保失败，不作product漏洞；Grafana非actualimport/render，剩余4rule非各真livefiring。

finalTLS双链22:11–22:12真实Nginx/CA/Edge/最终JAR loginreauthCookie/currentglobalHTTPSBack-channel/伪forwardIPplainencodedbudget过，原.5adminrestore未重跑。scan241lock157JAR0reportedvuln，Semgrep232path1redirect人工误报0error，用既定sameSHA256rulescache补remoteTLS EOF、source不上送，Gitleaks三路径0match。5monitor8TLSfinalcontainerlog定向secret无命中；专用containerDBvolume/network/credentialhashsettings/ports清，private报告缓存证据保，runtimehash f3c72c...。无commitpush/productionDBmonitordeploy/consumerMFAroles/globaltrusthosts变。

来源[English监控](../operations/monitoring-and-alerting.md)／[中文](../zh-CN/operations/monitoring-and-alerting.md)、[.6交付](../integration/development-delivery-2026-10-06-6.md)／[manifest](../integration/development-delivery-2026-10-06-6.json)。下一actualGrafana/privateTLScredentialrotationthrottle/真人oncallescalation、remainingregistrationrejectrecoverytokenissuecleanup/diskbackupclock、multiuserOIDCrefreshlogoutsoak/largeDBpoll成本。不得将subset当全部runbook指标或productionmonitorready；原B1/actualconsumer/签名发行与正式deploy独立。

### 3.27 2026-10-07Grafana真实导入/混合负载/refreshlogout hint修复/.7

从.6finalJAR拓扑推进实际Grafana12.2.0APIimportexactdashboard/privatePromeDatasource/randomexternaladminprovision/Viewer。1440×1000和390×844八headingpanel有真数据/canvaspixel/noquerypageerror/横overflow，ViewerAPIcanEditSavefalse、saveoverwrite/datasourcecreate403。早mobileheadingabsent是虚拟panel未lazy挂载，实际滚动probe修，不改repo面板。

.6shortAT45s/bffbefore10s actualrefresh后global400，单worker最小复现hint尚未expired/currenttoken查不到。PGMockMvc新增originalIdTokenHintStillLogsOutAfterRealRefreshReplacesTheStoredIdToken先redredirect断言；原Springprovider只currentlookup而BFF OidcUser保初始ID。按ADR-0011 V15 oauth2_id_token_hint_history HMAC-SHA256/keypepper namespace/raw不存/authorizationFKcascadeindex，save同事务先保previouscurrent再delegate/newhint，upgradecurrent首次save保但已丢更早hint不可重建。仅显式idtokenlookupfallback→仍存在auth，ATRTcodegeneral不扩/成熟clientaudprincipalsessionreturn继续核/authdelete清history不复活。回归green加preupgrade两32bytehash/未登记return400auth不改/ATRT不查/cascade重复hint400。编译import/type修为开发错误；依赖锁/因素permissionauth_time/starterAPI不改。

最终00:07:51–00:12:17 .20261006.7全量505server193starter37ref=735零failerror skip20tasks/actualPGV15/twoJARmetadata/localpub/nativePOMcompile；source303 ee911dc4.../18artifact3unchangedlocks。实际finalhash00:36:27–00:48:02 Grafana+两chainclosedloop800ms5subject9browser，每mode301.154/302.178s，12,598operation含48passwordOIDC/24current/24SSOreentry/16global，Prome120successrefresh0reuse40successBack-channel/DB40processed0finalpendingdeadbucket/sentinel一直保持。一次.7postrevocationinflightrefresh401SESSION_EXPIRED是协议允许终态，probe仅exit后允/正常200，不新repair或假计错误；早attempt单worker不计十分钟通过。

default/appowned protectedp9521.91/25.55ms、query65.96/71.67ms、global到twin失效9819.47/9021.69ms含redirectpollschedule非独立deliverySLA。89sampleSSOmem406.9–926.2MiB(first406.9last637.4)BFF309.3–402.8(first309.3last393.6)/DockerCPU85/40.35/PID96/73/lockwaiter0DBconn≤11；1RuntimeErrorsamplinggap保，虽mode间restartBFF也发生但未精确归因。短sample含native/warmup，不能排leak/饱和headroom/largeDBhistory成本。fixtureAT45s不改production15m。

finalscan241lock157JAR0reportedvuln/Semgrep2331redirect误报0error/Gitleaks三0match，304scan另Compose/source303，旧.6JAR保。targetedcontainerprocesslogs无fixturesecretTokenpattern，GrafanaPromeappBFFproxyDBcontainerDBvolume/network/accountadmincollector/keycerttrustsettings清、9443/9080/9092/3300free，无globalCAhosts/commitpush/prodDBdeploy。private runtimeef41bee4.../report/screenshots临时保。原.6alert与.5adminrestore未.7重跑。

详[hintEnglish](refresh-logout-hint-history.md)／[中文](../zh-CN/implementation/refresh-logout-hint-history.md)、[Grafana/mixed](../operations/grafana-mixed-load-2026-10-07.md)／[交付.7](../integration/development-delivery-2026-10-06-7.md)。下一productionGrafanaauthretentionbackupupdates/privateTLSrotationoncall、小时級multiuserrampsoak/真实hardwareDBvolume/metricsoverhead/historycleanup，以及remainingprobeUI/officialdomainCDNfirewall/backupkey/actualconsumer15mepoch与formalrelease，完整B1不自动关闭。

## 4. 问题清单

编号沿用此次审查，后续修复与测试引用同一编号。P1 应优先修复；P2 为重要安全/可靠性问题；P3 为较低优先级的一致性/体验问题。严重性不等于任意攻击者可以无条件利用。

**分类与状态：**A1/A2直连Servlet、S2扩大DBwriterrollback已验，A3/S1/S3–S6/B2–B5独立受控，B1整体部分；D1指定补丁兼容保持。03/06本地开发交付与04epoch受控有证据，原编号保留，无production/fullscan/liveconsumer关闭。

### 4.0 待修改矩阵

下表在原审查路径上更新已实现修复和“应变成什么”；历史复现边界保留在后续原编号表中，静态检查与新执行回归分别记载。

| 编号／级别 | 当前状况 | 预期状况 | 状态 |
|---|---|---|---|
| A1 / P1 | 既有PathPattern/第一方role，root/context真实HTTP合法/拒绝/cookie隔离/歧义矩阵通过，§3.14 | 保留专用Basic/MVC一致，补productionproxy/TLS/browser及容量/完整查询契约 | 已修复／直连Servlet TCP已验证 |
| A2 / P1 | 既有POST/解码path，真实HTTP3loginpath CSRF/password/预算/CAPTCHA单次与两context通过，§3.14 | 保留风险先于认证/CSRF与一次proof，补proxyTLSbrowser/生产thresholdwindow/并发容量 | 已修复／直连Servlet TCP已验证 |
| A3 / P2 | 独立hash前budget原子占用/successrefund、10/20/15m，25DB/MockMvc含并发/SQL故障通过 | 真实Servlet/proxy/容量/metrics/恢复吞吐验收，保留正常success无累计 | 已修复/hash前预算与DB受控并发已验证 |
| S1 / P1 | 普通/强制改密同事务消费恢复会话，恢复先锁用户再核验；晋升同时撤销人工凭据，28 项真实 PostgreSQL 回归通过 | 保持原子失效与双向提交语义；补真实浏览器/运维及生产边界证据 | 已修复／数据库事务与受控并发已验证 |
| S2 / P1 | 既有metadata锁，23新双向/连续Secret/auth与SQL故障rollback通过，§3.15 | 保留owningwriter单Secret原子性；liveclientrollout/新授权签发race/capacitycrash独立 | 已修复／扩大PostgreSQL/MockMvc竞争回滚已验证 |
| S3 / P2 | user→request/两phaseunion稳定UUIDbatch、busy后续retry、ownerstatusdue/rollback，24DBrace绿 | 真实容量/锁饥饿/多实例/crash与全系统图另验，保留batch原子取舍 | 已修复/注销锁序和受控DB竞争已验证 |
| S4 / P2 | user→request锁后门禁，PENDING/HELD拒绝；25数据库+3命令fixture和90专项通过 | 保留无变更拒绝/所有者取消后重试，补真实终端退出与浏览器/部署验收 | 已修复／数据库与命令fixture已验证 |
| S5 / P2 | LEFT JOIN保留墓碑，凭据明确null/模板无凭据不适用；18回归/138专项/273全量server通过 | 保留终态只读与写拒绝，补真实浏览器/容量；未知身份仍404 | 已修复／PostgreSQL/MockMvc/模板已验证 |
| S6 / P3 | 两入口16随机字符80bit，旧HMAC/规范化兼容；9JVM/33DBMockMvc/164相关绿 | 新码80bit/旧码仍60bit且原状态期限，不批量复活/续期；补真实browser/issuer升级验收 | 已修复／生成预算与旧摘要生命周期已验证 |
| B1 / P1 | 核心193回归/默认B2marker，.4参考committed加密task/lease/真实JVM重启SSO撤销及旧RT拒已验 | presubmitcrash/持久SessionTokenindexreplay/custom时序/外部consumer与生产多实例 | 参考提交后持久恢复已验／整体部分 |
| B2 / P2 | 原success wrapper及.3默认sid marker，同短锁register/invalidate，early/duringHTTPexchange signedlogout及registry并发过；真实BFF回归 | customadapter/durable/restartmultiinstance/在途business/容量墙钟验收，非跨存储原子 | 默认运行JVM迟到登记保护已修已验／持久与其他时序独立 |
| B3 / P2 | Back-channel 仅 200/204、撤销仅 200；禁跳转、HTTP 类别、八次重试/服务端死信，20 + 18 新回归通过 | 保留正确确认，补真实 TLS/代理/消费及可靠持久接受验收 | 已修复／DB/HTTP 发送与重试已验证 |
| B4 / P2 | 严格 decoder/claim 与 process receipt、失败索引保留/并发可重试已修；33/15/5项与全量117绿 | 旧 adapter 升级、持久事务/崩溃/真实消费/容量与跨实例独立验收 | 已修复／签名与默认执行/重放已验证 |
| B5 / P3 | 校验本地目标/state+Session+10分钟/单次，默认采用/自有显式；45target+28callback绿 | 保留安全导航，补真实容器/消费/shared store与版本验收 | 已修复/受控签名返回与事务已验 |
| 依赖 D1 | Security/AS7.1.1三锁/打包对齐，5renderer先红后绿、4DBconsent，独立两metadata解析通过 | 保留指定补丁/兼容，补完整扫描、browser/live部署与消费组合 | 指定CVE依赖已修／受控兼容已验证 |

### 4.1 入口安全（3 项）

| 编号 | 优先级 | 问题与影响 | 证据 | 当前状态 |
|---|---|---|---|---|
| A1 | P1 | 原URI路径导致编码内部门禁不一致 | 既有PathPattern/第一方role后，§3.14新增actualTomcat TCP/JDBC根/context/3literal编码，合法knownunknown、匿名Cookie错client/歧义/会话隔离通过；96新/181相关/419全量server | 已修复／直连Servlet已验，生产proxy/TLS/browser/容量独立 |
| A2 | P1 | 原URI/login匹配可跳编码路径IP风控 | 既有POST/UTF8decode后，§3.14actualTCP3path同CSRF/计数/CAPTCHA单次/封禁、proof顺序/rootcontext通过；无新生产偏差，仅fixture校准 | 已修复／直连Servlet已验，生产proxyTLSbrowser/阈值窗口并发容量独立 |
| A3 | P2 | 原内部Basic无hash前client/network限流 | 历史25错401、原第11回归红；ADR-0005用户批准独立原子failure/inflight、25新+32相关和203/176强制通过，见§3.8 | 已修复/DB哈希前gate已验，真实容量/部署/代理未验 |

源码入口：[第一方认证过滤器](../../las-sso-server/src/main/java/org/las/sso/security/FirstPartyClientAuthenticationFilter.java)、[登录风险过滤器](../../las-sso-server/src/main/java/org/las/sso/security/LoginRiskAuthenticationFilter.java)、[安全链](../../las-sso-server/src/main/java/org/las/sso/config/AuthorizationServerConfiguration.java)。

### 4.2 Standards：生命周期、并发与安全规范（6 项）

| 编号 | 优先级 | 问题与影响 | 证据 | 当前状态 |
|---|---|---|---|---|
| S1 | P1 | 正常改密/管理员晋升路径的恢复状态失效不一致 | 2026-10-03 初查确认普通改密缺失，三项先跑两项失败；此后改密同事务消费、恢复用户行锁/锁后核验及晋升人工凭据撤销已落地。28 项新 PostgreSQL 回归及全量 158 + 37 项零失败/跳过，详见 §3.3 | 已修复；数据库事务/受控并发已验证，浏览器/运维/生产待验收 |
| S2 | P1 | 原陈旧RegisteredClient编辑可能恢复轮换前Secret | 原metadata锁后2026-10-05扩大23DBwait/双向/SQLfault/幂等/BasicOAutherror、91专项及共同460/178全量绿，见§3.15 | 已修复／owningwriter竞争rollback已验，livegrantmutationrollout/capacitycrash另验 |
| S3 | P2 | 原user/request锁倒置可能循环等待/回滚 | 原3DBprobe先红，ADR-0006用户确认，user-first/phaseunion/statusowner重检、24race/40专项及227/176强制通过，§3.9 | 已修复/受控DB锁序已验，非全系统无deadlock/生产capacity验收 |
| S4 | P2 | 原晋升ACTIVE但PENDING/HELD用户后，强制改密与注销门禁相互阻塞 | 两条PostgreSQL回归先红；ADR-0002门禁修复，25数据库+3命令fixture/90专项及强制255/176零失败/错误/跳过，见§3.10 | 已修复／受控数据库与命令fixture已验证，真实浏览器/终端/生产待验收 |
| S5 | P2 | 原详情INNER JOIN已删除密码行，墓碑详情/自动选中页面误404 | 实际删除后三回归先红，LEFT JOIN+模板无值修复，18数据库/MockMvc/模板、138相关和强制273/176零失败/错误/跳过，见§3.11 | 已修复／受控数据库与MVC/模板已验证，真实浏览器/部署待验 |
| S6 | P3 | 原12个32字符随机位60bit低于80bit规范 | 两生成预算回归先红，四组16×5修复；9JVM/33独立旧摘要fixture数据库MockMvc、164相关和314/176强制零失败/错误/跳过，见§3.12 | 已修复／新熵预算/旧兼容生命周期已验，非随机源认证或生产browser验收 |

源码入口：[改密](../../las-sso-server/src/main/java/org/las/sso/account/PasswordChangeService.java)、[恢复](../../las-sso-server/src/main/java/org/las/sso/account/PasswordRecoveryService.java)、[管理员初始化](../../las-sso-server/src/main/java/org/las/sso/maintenance/AdminBootstrapService.java)、[客户端管理](../../las-sso-server/src/main/java/org/las/sso/admin/AdminClientService.java)、[注销](../../las-sso-server/src/main/java/org/las/sso/account/DeletionRequestService.java)、[注销管理](../../las-sso-server/src/main/java/org/las/sso/admin/AdminDeletionRequestService.java)、[维护任务](../../las-sso-server/src/main/java/org/las/sso/maintenance/DeletionExecutionJob.java)、[用户管理](../../las-sso-server/src/main/java/org/las/sso/admin/AdminUserManagementService.java)、[管理页面](../../las-sso-server/src/main/java/org/las/sso/admin/AdminUiController.java)、[恢复码](../../las-sso-server/src/main/java/org/las/sso/account/RecoveryCodeService.java)。

规范依据：[安全与恢复](../architecture/security-and-recovery.md)。本轴共 6 项，最高优先级为 S1/S2。

#### S4 产品规则确认 — 2026-10-02

- 用户已明确同意：存在 `PENDING`/`HELD` 注销申请的账号不得晋升 SSO 管理员。用户先通过已有流程取消注销，主机操作员再重试提升；不由提升操作自动取消申请或释放暂缓。
- 拒绝时不修改管理员身份、密码、注销申请或既有安全状态，命令提示先取消注销并返回失败。
- 提升事务内、身份/凭据变更前重新核验，统一注销申请/取消/执行的并发锁序。申请先提交则提升拒绝；提升先提交则后续申请按管理员禁止注销规则拒绝。
- 决策见 [ADR-0002](../adr/0002-require-deletion-cancellation-before-admin-promotion.md)，已同步安全/数据/API契约及测试计划。2026-10-02仅确认产品规则、不自动加入五项P1；S3后继续实施S4，2026-10-04数据库与命令fixture结果见§3.10，真实交互/生产仍待验收。

### 4.3 Spec：BFF 与 OIDC 契约（5 项）

| 编号 | 优先级 | 问题与影响 | 证据 | 当前状态 |
|---|---|---|---|---|
| B1 | P1 | AuthorizedClient 保存/刷新/退出所有权不一致，可能覆盖 Token 或跨会话误撤销 | 原LAS repository/本地失效证据保持；.2默认与自有BFF真实同主体双browser当前退出隔离/全局退出Back-channel验收，§3.18 | 本地浏览器与identified交付已验；durable/通知时序/外部消费重启多实例待验 |
| B2 | P2 | 原 sid 登记在登录 Filter 后，成功回调跳转不继续链，回调返回后可能漏接退出 | 2026-10-03 签名回调→立即签名退出单项先失败；成功 Handler 登记后通过，14 项协议 fixture 及全量 158/53 通过，详见 §3.4 | 原窗口已修/受控已验；通知先于登记/容器/持久协调未验收 |
| B3 | P2 | 原投递忽略状态，302 误记成功，starter 同类 | 两条原单项 23:55 先红后绿；显式确认/禁跳转/安全错误，20 DB/HTTP + 18 queue 和全量 178/71 零失败/跳过，详见 §3.5 | 已修复/受控发送重试已验，部署/持久队列未验收 |
| B4 | P2 | 原缺严格 iat/寿命/event/sid 与执行回执，失败可假报/重传重执行 | 18先红13失败，ADR-0003用户确认；strict Token/process/失效顺序修复，33/15/5与强制178/117通过，见§3.6 | 已修复/受控签名回执已验，持久/部署与旧 adapter 迁移待验收 |
| B5 | P3 | 原login丢returnTo/固定首页 | 两签名回归先红，ADR-0004用户确认显式自有接入；45target/28callback/83专项和全量178/176绿，见§3.7 | 已修复/受控返回已验，真实消费/共享/多实例未验 |

源码入口：[Starter 自动配置](../../las-sso-server/las-sso-spring-boot-starter/src/main/java/org/las/sso/client/LasSsoClientAutoConfiguration.java)、[刷新合并](../../las-sso-server/las-sso-spring-boot-starter/src/main/java/org/las/sso/client/SingleFlightLasSsoAuthorizedClientRefresher.java)、[Back-channel 接收](../../las-sso-server/las-sso-spring-boot-starter/src/main/java/org/las/sso/client/LasSsoBackChannelLogoutController.java)、[服务端投递](../../las-sso-server/src/main/java/org/las/sso/logout/BackChannelLogoutDelivery.java)、[撤销队列](../../las-sso-server/las-sso-spring-boot-starter/src/main/java/org/las/sso/client/LasSsoTokenRevocationQueue.java)、[登录入口](../../las-sso-server/las-sso-spring-boot-starter/src/main/java/org/las/sso/client/LasSsoSessionController.java)。

规范依据：[OIDC与BFF契约](../architecture/oidc-and-bff-contract.md)。B1运行期/B2原窗口/B3发送/B4默认回执/B5安全返回有独立证据；生产/持久/共享竞争开放。

### 4.4 独立依赖告警（不计入上述 14 项）

| 编号 | 告警 | 证据与边界 | 状态 |
|---|---|---|---|
| D1 | CVE-2026-47877：默认同意页面XSS | 原锁7.1.0/早期应用探针未复现历史保留；2026-10-04重核[官方公告](https://spring.io/security/cve-2026-47877/)，三模块Security/AS7.1.1，5依赖renderer红绿/4配置DBconsent、110server/131starter及323/176全量、两bootJar与filemetadata消费，见§3.13 | 指定依赖已修／受控兼容验证，完整扫描/真实浏览器/生产待验收 |

不能以早期失败注入探针宣称依赖安全；新增renderer证明的是fixture层依赖rawHTML，不把不经过LAS验证的值写成生产已利用。D1按官方修复版本处置，不把当前未启用的DPoP/WebAuthn/CBC公告直接当项目已利用漏洞；完整扫描仍待进行。

## 5. 已确认的下一轮计划：五项 P1

### 5.1 范围与顺序

1. A1：内部查询的第一方认证与授权边界。
2. A2：编码登录路径的 IP/CAPTCHA 风控匹配。
3. S1：凭据变更后的恢复会话失效。
4. S2：客户端配置与 Secret 轮换的并发一致性。
5. B1：BFF Token 与刷新/退出的会话所有权一致性。

每项采用“回归测试先确认旧行为失败 → 最小修复 → 专项测试通过 → 全量回归”的流程。先做纵向安全闭环，不一次写完五项实现再补测试。若测试否定静态结论，先修订问题证据，不制造失败测试以迎合报告。

### 5.2 修复方向与验收条件

| 任务 | 拟修复方向 | 必须补充的验收 |
|---|---|---|
| A1 | 使用与 MVC 一致的规范化 RequestMatcher；内部路由在授权层明确要求第一方服务身份，不能只依靠过滤器执行 | 普通 Cookie 用户、匿名、无/错 Basic、非第一方、禁用客户端均不能查询；正常和编码路径结果遵守同一门禁；合法第一方仍可查询已知/未知主体，保持 no-store 与最小响应 |
| A2 | 登录风控与真正的登录处理器使用一致的规范化路径/方法匹配 | 原始/编码登录路径在 IP 封禁时均拒绝；需 CAPTCHA 时无效/缺失证明均不能登录；合法证明可以一次使用；正常登录、CSRF 和账号失败锁不退化 |
| S1 | 在安全凭据更新的同一事务中废弃旧恢复会话；明确恢复会话与凭据版本的绑定或等效原子失效策略，避免并发完成恢复逃过失效 | 普通改密、强制改密、管理员晋升后旧令牌均拒绝且当前密码不变；合法恢复仍一次性、限时可用；覆盖改密/晋升与完成恢复的并发交错；离线管理员 reset 的已有保护不退化 |
| S2 | 对同一客户端的编辑/轮换统一事务锁或版本控制，避免整个陈旧对象回写 Secret；具体锁/字段更新方案以现有 Repository 行为和并发用例决定 | PostgreSQL 下受控交错“编辑读旧值 → 轮换 → 编辑完成”不能恢复旧 Secret；新 Secret 可用、旧 Secret 失效；客户端配置版本、授权撤销、幂等与审计仍一致 |
| B1 | 登录保存、加载、刷新、撤销统一按本地业务会话拥有 Token；明确 repository 和刷新锁使用同一所有权键，保持自定义配置兼容 | 同账号两个独立 Session 的 Token 互不覆盖；同 Session 并发只刷新一次，不同 Session 各刷自己的 RT；A 当前应用退出不撤销 B；freshness 不能借另一会话 Token 延长；绝对期限与暂时故障处理不退化 |

补充边界：

- A1/A2已在§3.14补真实Servlet TCP根/context矩阵；productionproxy行为另列，browserTLScookie/生产窗口/完整URI集合未运行不勾完成。
- S1 如需要新增版本绑定字段，先确认迁移方案及已有恢复会话处置；没有既定迁移落地，不预先写成“迁移已完成”。
- S2 使用可重复的数据库并发测试，不依赖 sleep 碰运气，也不以纯 Mock 保存顺序代替实际事务验证。
- B1 首先消除单 JVM 的会话所有权错误；完整跨实例持久化、会话索引/replay SPI、B2 等不自动纳入本轮。
- 当前应用退出与全局退出语义必须保持区别；不能用“所有同名用户会话全部注销”掩盖 B1。

### 5.3 本轮完成门槛

- [ ] A1、A2、S1、S2、B1 对应回归用例先在旧代码上失败，确认触及真实缺陷。
- [ ] 五项修复分别通过专项回归，保存实际测试命令、计数、失败/跳过情况和验证层级。
- [x] 服务端与Starter全量测试通过，参考BFF至少编译；最新.2强制461+178零失败错误跳过18task/bootJar/metadata，默认自有BFFbrowser独立验收，BFF GradleNO-SOURCE，460+178等保留历史。
- [ ] 对并发、多会话与真实 HTTP 层不足之处单独标记，不用已有 135 项测试数字替代新结果。
- [ ] 保留现有资格查询/版本与 Starter 发布改动，不覆盖无关工作区内容。
- [ ] 确认无 Token/密码/Secret/恢复码明文进入日志和文档。
- [ ] 更新相关实现说明及本文状态，记录每个问题关闭所依赖的证据。

五项 P1 完成只关闭本轮任务，**不等于生产上线门禁全部通过**。本计划不包含自动提交、推送、发布制品、生产数据库操作或部署。

## 6. 后续规划（S3–S6/D1/A3/SSO-01/B2–B5 已推进，其他范围分别确认）

2026-10-07整理：本节阶段表及旧五项P1顺序保历史背景，当前从`.7`到正式生产的工作按[新计划P01–P19](../zh-CN/implementation/production-launch-plan.md)／[English](production-launch-plan.md)推进。下一优先P02生产启动门禁盘点/测试，P03可靠撤销恢复、P04核心UI与人工流程；P01并行收正式环境和消费方输入，不重做已修P1。

| 阶段 | 候选工作 | 前置条件与验收方向 |
|---|---|---|
| 安全/可靠性补齐 | .5finalTLS双链/admin/restore、activebuildscan/readiness已验；下一productionimage/log/DAST/UI/S2liveclient | 保留历史及finalhash证据，domainmetricsalerts/持续load/durableordering/实际consumer正式发行另验 |
| 规范与体验 | S6真实生成/保存/恢复与B5真实返回验收 | 新80bit/旧60bit兼容已验，确认全部issuer更新；保留一次展示/目标绑定，验真实context/浏览器 |
| 资格查询契约 | SSO-04七字段/epoch/lifecycle/小load及隔离真实restore已验；productionconsumer/吞吐待验 | 只能同epoch排序counter，实际consumercache/inflight归business，harness屏障不替实现，不以本机吞吐承诺SLA |
| 资格事件（后续） | `SERVICE_ACCESS_CHANGED` 独立事件：同事务 outbox、签名/HTTPS、eventId、版本、重试/死信 | 用户已确认后续补；当前保留查询，不把自定义状态塞进标准 Logout Token |
| 初期 Starter 扩展 | SSO-01/02 公开安全链、会话 Token/锁/退出、可靠撤销提交/replay 替换、安全 returnTo；SSO-03 resolver/claim 支持 | 用自有参考 BFF/测试 adapter 验默认/自定义方式；其他项目的会话表、密码门禁和重启策略由各自会话实现 |
| 开发客户端登记 | SSO-05 独立 client、精确回调/退出地址、scope/audience、query eligibility、Secret 方法 | 外部提供经核验地址后登记；SSO 不擅自改变外部源/代理 |
| 后续 BFF/SDK 完整化 | 可选重启保持登录、持久 Token/索引/replay、跨实例刷新、安全链 API、profile 同步、TTL/契约版本、其他框架适配 | 不再把跨重启保留业务登录作为 LASLOG 初期目标；持久撤销作业单独处理，多实例部署需另行验证 |
| 制品与发布 | 当前0.1.0-dev.20261006.5 finalhash/source287/三锁/717全量/两metadata消费及TLSadminrestore，旧ID历史；committedsource/签名/托管immutable/retention待验 | 新内容用新ID，显式repo不默默MavenLocal，未授权不远程release |
| 上线验收 | TLS/固定 issuer/持久 key/Secret、响应头、Argon2 压测、日志扫描、备份恢复与 epoch/key 演练、告警、浏览器 E2E/可访问性 | 按[实施准备度](implementation-readiness.md)和[测试计划](test-plan.md)逐项给出证据；未实施/未验证的控制不能宣称上线就绪 |

其他项目的账号冻结收敛、业务删除及游戏连接处理由各自会话安排。本 SSO 计划只交身份、资格查询、退出、SDK 与配置契约；消费方回传作为外部联合验收输入，不由本会话管理实现。

### 6.1 推荐执行顺序与依赖

1. **先修已批准的五项 P1：A1 → A2 → S1 → S2 → B1。**保留逐项回归与真实 PostgreSQL/HTTP 证据。A1 是资格查询可信接入前置，B1 是多浏览器及近期认证证据隔离前置。
2. **补安全/可靠性及生产边界。**A1/A2直连/S2DBwriterrollback及.2真实SSOrefBFF双browser/reauth/退出已验；继续productionproxyTLS/fullscan/maintenancecapacity，livegrantmutationrace/B2ordering独立。
3. **消费开发交付组合。**SSO-03公共trusted参数与verifiedToken访问/SSO-06identifiedlocaltuple已交，RPpendingSession/15mexpiryrevoke/highriskwrite仍external；完整B1/live消费/committedversion未关闭，不重做密码页V14。
4. **联验生产查询恢复与容量。**SSO-04epoch/lifecyclewire/小load及.20261006.1隔离真实backuprestore有证据；生产备份RPO/key保管/实际consumer代次屏障和productionSLA仍待验。externalURI就绪做SSO-05，不把server打包进starter，不等待托管做开发。
5. **SSO 自有验收与发布准备。**Reference BFF 默认/自有链、双浏览器及退出/重认证协议黑盒；依赖扫描、真实代理/UI、TLS/Secret/备份/epoch/key 演练。其他业务实现只接收回传证据，不在本计划排期。
6. **后续补独立资格事件与可选多实例 SDK。**事件设计与接收方另行确认；MFA/邮箱不作为本轮依赖，业务登录持久化由业务方决策。

以上是任务排序建议，不自动批准新增实现。进入 LASLOG 联调不代表 SSO 已允许上线；漏洞关闭仍须对应回归证据。

## 7. 明确延期与非目标

- 第一版仍为用户名 + 密码，不新增邮箱收集、邮箱验证、MFA/Passkey。
- 独立 `SERVICE_ACCESS_CHANGED` 已明确延期；LASLOG 初期接受业务进程重启后重新走 SSO，可选跨重启保持登录及多实例能力留后续。SSO 自身的 JDBC 持久会话不因此撤销。
- 任意头像 URL、媒体上传、多人审批、KMS/HSM 等增强不在本轮 P1 范围。
- `SAS-Test` 是历史协议演示，不作为生产骨架或已验收生产依赖。
- SSO 不实现业务角色、Minecraft 绑定/白名单、项目权限或服务器封禁。
- 新增独立状态事件、改造 LAS LOG、生产多实例部署和远程制品发布均须另行确认。

## 8. 后续更新方式

本文作为截至 2026-10-02 的跟踪起点；后续在同一问题编号下更新，保留审查发现时间与历史验证边界，不抹去“当时未复现/未验收”的记录。

每项完成时至少填写：

| 字段 | 要求 |
|---|---|
| 问题编号/状态 | 例如 S1：待修复 → 已修复待验收 → 已验证 |
| 变更范围 | 涉及实现、测试、迁移、说明；未提交时明确标注，不虚构 commit |
| 验证证据 | 时间、命令、实际测试数、失败/错误/跳过数，以及 MockMvc/真实 HTTP/浏览器/数据库等层级 |
| 剩余限制 | 未覆盖的代理、多实例、压力或生产配置，保留到后续验收 |
| 关联决策 | 若改变产品/协议边界，先更新权威契约，再更新状态记录 |

### 8.1 更新记录

| 日期/阶段 | 已记录的变化 | 未因此完成的事项 |
|---|---|---|
| 2026-10-01 至 2026-10-02 初次审查 | 14 项代码问题、独立依赖告警、原 115 + 20 项测试和专项诊断；批准下一轮五项 P1 | 原问题未关闭 |
| 2026-10-02 密码重新认证交付 | V14、OIDC 密码挑战、固定 `auth_time`、服务端 126 项零失败/跳过 | LASLOG 接入、P1 修复、真实浏览器/部署未完成 |
| 2026-10-02 A1/A2 修复 | A1 规范化内部路径/显式第一方角色；A2 解码路径风控/编码登录回归；完整服务端 128 项，A1 ServiceAccess 6 项及 A2 四项登录风控专项通过 | A1/A2 仍需真实 Servlet/代理验收；S1/S2/B1 等问题未关闭 |
| 2026-10-02 S1/S2 修复 | S1 晋升同事务消费旧 recovery_sessions；S2 mutation 行锁/Secret 交错回归；相关专项与完整服务端 128 项通过 | S1/S2 仍需并发扩大/真实运行边界验收；B1 公开契约/双会话验收未完成 |
| 2026-10-02 B1 公开边界 | 新增 `LasSsoAuthorizedClientRepository`、`LasSsoLogoutReplayStore`、`LasSsoRevocationTaskSink`；默认 HttpSession/内存实现，Replay 冲突/过期、双 Session Token 隔离、四类自定义 Bean 替换、实际 Controller/Logout Handler adapter 调用及不同 Session 并发刷新测试通过；starter 27 项通过 | durable adapter、双浏览器真实流、重启/多实例仍未验证；B1 不关闭 |
| 2026-10-02 S1 修复 | 管理员晋升同事务消费旧 recovery_sessions；专项 AdminRecoveryCredentialServiceTest 与相关恢复/注销测试、完整服务端 128 项通过 | S1 仍需并发扩大验收；S2/B1 等问题未关闭 |
| 2026-10-02 本轮设计与整理 | 十五分钟/会话隔离、S4 前置取消、QQ 联系、事件延期、业务重启重新走 SSO；核对当前源码/报告和官方依赖公告 | 未新增业务代码修复、未重跑 Gradle、未提交/部署 |
| 2026-10-02 SSO 专属职责与状态复核 | 用户将本会话范围限定为 SSO；新增能力/需求/缺陷的当前→预期矩阵，计划仅管理服务端/starter/参考 BFF | 14 项及依赖告警仍未关闭；未重跑全部漏洞诊断或测试 |
| 2026-10-03 B1 运行期组合 | 整链退出 repository 统一，本地失效先于 sink；10 项安全链/loopback HTTP 与强化順序测试，starter 37 项通过；只加测试 Jackson 运行时锁 | 认证/Servlet/sink 为 fixture；非浏览器、真实 SSO/容器、DB/持久提交、重启、多实例；当时 S1 普通改密未修，随后补完见下一行 |
| 2026-10-03 S1 原子失效补完 | 普通/强制改密消费恢复会话；恢复用户先锁/锁后核验，晋升撤销人工凭据；28 项 PostgreSQL 双向竞争/回滚/到期/审计；专项 87 项，全量强制重跑服务端 158 + starter 37 项无失败/跳过，BFF 重新编译 | 不等于浏览器/代理、交互维护/容量/多实例/生产验收；S3/S4/S6 和 B1 整体交付等仍开放 |
| 2026-10-03 SSO-01/B2 | 公共 configurer/成功回调/换号销毁/error 接点，参考 BFF 可选 profile；14 项签名 Code/JWKS/立即退出 fixture + 2 error 回归；全量强制重跑 158 + 53 项无失败/跳过 | 入站 Servlet/监听/接收调度为 fixture；通知先于登记、MVC/真实容器/SSO/浏览器、持久接受/重启/版本/B3–B5 未完成 |
| 2026-10-03 至 2026-10-04 B3 | 明确两发送成功集合/禁跳转/安全状态日志；20 项 DB/HTTP + 18 queue；相关 73/20，全量强制重跑 178/71 无失败/跳过 | 真实 TLS/代理/OP/BFF、慢响应/容量/claim 竞争/重启/多实例未验；starter 耗尽非持久死信；B4/B5 仍开放 |
| 2026-10-04 B4 | strict Token/raw numeric sub、用户确认增量 process/legacy503；失败/partial/并发/缓存回执；33+15+5与全量178/117无失败跳过 | 单 JVM，不是持久/跨存储exactly-once；旧adapter须升级；真实MVC/消费/多实例/容量/通知登记时序未验，B5等开放 |
| 2026-10-04 B5 | ADR-0004显式自有返回、URI/state/TTL/单次，公共target/final handler、样例link；45target/28callback、83专项、强制178/176无失败跳过 | 入站/dispatch/ID仍fixture；sharedSession/MVC/browser/SSO/proxy/UI/persistence未验，旧pending须新登录；A3/S3/S4/S5/S6/D1开放 |
| 2026-10-04 A3 | ADR-0005内部独立failure/inflight budget/成功refund；25新含25并发10hash/15拒、SQLrollback/Clock/input；32专项、强制203/176零失败跳过 | liveServlet/TLS/proxyaddress/容量/metrics/多实例未验、非success总吞吐cap；S3–S6/D1与完整SSO-04待验 |
| 2026-10-04 S3 | ADR-0006保留batch原子，user-first/两phaseunion预锁/statusdue重检，24DBrace/40专项，强制227/176零失败跳过 | 非所有锁无deadlock，liveHTTP/容量/饥饿/crash/multi-instance未验，S4–S6/D1/完整B1/消费开放 |
| 2026-10-04 S4 | ADR-0002有效注销晋升门禁、DELETION_CANCELLATION_REQUIRED/先取消提示；25数据库+3命令fixture、90专项，强制255/176零失败/错误/跳过，BFF重编译/NO-SOURCE | 真实终端隐藏/进程退出、浏览器取消/首次改密、部署/容量/崩溃/多实例未验；S5/S6/D1与完整B1/消费/版本仍开放 |
| 2026-10-04 S5 | 墓碑详情凭据LEFT JOIN、明确null/无凭据不适用；先1红→3红→join2绿1红→3绿，18新增/138相关、强制273/176零失败/错误/跳过，BFF重编译/NO-SOURCE | role/认证/Servlet/近期时间fixture，非浏览器TCP/JS/可访问性/部署/容量；不重建损坏username或完成SSO-04全部交付，S6/D1/B1整体开放 |
| 2026-10-04 S6 | 两生成入口第四组16×5=80bit，HMAC/规范化旧兼容/placeholder；两回归先红，8JVM+33DBMockMvc新增、164相关、强制314/176零失败/错误/跳过，BFF重编译/NO-SOURCE | 旧码仍60bit不自动提升/失效/续期；无真实issuer混合/browser打印下载/可访问性/容量/随机源认证，全量Secret扫描/D1/完整B1版本消费开放 |
| 2026-10-04 D1 | 官方核验/三Security7.1.1锁及Nimbus补丁、starterplatform/直接Security API消费对齐；5renderer红绿+4DBconsent，110server/131starter及强制323/176全绿，18task/serverBFF bootJar实查；临时file repo原生/POM-only实际7.1.1 | 不称应用XSS已复现/MavenCLI或任意consumer强制旧锁已验；完整扫描/browser/TCP/CSP/部署/备份/live消费/版本与B1整体仍待交付；仅file probe非托管发布 |
| 2026-10-04 A1/A2Servlet | 48root+48context actualTCP/Tomcat/JDBC，专用Basic/真实Cookie/编码与风控/CSRF/CAPTCHA/proof顺序/歧义；96新增/181专项/强制419/176零失败错误跳过，12task/BFF重编译NO-SOURCE；test/docs无新业务repair | fixture错误校准非漏洞red，SecureCookie手工HTTP转发/固定issuerOrigin、240hwindow、proxyforward禁用；productionproxyTLSbrowser/阈值窗口容量/任意URI/S2更广及B1version独立 |
| 2026-10-05 持续计划S2/03/04/06 | S223DBrollback/91专项；publictrustedcustomizer/callback2与85相关；用户ADR-0007stateEpoch/query17+load1，48相关及combined110绿；identified0.1.0-dev.20261005.1/source263hash/三锁/Jar，两包外原生POM编译解析，全量460/178零失败错误跳过18task | OAuth400假定错已校准非新漏洞；queryrestore仅SQLshape/本机load非SLA，consumergenerationbarrier/local15m与liveSSO双browser/S2grantissuerace/B1durableordering/fullscan/production未验，仅localfilepublication未commitdeploy |
| 2026-10-05 UserInfo与真实浏览器/.2 | 真实UserInfo302回归先红，AS resource-server/显式Bearer converter/UserInfo401修复，15专项；默认/自有参考BFF真实Edge双Context/ALTCHA/双登录/绑定重认证/当前全局退出/Back-channel，最终hash JAR再次两模式通过；强制461/178零失败错误跳过18tasks及制品compile，30docs/434links/manifest核验、针对性四logcanary无命中，临时进程/DB清理 | 本地HTTP/harness标准参数非productionTLSproxy/consumer15m；durableordering/实际backup/容量/维护/fullscan/外部URI/正式release独立，无commitdeploy |
| 2026-10-05参考持久撤销/.4 | 用户ADR-0009参考profile独立PG/AES-GCM/commit/2mlease8claimretrydead成功擦cipher，37actualDBHTTPcontext/真实Edgedefaultapp停JVM恢复SSO旧RT拒/别sid独立，691全绿20tasks/278source9artifact3锁及两consumercompile，10logtargeted无命中/临时DBkey配置清理 | 非presubmitcrash原子/SessionTokenindexreplay持久/completeB1、deadcleanupkeyrotationproductionTLS容量灾备业务采用；无commitremote deploy |
| 2026-10-06恢复屏障/.20261006.1 | 18新恢复测试/709全量零失败错误跳过20tasks；真实PGdump/restore、两持久key、真正PTY/PowerShell/JARhash确认、旧CookieCodeATRT拒/freshlogin/后台outbox及到期deletion恢复，284source10artifact3锁/两consumercompile；12logtargeted无命中，进程容器volume内backup/外配置与两fixturekey已清 | 非productionbackupRPORTo/key/proxyTLS、真实consumer缓存屏障或旧cachedkid自动拒；bootstrap仅真console隐藏输入，firstchange/resetUI及fullscan容量完整B1仍开，无commitremote生产库 |
| 2026-10-06管理员维护/.20261006.2 | 真PowerShellhash/WindowsPTY hiddenbootstrap/强改/恢复码showdownloadreload/offline reset负例无变与成功废旧SessionToken/freshOIDC/globalBack-channel；发现旧CookieOIDC续行两先红，serverfilteranonymous成熟验证修复7新增/reauth18，716全绿20tasks/284source10artifact3锁/nativePOMcompile，57docs/650links/四logtargeted无命中，临时DBconfigkey与恢复plaintext清理 | 非populated晋升取消/live24hexpiry/生产终端TLSofflinecustody/完整recoveryUIaccessibility；B1presubmitgap/持久Sessionindexreplaymultiinstance、consumer15m/epoch/productionscan容量正式release独立，无commitremote生产库 |
| 2026-10-06 TLS/proxy/.20261006.3 | 旧guideForwardedPrefix实测evilcallback红→repoincludecanonical绿、参考httpsprofile；finalJAR/CAHTTPS/defaultapp双browser SecureHttpOnlyLaxhostonly/HTTP不发/reauthOriginCSRF/currentglobalHTTPSBack-channel/伪IPencodedbudget过；716全绿20tasks/source286含deploy12artifact3锁/twoartifactcompile，61docs690links、八containerlogtargeted无命中，四容器volume/网络与所有keysecret配置已清 | 非officialDNSpublicCArenew/CDN多跳prefixcrosssite/firewallhosthardening/productionwindowcapacityfullscan；B1durable完整/consumer门禁与正式签名发布独立，无prodDB/globalCAhosts/commitremote |
| 2026-10-06活跃安全扫描/.20261006.4 | Boot4.1.1/Framework7.0.9/Tomcat11.0.26/Jackson3.1.7/BC1.86/PGdriver42.7.13/Log4j2.25.5；三锁234包/解包JAR150包17不同基线CVE不再报告、Semgrep221path保1误报0error、Gitleaks source/JAR/allhistory各0match；716全绿20tasks/286source12artifact3newlocks/nativePOMcompile | 保厂商严重级别/功能条件和覆盖限制，不把opaqueJAR0package算通过；本阶段外部TLS/维护未用.4重验，后续重验见下一行；productionimage/log/DAST/实际consumer/签名正式发行仍开 |
| 2026-10-06finalartifact/readiness/.20261006.5 | .4真实重验后DBdownreadiness假200红→dbgroup绿、新test实际200503200/liveness200；717全量0skip20tasks/source287/12artifact3unchangedlocks/nativePOM/scans；.5finalhashTLS双链/admin/restore重跑通过，有界query/login/hash/JVMpoolSQL+DB恢复/anonymousmetrics302；34finalruntime日志定向无命中/fixture全清 | 当时domainmonitor未实施，后续见下；非productionSLA/持续load；正式DNSCA/CDNfirewall/生产backupimageDAST/fullUI/actualconsumer15mepoch及B1durable完整/签名发行独立 |
| 2026-10-06核心私有监控/.20261006.6 | fixedmetric/hashTimer/cacheDBvalidfresh/独立credential管理port、17new734全量0skip20tasks/source30218artifact3locks；repo7rules/8panel10query，exactJARPromeAMwebhook3真firingresolved，finalTLS两chain/241lock157JAR0vuln/Semgrep2321误报0error/Secrets三0match/13containerlogtargeted无命中/fixture清 | 当时Grafana只query，后续见下；privateprodTLSrotation/oncallchannel或所有7livealert/remainingprobe/soak/production独立 |
| 2026-10-07Grafana mixed/IDhint/.20261006.7 | .6actualrefresh→global400最小red，V15HMAC-only关联/typeonlylookup/cascade/upgradecurrent修green/1new735全量0skip；finalactualGrafana1440/390八panelViewerwrite403，两301/302smixed12,598operation/120refresh0reuse/40processedBack-channel/queue空/sentinel保，scan/nativePOM/fixture清 | 1samplinggap保/短memsample非leak排除或小时soak/production容量，actualGrafana生产authretention/privateTLSoncall与remainingprobe/IDhistorysizing/正式发行独立 |

截至2026-10-07：当前.20261006.7真实refreshlogoutIDhint缺陷已修V15、735全量0skip20tasks/source30318artifact3unchangedlocks/nativePOMscans，actualGrafana importdesktopmobileViewer/十分钟mixed两chain通过。原.6alerts/.5adminrestore保历史；下一生产Grafanaauthretentionbackupsecurity/privateTLSrotationoncall、小时级rampsoak/真实hardwareDBvolume/metricscost/historycleanup、remainingdomainexternalprobeUI/productionimageOSJDKproxylogDAST/officialDNSCA-CDNfirewall/backupkey/actualconsumer15mepoch与formalrelease。完整B1/customdurableindexreplay/presubmitgap仍独立，不business实施。
