# 客户端Secret变更竞争与回滚验证

- 状态：2026-10-05 S2既有修复扩大PostgreSQL/MockMvc验证；真实维护/消费/容量独立。
- 英文：[Client Secret mutation concurrency and rollback verification](../../implementation/client-secret-concurrency.md)。
- 跟踪：[状态§3.15](../../implementation/status-issues-and-plan-2026-10-02.md)。

## 实现/不变量

支持的patch/rotateSecret/disable先锁同client metadata再读RegisteredClient/配置，等待后读当前对象，避免patch回写轮换前Secret。enable仅UPDATEmetadata/status/version，通过UPDATE取写锁，不重写RegisteredClient/Secret。本扩大无需新生产修复。

轮换单活动Secret：hash/version、Authorization/Consent删除、Back-channel outbox、event/audit同事务。name-only后编辑保留新Secret/version；scope等安全编辑仍原规则推进/撤销。disabled可维护轮换但认证拒绝直到enable；enable不复活旧Secret/Consent/Authorization。原状态/version语义含重复enable仍推进未静默改。

仅owning-service变更，非直接Repository writer/local seeder/manual SQL/新授权签发同时竞争，不称全锁图。caller已持冲突锁/边界外writer需自守事务顺序。

## 扩大证据

AdminClientConcurrencyIntegrationTest新增**23项**actualPostgreSQL18.4/Spring代理/MockMvc：

- 九ordered pair：name/rotation、shrink/rotation、disable/rotation、enable/rotation双向＋rotation/rotation；
- commit前pg_blocking_pids/latch确认真实wait，终态hash/version/status/name/scope、授权consent删除/一次outbox；
- 原第一方Basic和OAuth token endpoint验old invalid_client/new故意不存在Code invalid_grant，不造成功grant；
- disable→rotate→enable不复旧码/授权；锁另一client时独立rotation完成；
- 五真SQLaudit fault＋outbox/idempotency trigger fault，早期状态/版本/安全/授权/outbox整体rollback；
- 首rotation rollback后等待nameedit仅见committed旧Secret；
- 并发同幂等key一明文一非secret replay，材料改变拒绝/写失败retry。

client/user/actor与Authorization/Consent走owning services，actor仅servicefixture非browseradmin权限验收；sid明示SQL用于outbox。fault只一次性DB并finally清。九表snapshot覆盖client/metadata/backchannel/auth/consent/security/event/audit/outbox，不称actorSession/全部auxfield无变。idempotency另验无明文记录。认证入站MockMvc，实际filter/provider/hash非liveTCP/真实成功grant。

初17绿，扩大23/91一失败是test假定OAuthinvalid_client401，现handler原400；改400＋error code验证，没有改protocolhandler/接受绕过。最后**91=23+11+53+4**零失败/错误/跳过；组合**110=23+17query+6queryfoundation+11client+53persistence**亦绿。

server Java21/Docker：

```powershell
.\gradlew.bat :test --tests "org.las.sso.admin.AdminClientConcurrencyIntegrationTest" --tests "org.las.sso.admin.AdminClientServiceTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.config.AuthorizationConsentIntegrationTest" --no-daemon --console=plain
```

随后common开发组合强制**460server/178starter**零失败/错误/跳过，18task含两bootJar/publicationmetadata。S2最终23XML2026-10-04T17:46:24.196Z（上海2026-10-05），命令/来源见[开发交付](../integration/development-delivery-2026-10-05.md)。早XML后覆盖，输出/状态§3.15保留阶段。

## 剩余

已识别staleedit/Secret问题有扩大writer/failure证据；liveadmin/维护/clientrollout、grantrefresh签发与mutation竞争、capacitystarvationdeadlock/crashrestartmulti-instance/fullSecretlog独立。无新migration/deps/permission/clientauthprotocol，未commit/托管发布deploy。
