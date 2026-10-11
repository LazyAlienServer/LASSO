# API 调用：从认证到结果确认

[目录](README.md) · [English](../en/api-operations.md) · [字段/响应参考](api-reference.md) · [OpenAPI](../../api/openapi.json) · [用户](user-guide.md) · [管理员](administrator-guide.md)

## F01 选择接口、准备可信客户端工具

**位置：本机可信API工具或应用后端的开发/测试环境。** 此页解释请求顺序；使用包内schema构造正文，不在命令行粘贴真实密码/Token。首次试写用隔离账号/client，不能向未确认生产目标发送。

1. 导入 `api/openapi.json`；设置真实canonical issuer/base URL。测试fixture只用于本机，普通HTTPS必须验证CA/hostname。
2. 选择operation，确认属于SSO Cookie、recovery Cookie、machine Basic、AT Bearer或collector Basic；不同认证不能互换。
3. 在工具的私密配置/受限cookie jar保存认证材料，关闭请求/响应全量日志、云同步和分享；密码不写进保存的非秘密请求模板。
4. 读[参考](api-reference.md)中的前置限制/成功码/幂等规则。下方目录将每个operation关联到流程，路径及完整字段以OpenAPI为准。
5. 准备只记录UTC时间、方法/路径、status、稳定code/requestId、目标ID和非秘密version。204无正文，不能强制调用JSON解析。

**预期：** 已明确目标/调用身份/影响/成功判据。**失败：** TLS/Discovery不匹配先修配置；不关闭验签/TLS来制造成功。

## F02 SSO Cookie、CSRF及写入的一般流程

**预期：** 已认证、获授权的请求满足操作的CSRF/同源/密码条件，返回文档状态，并用读回或审计核结果。

**前提：** Cookie模式用于SSO自身页面/API，不支持用用户AT或OIDC client secret获得管理员身份。

1. 在SSO浏览器登录并核用户名/UUID。若API工具用独立cookie jar，必须在该jar中完成受支持浏览器表单流程；不能把另一个origin浏览器Cookie随意复制出去。
2. 用同jar GET `/api/v1/csrf`（getCsrf），读取headerName/parameterName/token；保留响应cookie。该请求不建立登录。
3. 应用写请求使用返回header及token、Content-Type application/json、Accept application/json，同canonical Origin（缺Origin时同源Referer）。Origin不是CSRF替代。
4. 操作要Key时生成一次Idempotency-Key；原请求不确定后的同key重试保持同actor/operation/正文。新明确操作才换key；注册key是UUID。
5. 操作需要近期密码时先POST `/api/v1/me/reauthentication`（reauthenticateAccount），密码只走私密body。成功204、HTTP Session ID轮换；刷新cookie jar/CSRF后再发原操作，不能把认证成功当作原操作已执行。
6. 发请求一次；按该operation的200/201/204、正文schema或Location判定。读回目标状态/审计核最终结果。

**失败：** 401/session过期重新登录；403区分role/CSRF/origin/近期密码/受限状态；429遵Retry-After；400核字段/枚举，不能自动放宽scope；超时查结果。部分filter/改密错误是 `{code}` 或空正文，匿名可能重定向，不能统一按Problem解析。

**非秘密最小示例：** 登录浏览器打开SSO同源开发页/控制台后可读取profile（不是从其他站点跨源执行）。不要打印CSRF/Cookie，生产不保存开发工具认证抓包。

```javascript
const response = await fetch('/api/v1/me/profile', {
  credentials: 'same-origin', headers: { Accept: 'application/json' }
});
if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) {
  throw new Error('Inspect status and safe error before parsing');
}
const profile = await response.json(); // Keep private; not authentication proof for another site.
```

## F03 注册及两步恢复

1. 注册：F02先CSRF，再issueCaptcha用REGISTER；把返回parameters/signature原样交ALTCHA v2求解，不手造payload。
2. register发送RegistrationRequest+UUID Key；201保存一次性恢复码/用户ID，200 replay只确认已有用户。注册不登录；新用户执行U02。
3. 开始恢复：startRecovery用当前名、credential及RECOVERY_CODE/ADMIN_ISSUED，风险要求时先取RECOVERY_RISK challenge；204保留__Host-las_recovery。
4. 在10分钟内completeRecovery用同jar+CSRF、PUT newPassword；204清恢复cookie，用户普通登录。旧Credential在验证时已消费，不能当网络重试通用凭据。
5. 注册/恢复中断按U01/U08检查状态与支持重发，不下载或请求“读取原秘密”。

**预期：** 新注册/恢复状态可核，码/密码不进入普通记录。**失败：** 新password策略400修输入；失效恢复session401重新开始需另一有效凭据；CAPTCHA_REQUIRED后新challenge求解，不无限自动重试。

