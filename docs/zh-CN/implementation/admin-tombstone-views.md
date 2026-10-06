# 管理员查看已注销身份墓碑

- 状态：2026-10-04 S5修复并通过PostgreSQL/MockMvc/模板验证；真实浏览器/部署待验收。
- 英文对应：[Administrator views of deleted identity tombstones](../../implementation/admin-tombstone-views.md)。
- 跟踪：[状态地图§3.11](../../implementation/status-issues-and-plan-2026-10-02.md)。

## 契约与实现

注销删除密码/恢复凭据与个人资料，但保留UUID身份、保留用户名历史及注销/审计证据。已保留的DELETED账号仍应能在既有管理员列表/详情查看；无密码行不等于无身份。

`AdminUserManagementService.user`将`password_credentials`改为LEFT JOIN，保留users/当前用户名查询和原DTO字段。无凭据行时`credentialState`、`passwordChangedAt`、`lockedUntil`为实际null，不制造ACTIVE/DISABLED枚举或零时间。有凭据的状态/时间映射不变。非终态但缺凭据的明示不一致fixture也可查看，但读取不修复凭据、不授予登录/服务资格。

| 表面 | 已注销身份结果 |
| --- | --- |
| GET /api/v1/admin/users | 混合/DELETED筛选cursor页保留墓碑 |
| GET /api/v1/admin/users/{userId} | 200/no-store，DELETED，三个凭据字段明确null |
| /admin/users自动/显式选择 | 实际Thymeleaf渲染成功，凭据状态“无凭据”、密码更新/锁定到期“不适用” |
| 未知UUID详情 | 原404 ADMIN_TARGET_NOT_FOUND |
| 墓碑管理写 | 原409 ACCOUNT_STATE_CONFLICT，无冻结/解冻/下线/恢复操作按钮 |

显示名仍“已注销用户”；有凭据且无锁仍“未锁定”，无凭据不显示它。DTO无密码hash/恢复码。既有权限、管理员短会话、CSRF/Origin/近期密码写门禁和no-store详情保留。读取不创建凭据、推进资格、取消申请、写成功审计或恢复账号。无新端点/schema/依赖/构造或record字段/保留规则。

## 反馈闭环与证据

约20:31–20:32Asia/Shanghai最小真实删除/service回归失败ADMIN_TARGET_NOT_FOUND；列表有同账号、密码行已删。20:35扩大三条，service不存在/API404/自动选中页面404均红。仅改join后service/API转绿，页面因空白/不正确无值展示仍红；20:37模板fallback后三条通过。区分查询丢行、用户名缺失与nullable映射/渲染。早期XML被后续覆盖，命令/阶段记录保留红绿，不补造独立文件。

`AdminUserTombstoneIntegrationTest`新增**18项**：真实执行注销后的list/detail；JSON null明确存在/no-store/无秘密字段；实际自动/显式/被filter排除时仍选中模板；未知UUID；反复读取完整行快照；四类终态写拒绝且目标快照不变；普通/匿名不可读；三种原凭据状态/锁与改密时间；冻结展示/解冻按钮；混合/DELETED分页；ACTIVE缺凭据fixture仍资格拒绝；注销未提交时另一实际事务读旧完整状态、提交后读墓碑。

目标通过注册/真实注销service创建，仅将执行时间设为到期。管理员role/认证/ServletSession及写测试近期密码时间为fixture，不是浏览器/bootstrap/密码证据验收。并发读用独立PostgreSQL事务/Future限时，在删除变更之后、commit之前读，证明该受控快照而非所有list/detail交错/多实例竞争。目标快照覆盖身份/用户名/凭据/请求/恢复/会话/授权/事件/审计/outbox，不声称actor Session元数据或所有辅助表不变。

server目录Java21、Docker可用命令：

```powershell
.\gradlew.bat :test --tests "org.las.sso.admin.AdminUserTombstoneIntegrationTest" --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.admin.AdminUserTombstoneIntegrationTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --tests "org.las.sso.admin.AdminUsersTemplateContractTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

约20:46–20:47专项**138=18+10+24+25+2+53+6**零失败/错误/跳过；约20:48–20:50强制全量**273 server/176 starter**同零失败/错误/跳过，12actionable全执行，BFF重编译、自身NO-SOURCE。最终18项XML `2026-10-04T12:48:55.549Z`，实际PostgreSQL18.4Testcontainers运行。相对S4历史255新增18。

## 剩余验收

MockMvc实际执行Spring安全/MVC/JSON/Thymeleaf/PostgreSQL，非真实TCPServlet、浏览器JS、可访问性、proxy/production。仍依赖已保留当前用户名，不重建手工损坏用户名历史。多查询列表/详情非跨请求统一快照，不证明容量/延迟或关闭SSO-04终态/恢复/吞吐。

S6新生成/旧兼容和D1指定补丁随后验证，见[恢复](recovery-credential-entropy.md)/§3.12与[Security](spring-security-consent-patch.md)/§3.13；A1/A2随后直连Servlet与最新419/176见[HTTP记录](servlet-path-security.md)/§3.14。上方273/176保留S5。完整扫描/B1消费版本/productionproxyTLSbrowser独立，删除后管理员详情回归已守护生命周期读取边界。未提交/托管发布/部署。
