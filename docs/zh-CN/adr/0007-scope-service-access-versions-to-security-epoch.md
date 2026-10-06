# 服务资格版本限定于全局安全代次

状态：用户2026-10-05确认；契约受控及2026-10-06隔离真实backuprestore已验，production恢复与依赖方runtimecache独立。

英文：[Scope service-access versions to the global security epoch](../../adr/0007-scope-service-access-versions-to-security-epoch.md)。

## 背景

stateVersion与用户同库备份，恢复旧snapshot会回退。原恢复rotate security_epoch撤销session/auth，但查询cache若只比数字仍会拒新状态或沿用旧状态；裸主体counter不能证明跨恢复全局单调。

## 决策

每个认证查询含opaque非空stateEpoch，包括unknown，复用system_security_state.security_epoch，无新schema/外部counter/业务权限claim；一SQLsnapshot读epoch/主体事实。全局行缺失/blank不可用，不能补ALLOWED或虚构未知代次。

仅同(issuer,subject,stateEpoch)比version。epoch只等值namespace，不按UUID/string/timestamp排序；changedAt非恢复顺序。unknown仍DENIED/PRINCIPAL_NOT_FOUND/version0/changedAtnull，含当前epoch。

恢复开放流量前必须新epoch；不rotate恢复仍非法。依赖方遇不识别epoch失败关闭，通过恢复后fresh查询/对账屏障建立权威当前代次，清旧cache，拒退休代次延迟响应，不能随任意inflight不同字符串往返切换。请求/响应验收需本地generation/sessioncorrelation；业务重启恢复协调归消费方。

## 替代

- 裸version永不回退假设：拒绝，恢复使其不成立。
- 延期：用户未选，否则恢复语义开放。
- 外部不随backup回退counter：未选，增加基础设施/独立恢复ledger超当前范围。

## 后果

后续[ADR-0010](0010-executable-restore-epoch-barrier.md)／[交付.20261006.1](../integration/development-delivery-2026-10-06-1.md)增expectedepoch/交互nonweb事务屏障与真实pgdumprestore/持久key验收；generation缓存拒绝是testharness非consumer实施，下旧测试界限保历史。

response第七字段/JavaDecision加epoch，strictconsumer升级DTO/校验/cache namespace。无业务权限/MFA/独立资格事件。epoch是标识非Secret/grant，只认证internal暴露不绕签名/client。

受控test新epoch区分低version、rollback、missingblank503/no-store，不实现consumercache或真实backuprestore。见[查询契约](../implementation/service-access-epoch-contract.md)、[开发交付](../integration/development-delivery-2026-10-05.md)及ADR-0001职责/延期事件。
