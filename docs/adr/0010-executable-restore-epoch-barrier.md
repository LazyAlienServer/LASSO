# Execute the restore epoch barrier before reopening traffic

Status: implemented and isolated PostgreSQL/real `pg_dump`/`pg_restore`/persistent-key drill verified on 2026-10-06; production backup, key custody and relying-party barrier acceptance remain separate.

[Chinese](../zh-CN/adr/0010-executable-restore-epoch-barrier.md).

## Context

ADR-0007 requires a fresh opaque `security_epoch` before traffic returns after restoring an older PostgreSQL snapshot. The previous repository had the rule in the runbook and controlled restore-shape tests, but no executable maintenance barrier. Operators could accidentally run the operation against the wrong restored epoch or leave Spring Session, Authorization, refresh-family history, short-lived recovery state or stale outbox claims active.

## Decision

Add `SecurityEpochRecoveryService` and non-web command `rotate-security-epoch-after-restore`.

The command requires:

- production/restore configuration and `spring.main.web-application-type=none`;
- scheduling explicitly disabled;
- exact `--expected-epoch` read from the restored database;
- bounded `--reason`; and
- an interactive `ROTATE-EPOCH-AFTER-RESTORE` confirmation.

Inside one transaction, after locking and matching the expected current epoch, it:

1. generates a new random epoch and updates `system_security_state`;
2. revokes all active `user_sessions` and deletes JDBC Spring Session rows;
3. deletes Authorization rows, allowing the existing refresh-history delete trigger to revoke families;
4. explicitly revokes remaining refresh-family history;
5. deletes incomplete OIDC password challenges and transient rate/CAPTCHA/recovery/idempotency state;
6. preserves registered clients, user identities, credentials, audit facts, consent and pending outbox work;
7. releases stale outbox claims without deleting pending delivery; and
8. writes a security event containing old/new epoch and the bounded operator reason.

An epoch mismatch, missing/blank state or transaction failure aborts all mutations. A second invocation with the old epoch is rejected. The command does not create or rotate the persistent signing key; operators must perform the configured key/kid replacement and verifier/cache transition as a separate step before reopening traffic.

## Consequences

The operation intentionally invalidates every SSO login and Authorization after restore. It does not restore post-snapshot account facts, and it cannot know about relying-party databases or revocation ledgers outside the restored database. Consent and client registration remain so operators can perform controlled new login; pending outbox work remains so logout/deletion delivery is not silently discarded. A restored pending outbox event may be delivered again; receiver idempotency is required.

The service is SSO-owned and has no new migration. The command is an operator barrier, not an HTTP endpoint. Consumer caches/in-flight results must still execute the ADR-0007 fresh-generation barrier before traffic; this repository only emits the new epoch and invalidates SSO state. Real production credentials, backup storage, key custody, proxy drain and exact restore timing remain operational acceptance.
