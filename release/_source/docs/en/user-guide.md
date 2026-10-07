# User And Administrator Guide

[Index](README.md) · [中文](../zh-CN/user-guide.md)

## Account Users

Use only the operator's canonical HTTPS passport site. `/register` creates username/password identity and shows recovery codes once; password minimum12 characters, login name3–24 letters/digits/underscore, case-sensitive. Save codes in a private password manager/offline custody before leaving. The product has no email/MFA requirement. Never share passwords, codes or recovery output in support chat.

`/login` signs into the SSO passport. Risk controls may require CAPTCHA or temporary wait; repeated retries can extend symptoms. Successful business-site entry uses that site's registered OIDC flow, not shared passwords. Password-change-required identity must finish the required change before ordinary authorization.

| Page | Operation and effect |
| --- | --- |
| `/account` | Read stable ID/profile; rename login name without changing UUID/business mapping. Historical names remain reserved for their owner;90-day rename interval |
| `/account/sessions` | Inspect/revoke device sessions. Revocation/global logout propagates to registered consumers; current-site logout does not end the main SSO Session |
| `/account/recovery-codes` | View remaining count, rotate one-time codes after recent-password confirmation; old batch immediately invalid. Save new output once |
| `/account/security` | Redirects to password change within recovery/security page; password change invalidates old login/authorization and recovery-code state as indicated by the UI |
| `/account/authorizations` | Review/revoke client authorizations; new authorization may require another consent |
| `/account/delete` | Request30-day deletion wait with recent password/confirmation; sessions revoked and service access denied. Cancel during waiting/held state; execution is irreversible |
| `/password-recovery` | Use username plus unused recovery code or operator-issued manual credential, then new password. Used code consumed; other unused codes remain valid after ordinary recovery |

Browser recent-password checks are15 minutes bound to the current SSO/browser Session. Reenter when asked; a fresh Token from refresh is not password reentry. Deletion does not promise automatic removal of business-site historical content. Suspicious loss of recovery codes: rotate after secure login. No usable code: contact the existing QQ group; operators must verify identity and audit any one-time manual credential. Contacting the group alone is not sufficient recovery proof.

## Administrators

SSO administrator controls identity service only, not business-site admin roles. Initial appointment/reset is offline host maintenance. Log in, complete required password change, and use recent password plus an auditable reason for guarded writes.

- `/admin/users`: search by stable UUID/profile, inspect lifecycle/session/credential/deletion facts; freeze/unfreeze, session revoke, approved manual recovery and supported administrator handover. Verify the selected target before confirmation. Lifecycle restrictions affect all consumers; do not equate freeze with Minecraft-only bans. Last-admin/transition guards must not be bypassed with SQL.
- `/admin/clients`: create independent first-party/reviewed client per site/environment, exact URIs/scopes/audience and Back-channel endpoint; copy one-time Secret privately, review disable/enable/rotation consequences. Mutations use guarded browser APIs; Secret rotation has idempotency support, not permission to log/expose the value.
- `/admin/deletion-requests`: inspect pending/held/history; hold/release requires reason. Held deletion remains ineligible until released/cancelled; do not manually delete identity rows.
- `/admin/audit`: filter audit/security records by time/action/target and retain minimum sanitized incident evidence. Public support must not receive raw audit exports containing personal data.

One-time outputs are not recoverable by simply reopening a page. If an operation fails, retain requestId/time/category and consult [troubleshooting](troubleshooting.md); do not repeatedly issue sensitive operations without checking their committed state.
