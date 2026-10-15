# User operations: step-by-step

[Index](README.md) · [中文](../zh-CN/users.md) · [Administrator](administrators.md) · [API](api.md) · [Troubleshooting](troubleshooting.md)

## Before starting

**Location: browser at the operator's canonical SSO HTTPS site.** Paths below belong to SSO, not LASLOG. The current UI uses Chinese button labels; their original text is retained here for identification. Verify host/certificate/current account; enter secrets only in a private session.

| Task | Procedure |
| --- | --- |
| Register/save initial codes | U01 |
| Login/first temporary-password change | U02–U03 |
| Edit profile/change login name | U04–U05 |
| Password change/code rotation/recovery | U06–U08 |
| Device revocation/logout/application grants | U09–U10 |
| Request/cancel deletion | U11 |

Password:12–128 Unicode code points and ≤512 UTF-8 bytes, not username or bundled common passwords. Username is case-sensitive,3–24 letters/digits/underscore. SSO administrator is not a business administrator; email/MFA is not required.

## U01 Register and save first recovery codes

**Prerequisites:** An available name and private password manager/offline storage.

1. Visit `/register`; supply username/display name/password/confirmation.
2. Complete CAPTCHA, tick “我会保存下一步显示的一次性恢复码” (I will save the one-time recovery codes), then select “创建通行证” once and wait. Avoid refresh/double submission.
3. In the one-time result choose “复制全部” (copy all) or “下载文本” (download text). Check the saved private copy is readable and not in shared/automatically published storage.
4. Mark the saved acknowledgement, then select “前往登录”. Registration does not itself authenticate you.

**Expected:** Account created, ten initial codes, login page. **Failure:** Choose another occupied name; wait on429. After a lost response check creation before registering repeatedly with new keys. If created but codes were lost, log in with password and rotate via U07; replay cannot redisplay old codes.

## U02 Login and risk prompts

**Prerequisites:** Current name/password; use one hostname throughout.

1. Open `/login`, enter the exact current username, not UUID or historical name.
2. Enter password; remember-device is appropriate only for a private device. It cannot extend administrator policy.
3. Submit. If CAPTCHA is requested, complete it and resubmit there;429 requires waiting rather than automatic retry.
4. Account-site login reaches `/account`; a business-initiated flow returns through that site's validated callback. Required-change page goes to U03.

**Expected:** Your own current name and stable passport UUID. **Failure:** Unknown-user/wrong-password prompts are intentionally indistinguishable; check case/password-manager entry, then U08/manual verification. Login loops require HTTPS/cookie/host inspection, not switching to HTTP.

## U03 Change a temporary administrator password

**Prerequisites:** Bootstrap completed and privately delivered temporary credential still within24hours. This is initial activation, not routine change.

1. Log in with delivered username/temporary password.
2. At `/password-change-required` enter that temporary password/new password/confirmation, then “更新密码”. Do not try administration before activation.
3. After “密码已经更新”, choose “重新登录” and establish a new login with the new password.
4. Use U07 for fresh recovery codes, verify required admin access, remove temporary-password copies.

**Expected:** Old temporary password/session invalid; unique administrator retained. **Failure:** Correct mismatched entries; expired credential goes through authorized [offline reset](operations.md), not database hash edits.

## U04 Edit display name

**Prerequisites:** Normal active login, not required-change/deletion restricted.

1. At `/account`, select “编辑资料”.
2. Change the nonblank trimmed display name,≤64 code points, select “保存”. The page preserves the existing avatar; it is not an upload tool.
3. Check the reloaded name and unchanged UUID.

**Expected:** Display name changed, username/identity unchanged. **Failure:** Check empty/length400, renew expired login; after network uncertainty read current value before repeating.

## U05 Change login username

**Prerequisites:** Normal login/current password;90-day cooldown since last successful change satisfied.

1. At `/account`, select “修改用户名”.
2. Enter new name/current password, verify exact case/spelling, select “确认修改”.
3. Check new name and unchanged UUID after reload; update password-manager username.
4. Use the new name for future login. Historical names remain reserved; **the current endpoint rejects every occupied historical name, including your own old name**. UI text about owner reuse does not establish that implemented feature.

**Expected:** Identity stable and server-managed new cooldown. **Failure:** USERNAME_UNAVAILABLE needs another name; USERNAME_CHANGE_COOLDOWN waits until availableAt. The supplied password check is not substituted by generic recent evidence.

## U06 Change a normal password

**Prerequisites:** Active normal login/current password; understand old sessions/grants/recovery material end.

1. Open `/account/recovery-codes`, select “修改通行证密码”; `/account/security` leads to that area.
2. Supply current/new/confirmation, select “更新密码”.
3. On completion select “返回登录”, log in with new password, do not restore the old session.
4. Rotate/store codes via U07, remove obsolete password/code copies.