## F04 账号读写与结果核验目录

**预期：** 所选U流程的状态和读回一致；一次性结果已私密保存，结束会话的动作需重新登录。

**失败：** 按F02/U处理错误；未知写结果先读当前状态，不用已结束的会话证明仍可继续访问。

**前提/顺序：** 按F02获得有效normal Cookie和写入条件；每行关联用户逐项步骤，正文schema见OpenAPI。GET先读，写后再读，秘密只首次保管。

1. 先发送对应GET，核本人/目标状态、normal session及当前版本/期限。
2. 按所选U编号确认操作范围，正文只含OpenAPI定义字段；写入使用F02的CSRF/同源及适用密码/Key。
3. 提交一次，按200/204及安全错误判定；秘密结果马上私密保存，读回当前状态核是否提交。
4. 会话结束的操作用新登录/审计核结果，不能用已失效Cookie重复读取或把超时当失败重做。

| operationId | 请求与操作流程 | 核结果 |
| --- | --- | --- |
| getProfile / updateProfile | GET/PATCH profile，U04，字段displayName/avatarResourceId | 200 Profile、新显示名/原UUID |
| changeUsername | POST newUsername/currentPassword，U05 | 200 Profile；新名/availableAt，UUID不变 |
| changePassword | POST当前或temporaryPassword+newPassword，U03/U06 | 204后新登录；旧session不可用 |
| getRecoveryCodeStatus / rotateRecoveryCodes | GET数量，F02近期认证后POST rotate，U07 | 200状态/新一次性批，无replay读取 |
| listSessions / revokeSession | GET列表后选sid DELETE，U09 | 204或empty404；当前sid全用户退出 |
| logoutAll | POST Key，U09 | 204、全部用户SSO登录结束 |
| listAuthorizations / revokeUserClientAuthorizations | GET按client汇总，再DELETE clientId，U10 | 204后该项移除，其他client独立 |
| getDeletionRequest / requestDeletion / cancelDeletion | GET后POST或DELETE password+Key，U11 | 200 PENDING或204取消后读状态 |

受限强改/注销Cookie不能访问所有normal GET。对logout或改密不能用已失效jar继续查用户状态，应按普通登录/审计核结果。

## F05 管理员写入与读取目录

**预期：** 准确目标/状态/configVersion及审计符合所选A动作；一次性结果私密交付。

**失败：** 403核管理员自身的session/证据/CSRF/同源；未知结果读目标/审计，不为获得成功扩大或重复动作。

1. 使用自己的SSO admin Cookie；先F02近期认证，对话框/自动化都不能用目标密码。
2. GET选准确目标，记录其ID/state/configVersion和影响。
3. 构造完整schema、reason及适用Key；写一次。204不解析body，200读取版本/一次性结果。
4. GET重新核配置/状态，A10核具体审计；为防失败重试扩大影响，未知提交先调查。

| operationId | 详细流程 | 专用条件/核验 |
| --- | --- | --- |
| listUsers / getUser | A01 | UUID cursor；deleted凭据null合法 |
| freezeUser / unfreezeUser | A02 | reason+Key，204，旧grants不复活 |
| revokeUserSessions | A03 | 用户全scope，204 |
| issueManualRecovery | A04 | 核验后reason+Key，200 secret一次/30min |
| listClients / getClient / createClient | A05 | create无Key保证、200 secret一次 |
| updateClient | A06 | 全组PATCH，200 revocation/count/version |
| disableClient / enableClient | A07 | 204，不恢复旧授权 |
| rotateClientSecret | A08 | reason+Key，200；replay secret=null |
| listDeletionRequests / holdDeletion / releaseDeletion | A09 | holdUntil future≤90days；不重置executeAfter |
| listAuditEvents / listSecurityEvents | A10 | read，稳定过滤/cursor及offset时间 |

## F06 内部资格读取与分页

**前提：** 后端持有ACTIVE FIRST_PARTY登记Basic；人类Cookie不能替代。认证凭据通过受限工具/配置提供，GET不需要浏览器CSRF。

1. getServiceAccess路径subject从已验证的issuer/sub获得，不从浏览器自报用户名推定。
2. GET并验证200 JSON精确七字段/issuer/subject/epoch/version/reason，changedAt允许null。
3. DENIED是权威结果，401/429/503/网络/非法body为不可用；不能把故障写成已证实账号封禁，也不能放行。
4. consumer设置一分钟观测规则及本地generation/epoch屏障，按受支持契约对账；SSO端点不会自行清业务cache。
5. 分页listUsers/listClients/listDeletionRequests/listAuditEvents/listSecurityEvents保持过滤、默认limit50、服务端clamp1–200，cursor使用返回值；下一次新筛选清cursor。

