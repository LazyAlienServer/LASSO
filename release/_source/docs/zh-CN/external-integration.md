# 业务网站接入步骤

[目录](README.md) · [English](../en/external-integration.md) · [Starter SDK](../../sdk/README.md) · [API参考](api-reference.md)

**适用：** 服务端 / BFF Confidential Client。浏览器只持有业务 Session Cookie。

## 接入路线

| 步骤 | 成功判据 |
| --- | --- |
| 1. 登记客户端 | 每站/环境独立，精确 HTTPS URI |
| 2. 配置 Code + PKCE | state/nonce/签名校验，稳定 UUID 映射 |
| 3. 接入 Starter 或自有库 | 本地会话隔离、正确 CSRF / Cookie |
| 4. 刷新和退出 | 同会话合并，明确拒绝清登录，本地执行回执 |
| 5. 第一方资格/近期密码 | 一分钟拒绝、15分钟发起会话绑定 |
| 6. 真实消费方验收 | 下表测试通过 |

## 1. 登记客户端

在 SSO `/admin/clients` 由管理员填写：

| 字段 | 值 |
| --- | --- |
| client_id / 名称 / 环境 | 独立维护 |
| 类型 | LAS控制部署为FIRST_PARTY；批准合作方为REVIEWED_PARTNER |
| callback / post-logout / Back-channel | 精确可达HTTPS地址 |
| scope / audience | `openid profile`、目标资源audience |
| Secret | 一次显示，私密交付服务端 |

合作方需 consent；第一方免 consent 不放宽协议检查。不把 Secret 放浏览器，不使用 implicit/password grant。

## 2. OIDC 与身份映射

| 检查 | 要求 |
| --- | --- |
| Discovery | 精确Issuer，不混hostname/localhost/127.0.0.1 |
| 授权 | Code + PKCE S256，client_secret_basic |
| 登录事务 | state/nonce一次性有期限，returnTo站内allowlist |
| ID Token | 验签/算法/kid/iss/aud/azp/时间/nonce |
| 主体 | `(issuer, sub)`唯一；sub为不可变UUID |
| 登录名 | 可变且大小写敏感；不作业务主键 |

`LasUserPrincipal.getName()/getUsername()` 返回UUID，`loginUsername()`返回登录名。新身份不自动获得业务敏感角色。

Token/Secret/code不进入前端存储、分析或日志；ID Token不是API Bearer。资源服务器按AT的audience/scope/issuer/签名/时间校验。

## 3. Starter 配置

消费[固定版本SDK](../../sdk/README.md)，应用需JDK21、Servlet Web、OIDC JSON转换器及最终依赖锁审查。

```properties
spring.security.oauth2.client.registration.las-sso.provider=las-sso
spring.security.oauth2.client.registration.las-sso.client-id=${LAS_SSO_CLIENT_ID}
spring.security.oauth2.client.registration.las-sso.client-secret=${LAS_SSO_CLIENT_SECRET}
spring.security.oauth2.client.registration.las-sso.client-authentication-method=client_secret_basic
spring.security.oauth2.client.registration.las-sso.authorization-grant-type=authorization_code
spring.security.oauth2.client.registration.las-sso.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}
spring.security.oauth2.client.registration.las-sso.scope=openid,profile
spring.security.oauth2.client.provider.las-sso.issuer-uri=${LAS_SSO_ISSUER}
las.sso.client.registration-id=las-sso
las.sso.client.refresh-before-expiry=PT60S
las.sso.client.post-logout-redirect-uri={baseUrl}/logged-out
las.sso.client.session-cookie-name=__Host-my_business
las.sso.client.session-absolute-lifetime=P14D
```

实际Servlet Cookie同名，Secure/HttpOnly/Lax/Path=/、无Domain。

| 端点 | 方法 / 用途 |
| --- | --- |
| `/api/auth/login`、`/api/auth/session`、`/logged-out` | GET，本地身份 / 登录返回，不等业务授权 |
| `/api/auth/logout`、`/api/auth/logout/global` | POST，CSRF保护 |
| `/internal/oidc/backchannel-logout` | 签名协议POST，只对此例外；不豁免浏览器API |

### 自有 SecurityFilterChain

1. Starter不会覆盖应用链。
2. 调用一次 `LasSsoSecurityConfigurer.configure(http, existingSuccessHandler)`。
3. 保留应用route/CSRF/业务权限策略；之后不覆盖包装后的success handler。
4. 参考[默认/自有链样例](../../examples/reference-bff/README.md)。

## 4. 刷新与退出

