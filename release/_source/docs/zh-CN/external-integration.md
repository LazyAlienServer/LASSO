# 业务网站接入步骤

[目录](README.md) · [English](../en/external-integration.md) · [Starter SDK](../../sdk/README.md)

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
