# Administrator operations: step-by-step

[Index](README.md) · [中文](../zh-CN/administrator-guide.md) · [User procedures](user-guide.md) · [API workflows](api-operations.md) · [Offline maintenance](operations.md)

## Shared prerequisites and navigation

**Location: canonical SSO HTTPS browser; actor: current sole SSO administrator.** Finish bootstrap password change and log in afresh. Write dialogs take **the administrator's own password**, not the target user's; UI reauthenticates before the mutation. Direct APIs require their cookie/CSRF/origin steps. Reasons contain non-secret case references/conclusions.

| Operation | Procedure |
| --- | --- |
| User/tombstone search, freeze/unfreeze, force logout | A01–A03 |
| Manual verification and recovery issuance | A04 |
| Create/edit/disable/enable/rotate client | A05–A08 |
| Hold/release deletion, audit/security search | A09–A10 |

Protected administrator/self targets use operation-specific refusal; unavailable sole-admin login uses [offline reset](operations.md). SSO role does not grant business/game authority. One-time output clears on close/Escape/pagehide; audit is not a secret-retrieval tool.

## A01 Inspect user or deleted tombstone

**Prerequisites:** Target UUID/current name; begin with a read, not a mutation.

1. Visit `/admin/users`, search UUID/name/display name, select status and “筛选”. Prefer UUID to avoid display-name ambiguity.
2. Select left-side account; verify UUID/current name/lifecycle/admin marker.
3. Inspect credentials/session/grant counts/registration/password/lock times; preserve filters while paging.
4. Deleted filter reveals tombstones; “无凭据”/“不适用” are legitimate absence, not corruption or reactivation eligibility.

**Expected:** Accurate target and no tombstone write controls. **Failure:** Clear filters/check ID before concluding absent; never create a replacement with the same UUID.401 requires login,403 requires SSO admin rather than a business role.

## A02 Freeze or unfreeze

**Expected:** Read-back matches FROZEN/ACTIVE and the intended audit; unfreeze does not restore ended sessions or grants.

**Prerequisites:** A01 target is neither self/admin/deleted; authorized reason.

1. ACTIVE: “冻结账号”; FROZEN: “解除冻结”.
2. Read scope/target name+UUID, supply reason/current admin password, “确认执行”.
3. Reload: freeze is FROZEN with old sessions/grants ended; unfreeze is ACTIVE without restoring old login/grants.
4. Use A10 to correlate audit; launched consumers independently verify eligibility refusal. SSO success is not proof every game connection ended.

**Failure:** Reauthenticate on password/evidence failure; refresh state conflicts. Uncertain network first reads state/audit rather than repeating/broadening actions; no SQL lifecycle edits.

## A03 Force user logout

**Expected:** The target's SSO sessions/grants end, lifecycle remains unchanged, and the corresponding administrator audit exists.

**Prerequisites:** Allowed A01 target; choose all that user's SSO sessions/grants, not account freeze.

1. “强制下线”, verify UUID/all-session scope.
2. Supply reason/admin password, confirm once.
3. Reload counts; lifecycle remains as before and eligible user may log in again.
4. Check ADMIN_USER_REVOKE_SESSIONS audit; asynchronous notification failures go to [troubleshooting](troubleshooting.md), never revive old login.

**Failure:** Protected targets require offline maintenance. Delivery pending is not evidence that the SSO mutation failed.

## A04 Verify claimant, issue and deliver manual recovery

**Prerequisites:** Ordinary ACTIVE target without usable self-service recovery; independently verify identity first.

1. Receive minimal request through established channel, confirm current name/stable UUID and move delivery to a verified private channel.
2. Corroborate at least two independent bases against trusted preexisting records; store references/conclusion/actor. Public nickname/single screenshot/public data/IP alone is insufficient; failed verification means no issuance.
3. Select exact target via A01, “签发恢复凭据”, enter case reason/admin password, confirm.
4. Privately preserve/deliver the one-time credential/expiry within30minutes; select “我已保存，关闭”. Case keeps request/audit/time/channel references, not plaintext.
5. Claimant performs U08, chooses own password and logs in; verify outcome without asking for password. Other unused ordinary codes follow their existing policy.
6. Lost/expired delivery requires inspecting existing issuance, then an explicit authorized reissue which revokes old unused manual credentials. Same-key replay cannot redisplay secrets.

**Expected:** Single-use limited credential and retained issuance audit. **Failure:** Admin/frozen/deleted targets are restricted. Secret-unavailable does not mean not committed; do not paste user passwords/codes into cases.

## A05 Register an OIDC client and deliver settings

**Prerequisites:** Consumer origin/exact callback/logout/Back-channel/scope/audience/environment agreed; backend privately holds secret.

1. `/admin/clients` → “新建客户端”; optional independent clientId, name and FIRST_PARTY/REVIEWED_PARTNER. Unreviewed deployments are not first party.
2. Callback one per line, at least one; optional logout list; audience one per line, at least one; reachable Back-channel. Current create UI fixes scopes to openid profile, no arbitrary-scope selector.
3. Check exact scheme/host/port/path/case and reachability; reviewed production HTTPS, no wildcard/fragment/userinfo. Sample8088 is not LASLOG by default.
4. Reason/admin password → “创建”. Privately save/deliver clientId/one-time secret to backend, “我已保存，关闭”.
5. Reopen “详情”, verify type/URLs/scopes/audience/configVersion; secret no longer displayed. Consumer completes [first-login integration](external-integration.md); record registration/version.