**Expected:** New password works, old login cannot continue. Consumer notification is asynchronous; its actual refusal is consumer-verified. **Failure:** Wrong current password401, reused/short/common new password rejected. Lost response requires checking fresh login outcome, not repeatedly changing passwords blindly.

## U07 Rotate recovery codes

**Prerequisites:** Normal login/private storage; acknowledge all old codes will expire.

1. Visit `/account/recovery-codes`; inspect available count/generation date. Counts do not reveal old plaintext.
2. Tick old-code invalidation acknowledgement, select “重新生成恢复码”.
3. If recent authentication is required, enter current password and select “完成近期认证”. After success **click generation again**; authentication alone does not rotate.
4. Copy/download the one-time new batch, inspect private copy, mark saved acknowledgement, then navigate away.
5. Remove obsolete batch; reopening shows status only.

**Expected:** Ten new codes, previous batch revoked. **Failure:** Copy permission denied uses download/private manual save. Lost response/left page requires deliberately generating another batch after checking state, not recovering plaintext. Use a dedicated account for restore drills rather than consuming real codes to test storage.

## U08 Recover with a code or manual credential

**Prerequisites:** Current name and an unused recovery code or independently verified manual credential valid for30minutes.

1. Open `/password-recovery`, enter current name/credential; the page recognizes regular/manual form, no old password needed.
2. Select “验证恢复码”; complete risk CAPTCHA and resubmit if required.
3. At step2 enter new password/confirmation within the10-minute recovery session, select “设置新密码”. Step1 already consumed this credential; do not leave and retry the same one.
4. Select “返回登录”, log in normally. Other unused ordinary codes remain valid; suspected batch exposure warrants U07.

**Expected:** Old password/session/grant ends, no automatic login. **Failure:** Used/expired credentials cannot be resurrected; expired recovery session needs another usable credential. No codes means [manual verification](administrators.md), never public disclosure of codes/password/Cookie. Administrator uses offline custody or authorized reset, not a normal manual-recovery credential issued to an admin.

## U09 Inspect devices, revoke and log out

**Prerequisites:** Normal login and explicitly chosen scope.

1. At `/account/sessions` identify device/last activity/expiry/current marker; UA summary alone is not proof of identity.
2. For another device select its revoke button, check confirmation, confirm; successful row disappears and that device needs login.
3. Revoking the current sid calls **all-user SSO session/authorization logout**, not just this tab.
4. All-device button also confirms user-wide logout and returns to login.
5. Navigation “退出通行证” is likewise user-wide SSO logout. A business site's own logout should end only its local login and preserve SSO.

**Expected:** SSO state ends and applicable notifications queue. Success is not evidence that every unavailable/offline BFF received it. **Failure:**404 means recheck expired/already ended row; uncertain request checks state, never reinstates logged-out identity on remote failure.

## U10 Revoke one application's grants

**Prerequisites:** Active SSO login; operation affects all your authorizations for that client, not one family/browser only.

1. Visit `/account/authorizations`, verify name/client ID/scopes/recent-use label.
2. Select “撤销授权”, check target in dialog, select “确认撤销”.
3. Successful list removes that client; other clients and business content remain independent.
4. Next use starts a new SSO flow; a partner may request consent again. Notifications are asynchronous.

**Expected:** Your client's authorization/consent removed. **Failure:** Read list after timeout before retry. If a business session remains usable, its owner checks freshness/Back-channel; do not send Token captures to support.

## U11 Request, inspect or cancel deletion

**Prerequisites:** Ordinary account; administrator cannot self-delete. It is not deletion of all business-site historical content.

**Expected:** The request shows its original due time and restricted state. Cancellation restores normal eligibility without reviving old grants; executed deletion remains final.

1. At `/account/delete` read30-day/irreversible effects, enter current password, select “申请注销通行证”.
2. Original login ends; to inspect/cancel log in again into restricted deletion page, verify requestedAt/executeAfter.
3. PENDING/HELD do not permit normal account/business entry. A hold's public summary is not eligibility restoration.
4. Enter current password and “取消注销申请”; reload verifies cancellation/normal state. Old grants are not resurrected; business login restarts.
5. Executed deletion leaves retained identity/name constraints and cannot be undone by page/unfreeze. Admin hold release preserves original due time; an already due request may execute soon.

**Failure:** Read state after lost response rather than inventing new-key requests. Executed cannot cancel; fix password/CSRF/current login, not SQL status. Record time/requestId/state, not secrets.

## Unified uncertain-result handling

1. Stop repeated submission, note operation/UTC time/stable error/requestId.
2. Reads may refresh; writes first query state/audit, then decide on an explicit new action.
3. Lost one-time output uses generation/rotation/reissue; never retrieve raw old secrets from logs/DB.
4. Share only sanitized metadata; authentication/idempotency/error rules are in [API](api.md).
