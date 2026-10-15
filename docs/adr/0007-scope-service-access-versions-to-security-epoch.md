# Scope service-access versions to the global security epoch

Status: accepted by the user on 2026-10-05; controlled contract and 2026-10-06 isolated real backup/restore verified. Production restoration and relying-party runtime cache acceptance remain separate.

Chinese counterpart: [服务资格版本限定于全局安全代次](../zh-CN/adr/0007-scope-service-access-versions-to-security-epoch.md).

## Context

`stateVersion` is stored in the same PostgreSQL backup as users. Restoring an older backup can decrease its value. Existing disaster recovery rotates `security_epoch` and invalidates sessions/authorizations, but a service-access cache could still compare only the restored number to its previously observed version. A bare per-subject counter cannot prove global monotonicity across restoration.

## Decision

Add opaque nonblank `stateEpoch` to every authenticated service-access decision, including unknown subjects. Reuse `system_security_state.security_epoch`; no new schema, counter service or domain permission claim. Read epoch and subject facts in one SQL statement snapshot. Missing/blank global state fails unavailable, never creates ALLOWED or a synthetic unknown-generation result.

Version ordering applies only to `(issuer, subject, stateEpoch)`. The epoch is an equality namespace, not a sortable timestamp/UUID; different epochs have no ordering relation. `changedAt` is descriptive, not a recovery ordering mechanism. Unknown subject stays DENIED/PRINCIPAL_NOT_FOUND/version 0/changedAt null, with current epoch.

Recovery must generate a new epoch before reopening traffic; restore without rotation remains invalid. Relying parties fail closed on an unrecognized epoch, establish the current authoritative generation through a fresh post-recovery query/reconciliation barrier, clear old-generation caches, and reject delayed responses from the retired generation. They must not switch back and forth merely because arbitrary in-flight responses have different epoch strings. Requests/result acceptance need local generation/session correlation. Ordinary business restart/restore coordination stays owned by the relying party.

## Alternatives

- Keep bare version and assume it never decreases: rejected because backup restoration can invalidate that assumption.
- Delay the extension: not selected by the user; restoration semantics would remain incomplete.
- External non-restored monotonic version source: not selected; adds infrastructure and a separate recovery ledger beyond current scope.

## Consequences

Subsequent [ADR-0010](0010-executable-restore-epoch-barrier.md) provides the expected-epoch interactive/non-Web barrier. [Historical delivery](https://github.com/LazyAlienServer/LASSO/blob/8390243/docs/integration/development-delivery-2026-10-06-1.md) records actual dump/restore/key acceptance; generation-cache rejection there is a harness, not consumer implementation.

The response gains a seventh field and Java Decision construction gains the epoch component; strict consumers must update their DTO/validation/cache namespace. No business permission, MFA or independent eligibility event is introduced. The existing security epoch is an identifier, not a secret or authorization grant; exposing it only on the authenticated internal query does not allow bypassing signature/client checks.

Controlled tests distinguish restored lower versions/new epoch, rollback and missing/blank epoch unavailability. Original scope: [query record](https://github.com/LazyAlienServer/LASSO/blob/8390243/docs/implementation/service-access-epoch-contract.md). Consumer cache/recovery acceptance is independent; ADR-0001 retains authority/event boundaries.
