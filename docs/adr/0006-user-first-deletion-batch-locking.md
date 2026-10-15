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

Selected user locks remain until batch commit. A later failure rolls back earlier valid work; sustained contention can delay skipped candidates, so target capacity/starvation needs acceptance. This fixes the identified deletion user/request order, not every possible lock cycle. Subsequent promotion/tombstone/recovery/dependency repairs are preserved in [historical implementation records](https://github.com/LazyAlienServer/LASSO/tree/8390243/docs/implementation).

No schema, factor, authorization, retention, dependency or deployment rule changed. Thirty-day delay, ninety-day max hold, cancellation/admin protection and audit/eligibility remain. Original PostgreSQL evidence: [deletion concurrency](https://github.com/LazyAlienServer/LASSO/blob/8390243/docs/implementation/deletion-concurrency.md).
