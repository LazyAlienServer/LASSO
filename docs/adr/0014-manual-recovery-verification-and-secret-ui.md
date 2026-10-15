# ADR-0014: Manual Recovery Verification And One-Time Secret UI

- Status: accepted by the user during P04, 2026-10-07
- [中文](../zh-CN/adr/0014-manual-recovery-verification-and-secret-ui.md)

## Decision

Use the existing QQ group for contact, not as an identity credential. Before issuing an ordinary user's manual recovery credential, the SSO administrator must verify at least two independent facts against existing trusted records and record the target immutable UUID, evidence conclusions/references, operator, reason and outcome. Public facts, group membership, a lone screenshot or IP address do not suffice. If verification is inconclusive, do not issue. The procedure does not introduce QQ login/binding, MFA, email or two-person approval.

Operators never collect the user's password, recovery codes, OAuth Tokens or complete Cookies. Manual credential issuance uses current administrator password evidence, a reason and existing audit/one-time/idempotency behavior. Deliver privately through the verified established channel; failed/lost delivery requires reissue, which invalidates the previous unused credential. No plaintext is retained in the case log. Administrators recover through their own offline codes or host maintenance, not the ordinary manual credential endpoint.

One-time credential/client-secret dialogs must erase plaintext on save/close/Escape/pagehide. Registration/rotated code panels clear plaintext on pagehide; unsaved output warns before navigation and requires a save acknowledgement where provided. Browser clipboard/downloads are user-controlled; the service cannot promise to erase external copies. Current password inputs must not remain populated in closed dialogs. Idempotent results without plaintext stay in a visible recovery/error state rather than a closed dialog.

## Consequences

Human verification/custody is operator-owned; UI tests do not establish a claimant's identity. Store minimal case references privately. Deletion disputes use bounded hold/release/public summary and owner cancellation, not SQL resurrection or silent cancellation. Current procedure: [administrator guide](../en/administrators.md).
