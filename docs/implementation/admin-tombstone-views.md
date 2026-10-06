# Administrator views of deleted identity tombstones

- Status: S5 repaired and PostgreSQL/MockMvc/template verified on 2026-10-04; live browser/deployment acceptance pending.
- Chinese counterpart: [管理员查看已注销身份墓碑](../zh-CN/implementation/admin-tombstone-views.md).
- Tracking: [status map §3.11](status-issues-and-plan-2026-10-02.md).

## Contract and implementation

Deletion removes password/recovery credentials and personal profile data while retaining the UUID identity, reserved username history and deletion/audit evidence. A retained `DELETED` user must remain inspectable through existing administrator list/detail routes; absence of a password row does not mean absence of the identity.

`AdminUserManagementService.user` now uses a `LEFT JOIN` for `password_credentials`, retaining the existing users/current-username query and DTO fields. The missing credential row yields actual nulls for `credentialState`, `passwordChangedAt` and `lockedUntil`. No synthetic ACTIVE/DISABLED credential state or zero timestamp is introduced. Existing credential states and timestamps still map unchanged. This also makes an explicitly inconsistent nonterminal identity with missing credentials inspectable; it does not repair credentials or authorize login/service access.

| Surface | Deleted identity outcome |
| --- | --- |
| `GET /api/v1/admin/users` | Remains in existing mixed/DELETED-filtered cursor pages |
| `GET /api/v1/admin/users/{userId}` | 200, `Cache-Control: no-store`, status DELETED, explicit null credential fields |
| `/admin/users` auto/explicit selection | Actual Thymeleaf rendering succeeds; 凭据状态 = 无凭据, 密码更新/锁定到期 = 不适用 |
| Unknown UUID detail | Existing 404 `ADMIN_TARGET_NOT_FOUND` |
| Deleted-account management writes | Existing 409 `ACCOUNT_STATE_CONFLICT`; no freeze/unfreeze/revoke/recovery action buttons |

The deleted display name remains `已注销用户`. Known normal credentials with no lock still show `未锁定`; absent credentials do not. The DTO exposes no password hash or recovery code. Permissions, short administrator session, CSRF/origin/recent-password write checks and no-store detail responses remain on their existing paths. Read methods do not create credentials, update eligibility, cancel deletion, write success audits or enable account recovery. No endpoint, schema, dependency, constructor/record field or retention policy was added.

## Feedback loop and evidence

The initial minimal actual deletion/service regression at about 20:31–20:32 Asia/Shanghai failed with `ADMIN_TARGET_NOT_FOUND`, although the same identity was present in the list and its password row was gone. The expanded three-case run at about 20:35 had three failures: service not-found, detail API 404 and auto-selected page 404. Changing only the join made service/API pass; the page still failed on blank/incorrect absent-value presentation. The three cases passed after explicit template fallbacks at about 20:37. These distinguish query omission from missing username and nullable mapping/rendering. Earlier XML was overwritten by later runs; the commands and phase record preserve the red/green history.

`AdminUserTombstoneIntegrationTest` adds **18** cases: real executed deletion/list/detail; JSON null presence/no-store/no secret fields; actual auto/explicit/filtered Thymeleaf selection; unknown UUID; repeated-read full-row snapshots; four rejected terminal writes and target-state snapshots; ordinary/anonymous read refusal; three existing credential states and lock/password timestamps; frozen view/unfreeze button; mixed/DELETED pagination; inconsistent ACTIVE-without-credentials fixture remains denied; concurrent committed read during uncommitted deletion sees the prior row and after commit the tombstone.

Targets are created by registration and removed by the real deletion service after an explicit due-time fixture update. The administrator role/authentication/Servlet session and recent-password timestamp for write-gate tests are fixtures; they are not browser/bootstrap/password-evidence acceptance. The concurrent read runs on another actual PostgreSQL transaction with a bounded Future wait after deletion mutations, before commit; it verifies that controlled snapshot outcome, not all list/detail interleavings or multi-instance contention. Full snapshots cover target identity/usernames/credentials/deletion/recovery/sessions/authorizations/security events/audit/outbox; actor session metadata and every auxiliary table are not claimed unchanged.

Commands from `las-sso-server/`, Java 21 with Docker available:

```powershell
.\gradlew.bat :test --tests "org.las.sso.admin.AdminUserTombstoneIntegrationTest" --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.admin.AdminUserTombstoneIntegrationTest" --tests "org.las.sso.account.DeletionRequestServiceTest" --tests "org.las.sso.account.DeletionConcurrencyIntegrationTest" --tests "org.las.sso.maintenance.AdminPromotionDeletionIntegrationTest" --tests "org.las.sso.admin.AdminUsersTemplateContractTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.config.ServiceAccessEligibilityIntegrationTest" --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
```

Focused run at about 20:46–20:47: **138 = 18 + 10 + 24 + 25 + 2 + 53 + 6**, zero failures/errors/skips. Full forced run at about 20:48–20:50: **273 server / 176 starter**, zero failures/errors/skips; 12 actionable tasks executed, reference BFF recompiled and `test NO-SOURCE`. The final 18-case XML timestamp is `2026-10-04T12:48:55.549Z`; PostgreSQL 18.4 Testcontainers actually ran. S5 adds 18 cases over S4's historical 255 server count.

## Remaining acceptance

MockMvc executes actual Spring security/MVC/JSON serialization/Thymeleaf and PostgreSQL services, not a live TCP Servlet container, browser JavaScript, accessibility, proxy or production deployment. This repair does not remove the dependency on retained current username rows or reconstruct manually corrupted username history. It does not make the multi-query list/detail page one cross-request snapshot, prove latency/capacity or complete SSO-04 terminal/restore/throughput delivery.

S6 new generation/old compatibility and D1's named patch were subsequently verified in the [recovery](recovery-credential-entropy.md)/§3.12 and [Security](spring-security-consent-patch.md)/§3.13 records. A1/A2 later gained direct Servlet evidence/latest 419/176 in the [HTTP record](servlet-path-security.md)/§3.14. The 273/176 counts above preserve S5. Full scanning, full B1/consumer/version and production proxy/TLS/browser remain separate. The delete-then-admin-detail regression now guards that lifecycle/read boundary. Changes remain uncommitted; no hosted release/deployment occurred.
