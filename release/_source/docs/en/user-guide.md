# User and administrator tasks

[Index](README.md) · [中文](../zh-CN/user-guide.md) · [Failure guide](troubleshooting.md)

## 1. Registration and login

| Step | Action | Result |
| --- | --- | --- |
| 1 | Official HTTPS `/register` | Case-sensitive 3–24-character name; letters/digits/underscore; password ≥12 characters |
| 2 | Save one-time recovery codes | Private manager/offline custody, not support chat |
| 3 | `/login` | Complete requested CAPTCHA/wait |
| 4 | Required password change | Genuine temporary-password change and relogin |

Business sites use registered OIDC, not shared passwords. No email/MFA release requirement.

## 2. Account reference

| Page | Task | Effect |
| --- | --- | --- |
| `/account` | Profile, stable UUID and rename | Same UUID; 90-day rename interval, historical names reserved |
| `/account/sessions` | Device revocation | Revocation/global logout propagates; local business logout does not end main SSO |
| `/account/recovery-codes` | Count/rotate after recent password | Old batch invalid, new codes shown once |
| `/account/security` | Security/recovery password change | UI states old-session/authorization/recovery invalidation |
| `/account/authorizations` | Review/revoke | Reauthorization may need consent |
| `/account/delete` | Recent-password-confirmed deletion | 30-day wait, revoked sessions and eligibility denial; pending/held can be cancelled |
| `/password-recovery` | Username+unused code/manual credential | Used code consumed; other ordinary unused codes survive recovery |

## 3. Recovery and one-time output

- Dialog close/save/Escape/navigation clears plaintext; reopening cannot recover it.
- Downloads/clipboard/device history remain user custody. Remove replaced batches.
- No usable code: contact the existing QQ channel, then complete identity verification.
- Suspected exposure: rotate after secure login, no password/code/Token/Cookie to support.
- Recent password lasts 15 minutes in the originating browser login; refresh is not reentry.

## 4. Administrator pages

SSO administration grants no business-admin role. Bootstrap forces first change; offline reset creates ACTIVE password. See [operations](operations.md).

| Page | Task | Verify |
| --- | --- | --- |
| `/admin/users` | UUID, lifecycle, session, credential, freeze, recovery and handover | Target, recent password and reason; retain last-admin guards |
| `/admin/clients` | Independent type/URI/scope/audience/Secret | Per site/environment; one-time private secret |
| `/admin/deletion-requests` | Pending/held/history/hold/release | Reason/expiry; no manual identity deletion |
| `/admin/audit` | Filter time/action/target | Minimum sanitized evidence, no public raw personal data |

## 5. Manual recovery case

| Order | Action |
| --- | --- |
| 1 | Stable UUID/current name, verified private channel |
| 2 | At least two independent bases against trusted existing records |
| 3 | References/conclusions/operator/reason/request-audit/expiry |
| 4 | Private one-time delivery/outcome, no plaintext record |

Group membership/public facts/single screenshot/IP insufficient; unverifiable means no issue. Administrators use own offline codes/host maintenance, not ordinary manual credentials. Reissue revokes previous unused manual credential.

## 6. Deletion dispute

| State/action | Meaning |
| --- | --- |
| Pending/held | Owner can cancel with password, still ineligible |
| Hold | RequestID/reason/bounded expiry/review/non-sensitive public summary |
| Release | Original schedule, already-due work may execute next cycle |
| Executed/deleted | No resurrection or automatic business-history deletion promise |

On sensitive failure inspect committed state first, retain requestId/time/category, do not blindly repeat issuance.
