# 服务资格代次、生命周期响应与负载记录

2026-10-06补[真实隔离恢复](../integration/development-delivery-2026-10-06-1.md)及[可执行屏障](../operations/restore-epoch-barrier.md)，pgdumprestore/持久key/新epoch低counter/旧Token拒绝已验。consumergeneration是harnessfixture非业务runtime，productioncapacityrestoreconsumer独立；下原阶段证据保留。

- 状态：2026-10-05 SSO-04server事实/epoch实现与受控验证完成；真实restore/消费对账/生产容量待验。
- 英文：[Service-access state epoch, lifecycle wire contract and load observation](../../implementation/service-access-epoch-contract.md)。
- 决策：[ADR-0007](../adr/0007-scope-service-access-versions-to-security-epoch.md)，用户选择复用全局securityepoch。

## Wire/认证

GET /internal/v1/principals/{UUID}/service-access沿原ACTIVEFIRSTPARTY Basic/A3budget/no-store，开发server七字段：

```json
{
  "issuer": "http://127.0.0.1:9000",
  "subject": "019fb42f-5f16-7c31-8ce0-9e54f7441ff0",
  "decision": "DENIED",
  "reason": "DELETION_PENDING",
  "stateEpoch": "opaque-current-security-epoch",
  "stateVersion": 4,
  "changedAt": "2026-10-05T00:00:00Z"
}
```

stateEpoch复用全局security_epoch，同SQLsnapshot读user/credential/deletion，非Secret/grant/timestamp/可排序代次。unknown也含；missingrow/blankepoch503IDENTITY_SERVICE_UNAVAILABLE/no-store不补造/不ALLOWED。unknown200DENIED/PRINCIPAL_NOT_FOUND/version0/changedAtnull，无usernameadminbusinesssecret。strict六字段consumer要升级，无migration。

## 原因/版本/恢复

优先DELETED→ACCOUNT_DELETED、FROZEN→ACCOUNT_FROZEN、有效PENDING/HELD→对应reason、requiredpassword、disabledcredential、有效ACTIVE→ALLOWEDACTIVE，其余DENIEDACCOUNT_STATE_INVALID。部分非法共存是SQLfixture非支持writer。

同epoch支持生命周期在owning事务推进version/changedtime，rollback/rejected不发布。ACTIVE普通改密/恢复推进credentialversion撤session但资格事实/version/time不变；退出PASSWORD_CHANGE_REQUIRED推进资格。query不增counter，Logout/freshness与eligibility独立。

只同(issuer,subject,stateEpoch)排序。restore旧DB可低version，必须newsecurity_epoch后属新namespace；changedAt不能跨恢复排序，不同epoch同数字无关系。consumer须：

1. 关联request/result至当前本地generation；
2. 同epoch拒低version覆盖新事实；
3. 意外epoch失败关闭，废弃旧资格/本地证据，经fresh对账/恢复屏障建立权威当前epoch；
4. 屏障后拒旧epoch延迟响应，不按string/UUID/time比较或每到一条就切换。

server不实现消费cache、不保一分钟观察；business timer/activityrefresh/restart与运维restore窗口归各方。restore复用旧epoch不支持且这对无法检测，独立资格event仍ADR-0001延期。

## 验证/小负载

ServiceAccessLifecycleContractIntegrationTest**17新增**actualPostgreSQL/Spring/MockMvc：七字段/no-store、完整ACTIVE冻结解冻申请holdreleasecancel再申请执行history版本、无凭据墓碑/unknown、precedence/缺失disabledrequired、rollback/rejected、普通passwordrecover区别、requiredexit、freeze未commit另一query旧snapshot、新epoch低version恢复shape、epochrollback、missingblank503。restore仅一次性SQLfixture非pgdumprestore/live；consumer旧inflight拒绝是义务非已实现business行为。

ServiceAccessQueryLoadIntegrationTest**1新增**实际loopbackTCP/Tomcat/PostgreSQL/Argon2，warmup4再8worker×10=80合法查询，原A3client10/network20。都200/ALLOWED/version1/epoch/no-store，成功counter结束零，最后fullrun：

| 请求/并发 | 总耗时 | 吞吐 | p50 | p95 | p99 |
| --- | --- | --- | --- | --- | --- |
| 80 / 8 | 1343.0ms | 59.57req/s | 112.9ms | 203.5ms | 232.1ms |

仅workstationXML实测，非threshold/productionSLA/饱和证明/10m持续/proxyTLS/consumer数量保证。valid并发超过budget仍可能暂429；此8worker证success总数不等于failquota。measurement不打印SecretToken，完整sizingmetricsDBpoolArgon2扫描待验。

serverJava21Docker：

```powershell
.\gradlew.bat :test --tests "org.las.sso.config.ServiceAccessLifecycleContractIntegrationTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --no-daemon --console=plain
.\gradlew.bat :test --tests "org.las.sso.config.ServiceAccessQueryLoadIntegrationTest" --no-daemon --console=plain
```

epoch/queryfoundationbudget**48=17+6+25**零失败/错误/跳过，clientquerypersistence组合**110**亦绿。开发tuplefull**460server/178starter**零失败/错误/跳过，18task/bootJar/metadata；17XML17:47:04.060UTC、load1XML17:47:10.736UTC含记录，命令/来源见[开发交付](../integration/development-delivery-2026-10-05.md)。

无schema/deps/因素权限/eventmigration，JavaDecision加epoch/cacheDTO是明确兼容变化。未commit/托管发布deploy/生产restore。