**Expected:**200 creation and one secret; partner consent applies. **Failure:** Create lacks generic idempotency; read by entered ID/name after timeout before duplicating. Existing client with lost secret uses A08.

## A06 Edit client settings

**Expected:** Read-back matches the complete submitted settings and current configVersion, with the actual revocation result recorded.

**Prerequisites:** Coordinated change window/old non-secret settings/version/affected paths recorded.

1. “详情” verifies client ID, then “编辑配置”; ID immutable.
2. Edit prefilled complete set; check unchanged URLs/audiences retained. API PATCH is also full settings, not arbitrary merge.
3. Reason/admin password → “保存修改”.
4. Check configVersion/revocationTriggered; service code determines actual revocation, not a broad UI sentence assuming every change revokes.
5. Consumer installs matching config and tests new login/refresh/logout/rejected obsolete callback; users establish new grants when revoked. Correlate via A10.

**Failure:**400 checks individual URLs/scopes/audiences; read actual details/version after uncertainty. Reverting settings is a new mutation, not restoring historical version/secret.

## A07 Disable or enable client

**Expected:** Client status matches the action. Disabled authentication is refused; enabling permits new grants without restoring revoked ones.

**Prerequisites:** Exact client and authorized security/maintenance scope affecting its users.

1. Detail → “禁用客户端” or “启用客户端”.
2. Verify target, reason/admin password, confirm.
3. After disable select DISABLED filter and verify state/rejected new authentication; old grants ended.
4. After remediation enable, verify ACTIVE and perform a new login/refresh. No restoration of revoked grants/consent or rotated secret.

**Failure:** Read detail/audit, do not repeatedly toggle on timeout. Enabling is not a substitute for fixing configuration/integration failures.

## A08 Rotate client secret

**Expected:** The new secret is privately installed, old-secret authentication is refused, and users obtain new grants.

**Prerequisites:** Consumer can stop/update/restart; one active secret has no dual-secret grace period.

1. Record client ID/configVersion/consumer revision, agree window and drain consumer ingress if needed.
2. Detail → “轮换密钥”, understand old secret/all-client-grant invalidation, reason/admin password, confirm.
3. Save/deliver one-time new secret to backend and close; no frontend build/Git/command-line secret.
4. Consumer installs new secret/restarts as needed, verifies new login/refresh and isolated old-secret refusal; users log in again.
5. A10 correlates audit/version; remove obsolete copies and reopen.

**Failure:** Same-key replay acknowledges without plaintext; lost new secret needs another coordinated rotation, never old-secret restoration. Network outage must not generate repeated automatic rotations.

## A09 Hold or release deletion

**Expected:** HELD/PENDING read-back matches the action and retains the original executeAfter; a hold does not restore eligibility.

**Prerequisites:** Authorized dispute/retention basis, user UUID/request ID/original executeAfter known.

1. `/admin/deletion-requests` filter PENDING/HELD; identify exact request, not a prior historical request.
2. PENDING → “暂缓”, enter internal reason/non-sensitive public summary/future deadline≤90days/admin password.
3. Verify HELD/holdUntil/original executeAfter, schedule review. Browser local-time input is converted to ISO; check zone.
4. HELD → “释放”, check original due time/current time, reason/admin password, confirm.
5. Verify PENDING; **does not restart30days**, already due may execute next scheduled cycle. Owner may cancel PENDING/HELD via U11.

**Failure:** No key guarantee here; read state/audit before retry. EXECUTED/DELETED cannot resurrect; do not SQL-edit due time or extend holds to bypass policy.

## A10 Search audit and security events

**Prerequisites:** Administrator read access/target/event/time range; do not manufacture password evidence for a read.

1. `/admin/audit` → “管理员操作” or “安全事件”.
2. Admin filters action/targetId; security filters type/severity/userId. Choose recent1hour/24hours/7days or local timestamps, check zone, “筛选”.
3. Correlate target/actor/UTC/requestId/SUCCESS; nearby same-action records are not automatically one commit.
4. Preserve filters on next page, clear cursor for new search. Refresh-reuse events investigate same-session concurrency/exposure without deliberate Token replay.
5. Store minimal event references/conclusions; no broad personal-data export or raw authentication logs in public issues.

**Expected:** Concrete action/state linkage. **Failure:** Empty result checks kind/range/IDs, not assumes commit outcome. DB outage goes to operations; audit never retrieves missing one-time plaintext.

## Uncertain-result completion

1. Record UI category/UTC/requestId without password or output secrets.
2. Read target/configVersion/audit before classifying commit; never manufacture certainty by repeating writes.
3. Explicit reissue/rotation records its relationship to the previous attempt.
4. A 403 requires checking the actor's session/evidence/CSRF; do not borrow another user's identity. Sole-admin loss uses offline custody.