**预期：** 只获得资格，不创建业务成员/角色。**失败：** 不主动拿旧Bearer、身份fixture或缺失epoch补造ALLOWED。

## F07 首次OIDC登录及受控重认证

**位置：受支持BFF后端和两个隔离浏览器，非SSO API管理员cookie jar。** 登记/库配置详细顺序见[接入](external-integration.md)。

1. oidcDiscovery或oauthMetadata从可信HTTPS issuer读取metadata，getJwks由库取得公钥；verify issuer/URL/trust，不把Token自带jku当可信地址。
2. BFF生成会话绑定的一次性state/nonce/PKCE verifier，使用authorizeCode的浏览器GET；authorizeForm是框架POST授权/consent，初期优先已验GET路线。
3. 需要交互时loginPage/passwordLogin在SSO收密码；风险CAPTCHA按页面。需要重新输密码时BFF新事务prompt=login/max_age=0，SSO用oidcPasswordPage/verifyOidcPassword（5分钟challenge），不是BFF直接调用密码API。
4. callback核state/一次性/session，exchangeOrRefresh按authorization_code提交code/原redirect/verifier和client_secret_basic。
5. 库验证ID Token签名/算法/iss/aud/time/nonce，getUserInfo或postUserInfo用AT Bearer，sub必须相同；只服务端保存Token，浏览器拿local Cookie。
6. 在本地成功前完成存储/索引登记。高风险证据只关联发起session的签名auth_time，15分钟精确到期，不自动执行业务命令。

**预期：** 当前BFF登录可读安全session，不能把身份自动当成员权限。**失败：** callback/code/state失败从新顶层事务重来，不重放旧code；密码页wrong401不推进证据，challenge到期由BFF重新发起。

## F08 Refresh、撤销、introspection与退出

1. exchangeOrRefresh用refresh_token时，网络调用前提交本地意图，同session单飞。新Token验证并持久CAS成功后才能继续访问，详[接入](external-integration.md)。
2. 明确invalid_grant终止本地登录；响应丢失/保存前崩溃结果未知，安全失效、保存已接受已知材料、不要故意重复A。
3. revokeToken POST已知Token+可选hint/client Basic，成功200也可能未知/无效；只能按确立契约记录提交Token结果，**旧A的200不能记后继family已清理**。
4. introspectToken是标准存储Token查询，client认证后200 active/false；不是取回B、权限查询或family截止证明。未知A的active=false不证明B不存在。
5. ssoBrowserLogout（POST /logout）属于SSO用户全scope。BFF当前退出使用该站/api/auth/logout；oidcLogout/oidcLogoutForm由BFF使用end_session做全局浏览器流程，核初始hint/精确登记返回/state。
6. logout先本地失效，再已接受作业可靠执行；200HTTP、task接受、family清理是不同结论。按产品明确接受残留未知授权限制；历史RT补偿不自动要求新增。

**预期：** 退出或不确定状态不能因迟到结果复活。**失败：** provider不可用保失效/任务重试，不重启恢复旧登录、不自动全用户撤销扩大scope。

## F09 私有健康与指标

**预期：** 私有liveness/readiness、collector认证状态符合文档，快照新鲜且采样成功。

**失败：** 按M01/T01定位listener/配置/凭据/DB，保持管理口私有，区分liveness和readiness。

1. liveness/readiness在正确私有management地址GET；Compose9091，不用public proxy路径。readiness200包含DB，DB故障可等连接超时后503；liveness不证明DB正常。
2. metrics使用独立collector Basic/GET，无认证401、正确认证200。密码不放URL/argv，配置由[监控步骤](monitoring.md)准备。
3. 200 scrape另看snapshot_available/last_success；旧gauge不作健康证明。
4. 未启monitoring或错误listener404按配置排查，不开放公共actuator来测试。

## F10 手工验证的完成记录

**预期：** 记录将实际步骤/status/读回关联到精确版本及目标，未执行和限制明确。

**失败：** 无证据的项保留未验证状态；记录失败类别并定向修正，不改预期来制造通过。

1. 每项填写版本/hash、目标测试环境、操作员、前提、步骤、预期/实际、status/code/requestId及read-back/audit引用。
2. 单测/文档命令解析/fixture与真实HTTP/browser区分；未试过写“未执行”，不因OpenAPI存在就称验收通过。
3. 秘密输入/输出不进报告。清测试账号/client的影响按其正式操作流程，身份永久事实不能随意SQL删除。
4. 有真实失败先保持受保护访问拒绝，交负责人定位再最小修复；不取消协议/业务门槛。
