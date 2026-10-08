# Manual Recovery, Credential Custody And Deletion Disputes

[中文](../zh-CN/operations/manual-recovery-and-lifecycle.md) · [ADR-0014](../adr/0014-manual-recovery-verification-and-secret-ui.md)

## Ordinary User Manual Recovery

1. **Open an access-controlled case.** Contact via the existing QQ group, then move to the established private channel. Resolve the exact case-sensitive current username to the immutable UUID in SSO management. Group membership or a new chat claiming a name does not prove ownership. Do not ask for passwords, recovery codes, Cookie/Token captures or unrelated identity documents.
2. **Verify two independent bases.** Corroborate against existing records you or the relevant business operator can actually check: account historical username/creation details, private historical community interactions, separately verified LASLOG/Minecraft business facts, or a previously established offline identity. Two repetitions/screenshots of the same source are one basis. Public names/UUID/game skin/IP alone are not sufficient. Record which trusted records were consulted and the outcome, without copying confidential business data into SSO. A Minecraft UUID is not the human principal; only the consumer confirms its historical association.
3. **Decide and record.** Both bases must substantiate the same target; unresolved contradictions or insufficient records mean NOT_VERIFIED/no issuance. Suspected compromise/deletion dispute goes to deliberate investigation; no invented evidence, login bypass or account unfreeze solely because a caller requests recovery. The operator must identify scope and preserve existing audit.
4. **Issue through `/admin/users`.** Check exact username+UUID in confirmation, enter a non-secret case reference/reason and current administrator password. The server requires current admin/session/Origin/CSRF and recent password. Ordinary ACTIVE non-admin targets only. The credential is one-use, expires30 minutes, and appears once. Record request/audit/credential row reference and expiry, never the plaintext. Administrator cannot set/view the user's password or impersonate them.
5. **Deliver privately and complete.** Deliver only to the independently verified established channel. The user uses `/password-recovery`, sets their own new password and logs in normally; no automatic login. Verify outcome through correlated audit/state, not by asking for the new password. Reissue after uncertain/lost delivery invalidates old unused credentials. A used/expired code is never restored; no plaintext is recoverable from audit/database. After closing a secret dialog its DOM value is cleared, but the recipient must separately protect/delete copies and clipboard history.

## Case Record Template (No Secrets)

```text
Case reference / opened-at / environment:
Target current username / immutable UUID:
Contact channel reference (restricted, not public chat transcript):
Evidence A: category / trusted record reference / verifier / conclusion:
Evidence B: independent category / record reference / verifier / conclusion:
Contradictions and resolution:
Decision: VERIFIED_ISSUE / NOT_VERIFIED / INVESTIGATION / DELIVERY_UNCERTAIN
SSO operator / non-secret reason:
Issue requestId / audit or credential-row reference / expiry:
Delivery channel reference / delivered-at / acknowledgement result:
Outcome: used / expired / reissued / refused / pending investigation
Completion audit/requestId / completed-at:
Follow-up and access-controlled record owner:
```

Keep evidence references and minimum conclusions; passwords, codes, keys, Tokens, full Cookies and raw sensitive exports are prohibited in this record. Case labels are not authentication or automatic software approval. Set operational access/retention owners before launch; this template does not invent a legal retention period.

## SSO Administrator Recovery And Custody

- Generate the administrator's recovery batch after first password change/relogin. Confirm ten codes, then save to an access-controlled encrypted password manager or sealed offline medium independent of the host/database. Do not store them in source, deploy environment files, screenshots, public QQ or plaintext backups alongside the database.
- Verify a readable backup copy without transmitting plaintext to support; record custody location/owner and review date only. Mark the active batch by non-secret generation/date. Rotation invalidates the previous batch; update custody and remove obsolete copies. A download success or automation check is not proof of real operator custody.
- Recovery preference: administrator's own unused recovery code, then authorized host maintenance if codes are lost. Ordinary manual credential issuance is forbidden for administrators. Maintenance uses the exact server JAR/hash, target DB/config, production non-Web mode, hidden real console input and stop/drain of writers. Follow the current [server guide](../../las-sso-server/README.md) and [restore barrier](restore-epoch-barrier.md).
- Check target UUID, one-administrator state and audit/result before repeating an uncertain reset. Reset revokes old password/session/authorization/recovery evidence. No web backdoor exists if host/DB/custody are all lost.

## Deletion Dispute And Administrator Appointment

1. Resolve exact UUID/request ID and current PENDING/HELD/CANCELLED/EXECUTED state. Record dispute case, factual reason and accountable operator. SSO lifecycle is distinct from business bans/content ownership; consumers independently handle their historical content.
2. If investigation legitimately needs time, use `/admin/deletion-requests` hold with a reviewed expiry (at most90 days), private auditable reason and a separate user-visible summary. Summary must not expose internal allegations or unrelated data. Held users remain ineligible but can cancel with their own current password. Do not treat hold as an indefinite freeze or remove the owner cancellation path.
3. Review by expiry; release through the supported admin operation when resolved. Preserve/recheck scheduled execution time: an already-due released request can execute on the next job cycle. Inform the user accurately; release does not reset the original30-day wait. Do not promise recovery after EXECUTED/DELETED.
4. Only the owner cancels pending/held deletion through `/account/delete` with password evidence. Before offline promotion of an existing user, they must cancel; bootstrap refuses effective PENDING/HELD atomically. Promotion does not silently cancel/release. Do not manually update `is_sso_admin` or deletion rows in production. Deleted UUIDs are not reused/resurrected.

```text
Dispute case / target UUID / deletion request ID / observed status:
Private evidence references / reason / operator:
Hold expiry / public summary / hold requestId/audit:
Review result / release-or-owner-cancel requestId/audit / time:
Scheduled execution time reviewed / user communication reference:
Final status and consumer-side follow-up:
```

Software gates and fixture browser tests are verified separately from actual operator training, claimant verification, offline custody and dispute execution. Assign these owners and rehearse with non-production data before public launch.
