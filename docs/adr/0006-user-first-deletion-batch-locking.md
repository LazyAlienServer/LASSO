# User-first deletion locking with atomic batches

Status: explicitly confirmed by the user on 2026-10-04; implemented and PostgreSQL-tested for issue S3, production acceptance pending.

Chinese counterpart: [注销用户优先锁序与批次原子性](../zh-CN/adr/0006-user-first-deletion-batch-locking.md).

## Context

Deletion request/cancellation acquired the user first. Admin hold/release acquired the deletion request first, then updated the user's eligibility version. Expired-hold processing also acquired requests before users. A cancellation holding the user and waiting for the request could therefore oppose an admin/job holding the request and waiting for the user. Multi-user transactions can also create inverse acquisition across phases if user order is not coordinated.

## Decision

The user selected preservation of the existing batch transaction/whole-batch rollback rather than per-user commits:

- All deletion mutations acquire target `users` before their `account_deletion_requests`, followed by associated security state. Admin candidate owner lookup is nonlocking and is rechecked under user/request locks; it is not authorization.
- Batch readers select bounded candidates without locking requests. Acquire the candidate user set in PostgreSQL `ORDER BY id` order with `FOR UPDATE SKIP LOCKED`, before any request locks. Requests are then locked and their ownership/current status/deadline rechecked.
- The scheduled expired-hold and pending-execution phases preselect a union of both candidate sets and acquire its users once. After release begins, do not discover and acquire new users for execution. New eligible requests are handled in subsequent runs.
- Busy user/request candidates are skipped before mutation and remain retryable on a later invocation. Preserve scheduled advisory-lock ownership. Skipping is not completion, and counters report actual transitions.
- Keep all processed changes in the existing outer batch transaction. Failure rolls back status, credential deletion, eligibility versions, audit/security events and logout outbox across the batch, including both scheduled phases.
- Recheck hold deadline after interactive lock waits. Direct execution must match request/user, still be PENDING and be due; cancelled/held/not-due/wrong-owner requests are not deleted. Existing administrator protection remains.

## Alternatives

- Per-user transactions: not selected; they reduce lock scope but intentionally allow partial batch commits and require new coordination/count/retry semantics.
- Fix only hold/release but keep request-first expiry: rejected; the job would retain the same inverse-order cycle.
- Fix each phase separately without prelocking the union: not selected; one transaction would accumulate user locks in two potentially different orders.
- Hold request locks before identifying users: rejected; locking the request to discover its owner recreates the original order.

## Consequences

Interactive operations can wait on a same-user operation before acquiring the request. Batch operations can return zero despite due candidates because they are busy or changed; later runs must retry. Zero is not proof that backlog is empty. Scheduled selection remains at most100 expired candidates plus100 pending candidates, and at most100 executions per invocation; no claim/lease table, durable retry queue or per-user transaction replacement is added.

`executeDue` counts actual processed request transitions, including the existing administrator protective cancellation. The job counts released holds plus processed pending transitions: a released request executed in the same run contributes two. This is an intentional correction from counting stale/skipped candidates as completed, not a new HTTP API payload. Public constructor/method call signatures remain usable; due checks make the direct execution entry fail closed for invalid/stale inputs.

Locks for successfully selected users remain until batch commit. Retaining batch atomicity can make a later failure undo earlier valid work; sustained contention can delay skipped candidates, and capacity/starvation need operational acceptance. This fixes the identified deletion user/request order, not every possible application/database lock cycle. S4 gating under ADR-0002 was subsequently implemented and verified at PostgreSQL/command-fixture level; see the [promotion contract](../implementation/admin-promotion-deletion.md). S5 tombstone queries were subsequently repaired and PostgreSQL/MockMvc/template verified; see the [tombstone contract](../implementation/admin-tombstone-views.md). S6 new entropy/old compatibility was subsequently repaired/verified; see the [recovery contract](../implementation/recovery-credential-entropy.md). D1's named patch was subsequently upgraded/verified; see the [Security record](../implementation/spring-security-consent-patch.md). Live acceptance and full scanning remain independently open.

No schema, authentication factor, authorization rule, retention period, dependency or deployment changed. Thirty-day request delay, ninety-day max hold, existing cancellation/administrator protection and audit/eligibility semantics remain. See [deletion concurrency contract](../implementation/deletion-concurrency.md) for actual PostgreSQL failure/concurrency evidence and boundaries. Changes are uncommitted/unpublished/undeployed.