| 场景 | 实现要求 |
| --- | --- |
| AT / RT期限 | 默认AT15分钟、RT family绝对14天，轮换不顺延；以实际exp为准 |
| 并发refresh | 同本地session single-flight合并 |
| 明确refresh拒绝 | 清业务登录，不重试旧RT |
| 暂时故障 | 独立503，不当封禁或密码证据 |
| 当前站退出 | 先销本地登录，再RFC7009；不结束SSO主会话 |
| 全部退出 | Discovery end_session、保留ID hint、精确URI、一次性state |

存活授权跨refresh保HMAC hint关联；升级前丢失hint不重建。

### Back-channel 验证与本地执行

- 接收form `logout_token`，不使用浏览器Cookie。
- 验 `typ=logout+jwt`、RS256、issuer/audience/时间/jti/events、sid或sub。
- 拒nonce/畸形Claim；按 `(issuer,sid)` 幂等撤销。
- 重放/sid撤销标记至少保本地absolute lifetime（默认14天）。
- 成功必须是本地执行commit，不仅解析；防迟到回调/refresh登记复活。

默认repository/index/replay/refresh协调/撤销sink为单JVM/HttpSession，重启丢业务登录。参考持久queue仅保已commit task，提交前补偿/多实例/持久业务登录仍消费方实现；见[队列维护](reference-revocation.md)。`LasSsoRevocationSubmission.REQUEST_ATTRIBUTE`是提交结果，不是OP回执。

## 5. 第一方服务资格与近期密码

### 服务资格

| 项目 | 契约 |
| --- | --- |
| 调用者 | ACTIVE FIRST_PARTY Basic，不用Cookie/Bearer替代 |
| 路由 | GET `/internal/v1/principals/{UUID}/service-access` |
| 七字段 | issuer、subject、decision、reason、stateEpoch、stateVersion、changedAt；no-store |
| 未知UUID | 200 DENIED/PRINCIPAL_NOT_FOUND/version0/changedAt=null |
| epoch缺失 / 认证故障 | 503或认证错误，不能伪ALLOWED |
| 拒绝状态 | freeze / 删除等待或held / 必须改密 / 禁用凭据 |

版本仅同 `(issuer,subject,stateEpoch)` 比；epoch不可排序。意外代次先fail closed，再新本地generation权威对账、清旧证据/拒旧inflight，不随响应切代。

查询/主动刷新补偿须一分钟内观察拒绝。包不实现业务cache/timer；SERVICE_ACCESS_CHANGED延期，Logout不代资格同步。

### 近期密码

1. 标准授权customizer发 `prompt=login` / 适当 `max_age`。
2. 绑定待执行操作、发起本地浏览器session、state/nonce/PKCE。
3. 验证登录后读签名初始ID Token `auth_time`；Spring接点为 `getAuthenticatedAt()`。
4. 只在发起session内15分钟可复用，到期重输密码。

OP挑战5分钟、JDBC原子完成/消费；refresh/issue time不推进证据。Starter提供接点，不替业务实现pending/expiry；弹窗不是证据，无MFA新增门禁。

## 6. 消费方测试表

| 测试 | 预期 |
| --- | --- |
| 双客户端/改名 | 独立登录、同UUID |
| 并发refresh/reuse | 合并、复用拒绝 |
| 当前/全局/重复迟到退出 | 精确本地session撤销，无复活 |
| 客户端禁用/provider网络故障 | 正确拒绝/独立不可用 |
| 重启 | 初期业务登录结束 |
| freeze/注销/held | 一分钟内观察拒绝 |
| 恢复新epoch | 拒旧cache/inflight |
| 跨浏览器/到期/refresh密码证据 | 发起session15分钟边界正确 |

记录真实业务结果；参考BFF或SSO单测不替实际消费方验收。

## I01 对接准备与登记交接

**操作者/位置：** 消费方后端负责人+SSO管理员，隔离测试配置记录及SSO浏览器；先保持业务路由拒绝。

1. 确定issuer、业务HTTPS origin、registrationId、完整callback/post-logout/可达Back-channel、audience，以及单实例/重启失效政策。
2. 容器也需可达地址/验证TLS，不把localhost当另一容器；代理保留完整回调路径，不复制旧strip-api规则。
3. 管理员[A05](administrator-guide.md)建立独立client，交接非秘密配置/version，secret仅私密交后端。
4. 消费方核类型/scope/URI一致，固定SDK/SSO版本及hash。仅SSO账号站不需创建虚构client。

