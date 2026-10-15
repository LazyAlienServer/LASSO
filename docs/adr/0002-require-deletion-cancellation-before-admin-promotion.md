# Require deletion cancellation before SSO administrator promotion

Status: accepted product decision, confirmed on 2026-10-02. S4 implemented and PostgreSQL/command-fixture verified on 2026-10-04; interactive process and production acceptance remain pending.

Chinese counterpart: [提升管理员前必须取消有效注销申请](../zh-CN/adr/0002-require-deletion-cancellation-before-admin-promotion.md).

## Decision

An existing account with an effective `PENDING` or `HELD` deletion request cannot be promoted to the SSO administrator. The account owner must first cancel deletion through the existing authenticated cancellation flow; the host operator then retries the administrator bootstrap command.

A rejected promotion must not change administrator status, password credentials, deletion state or existing security state. The command explains that deletion must be cancelled first and exits unsuccessfully. Promotion never implicitly cancels deletion or releases a hold.

## Considered Options

- Automatically cancel deletion during promotion: rejected because a host-side governance action would silently override the account owner's deletion request.
- Promote first and let the deletion job cancel later: rejected because the deletion restriction and forced password-change gate can block each other before the job runs or the temporary password expires.

## Consequences

- Check the target account's current lifecycle and effective deletion request in the promotion transaction before changing role or credentials.
- Serialize promotion with deletion request, cancellation and execution under a consistent lock order. If deletion request commits first, promotion is rejected; if promotion commits first, the existing administrator-deletion prohibition rejects a later deletion request.
- Terminal deletion-request history does not block an otherwise eligible account. Frozen or permanently deleted accounts remain ineligible under the existing account-state requirements.
- Acceptance must cover `PENDING`, `HELD`, no effective request, cancelled history and controlled concurrent request/promotion outcomes against PostgreSQL.
- The original decision established S4's behavior without authorizing its implementation as part of the five-P1 repair scope. The user subsequently requested S4 execution after S3; that implementation is recorded below. Business code and production deployment remain outside this decision.

## Implementation evidence — 2026-10-04

- `AdminBootstrapService.promoteExistingUser` retains the maintenance advisory lock, locks the ACTIVE target user, then locks/checks that user's PENDING/HELD requests before touching username, password, role or security state. This follows S3's user-first ordering.
- Effective status, not deadline, is the promotion gate: an overdue PENDING or expired-but-still-HELD request still blocks. Releasing a hold leaves PENDING and is not cancellation. Cancelled history does not block an otherwise eligible ACTIVE account; a newer effective request does.
- Refusal uses `DELETION_CANCELLATION_REQUIRED`. The command explains that the account owner must cancel first and the operator must retry, prints no success result and rethrows the failure. Console tests verify both password buffers are cleared; they do not establish a real shell process exit code or hidden-input terminal behavior.
- Full-row snapshots verify refusal changes neither role/hash/credential version nor deletion request, eligibility version, recovery state, sessions, authorizations, existing audit/security events or outbox. Successful retry preserves cancelled request history and retains existing security invalidation/audit behavior.
- Controlled PostgreSQL request/promotion/cancellation/execution/freeze interleavings verify the lock-bound outcomes, and a real SQL audit failure verifies rollback of earlier promotion mutations. Concurrent eligible maintenance attempts still produce one administrator.

At this decision's verification stage, promotion database/command tests and related suites passed. Original commands/counts/limits: [historical record](https://github.com/LazyAlienServer/LASSO/blob/8390243/docs/implementation/admin-promotion-deletion.md). No schema, dependency, identity factor or public promotion endpoint was added.