**预期：** 精确输入一致。**失败：** URI/可达性先确认，不用wildcard或省略退出验收。

## I02 固定SDK与唯一安全链安装

**位置：消费方项目，非SSO包根。** 参考BFF不自动创建/启动LASLOG接入。

1. 验包并声明[SDK file Maven](../../sdk/README.md)，精确starter{{VERSION}}、最终BOM/锁/Java21兼容。
2. 第3节properties放消费方外部配置，私密secret/issuer；Servlet Cookie与配置同名、Secure/HttpOnly/Lax/Path=/、无Domain，代理同源。
3. public configurer在自有SecurityFilterChain只configure一次，保业务默认拒绝/CSRF/成功delegate；不重复Filter/覆盖包装handler，返回目标采用公开wrapper。
4. 按政策替换public repository/session/replay/sink/refresher/manager，验证实际Bean调用；有DB不等default持久，worker由所选适配完成。
5. 起隔离测试consumer，核路由/错误/Cookie/代理/受限状态，密码只在SSO输入。

**预期：** 固定依赖/唯一链启动，权限边界明确。**失败：** 修外部secret/discovery/链组合，不复制隐藏源码。

## I03 首次登录与双浏览器隔离

1. 浏览器A从业务/api/auth/login?returnTo=受控站内路径进入，完成SSO登录及适用partner consent。
2. 后端核一次性state/nonce/PKCE/code/签名Token，回调成功前保存及登记session索引；浏览器只接local Cookie和安全session。
3. 浏览器B同用户独立登录，确认两个local session/Token不共享，refreshA不覆盖B，改名保持sub。
4. 错/重用callback、非法returnTo/client不得建立登录；重启consumer后旧Cookie/未决事务拒绝，新顶层登录依SSO主会话。
5. 记录实际HTTP/browser/版本/非秘密关联；资格/入站/业务授权未实现仍不开放业务。

**预期：** 正确回调建立独立会话，坏事务无成功。**失败：** 共享Token/自动授敏感角色为消费端缺口，不以身份通过放行。

## I04 刷新故障及两类退出

1. 隔离fixture或真实到期验证自动single-flight refresh，结果验证且持久CAS成功才继续访问，不改生产期限快测。
2. 测明确拒绝、save故障、超时/丢响应、退出竞争、迟到结果、真实进程restart；本地拒绝、已接受已知作业持久恢复、worker重试分别有证据。
3. CSRF POST业务/api/auth/logout：local Cookie/登录结束，SSO保持；新顶层登录可普通免密。
4. global endpoint按end_session/hint/state/精确return走浏览器，核双consumer/浏览器/重复迟到Back-channel无复活。
5. 未知B如实记录，旧A200不记family清理；核固定family截止/残余AT访问，由责任方接受或另选增强，不故意reuseA补偿。

**预期：** 故障/退出不重新打开旧登录。**失败：** outage非封禁，接受作业/日志/普通200不等family完成。

## I05 资格和近期密码接线

1. 后端认证七字段query，核issuer/sub/reason，同epoch高水位/一分钟cache及active调度；空闲游戏/业务权限归所属责任。
2. 恢复epoch要独立核验/expected generation/fresh query，清旧session/cache/pending/evidence，拒迟到，不排序UUID/time。
3. 高风险动作发起session绑定pending/state/nonce/verifier，prompt=login/max_age=0；只SSO收密码。
4. callback核issuer/sub/sid/session/签名auth_time，一次消费；15分钟仅发起session，期限/退出/重启清证据。本地登录成功、refresh或dialog不推进时间；只有已验证的新密码认证证据可建立新时间戳。
5. 用户明确重新提交业务动作，重新查资格/业务/resource；callback不执行命令，测跨浏览器/截止相等/错身份/旧callback。

**预期：** 身份/资格/业务门禁独立，证据可验证。**失败：** SSO count不代consumer验收，不新增MFA。

## I06 完成及升级维护

**预期：** 消费方自身证据覆盖支持的接入及故障路径，版本固定且有运维负责人。

**失败：** 消费方失败或未执行的检查保持未完成；修对应适配器/配置并重验后才开放相关路径。

1. 第6节及[API F07–F08](api-operations.md)记录expected/actual/未执行/限制。
2. consumer负责人核权限/恢复/通知/负例，只开放已验路径，样例不能替自己的结果。
3. client/secret变更按A06–A08复验；SDK升级固定新version/hash/锁/public API兼容，不随兄弟HEAD。
4. 事故先本地失效保作业，查safe metadata，按授权scope处理，不自动全用户升级撤销。
