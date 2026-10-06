# Restore epoch barrier procedure

- [Chinese](../zh-CN/operations/restore-epoch-barrier.md), [ADR-0010](../adr/0010-executable-restore-epoch-barrier.md), [epoch contract](../implementation/service-access-epoch-contract.md).

This is an operator-only maintenance command. It is not an HTTP endpoint and must not run while the SSO web process or scheduled jobs can write the restored database.

## Procedure

1. Drain the public proxy and stop SSO/BFF writers. Keep the backup and key custody records separate.
2. Restore the PostgreSQL snapshot into an isolated target database. Verify PostgreSQL/schema versions and record the target connection identity.
3. Verify permanent facts, client registrations, current administrator, audit data and the restored epoch:

```sql
SELECT security_epoch, updated_at, change_reason
FROM system_security_state
WHERE id = 1;
```

4. Build/select the exact server JAR and SHA-256. Do not use a directory wildcard or a different delivery.
5. Run the command with the expected restored epoch; the script requires an interactive confirmation:

```powershell
.\scripts\rotate-security-epoch-after-restore.ps1 `
  -Profile production `
  -JarPath $ServerJar `
  -ExpectedSha256 $ServerJarSha256 `
  -ExpectedEpoch $RestoredEpoch `
  -Reason "POSTGRES_RESTORE_2026-10-06"
```

The script forces `spring.main.web-application-type=none` and `las.sso.scheduling.enabled=false`. The command refuses an epoch mismatch, blank/missing epoch, noninteractive console, web mode or active scheduling. It prints only old/new epoch and rotation time after successful commit.

6. Verify the target database:

```sql
SELECT security_epoch FROM system_security_state WHERE id = 1;
SELECT count(*) FROM user_sessions WHERE status = 'ACTIVE';
SELECT count(*) FROM spring_session;
SELECT count(*) FROM oauth2_authorization;
SELECT count(*) FROM oauth2_refresh_token_history WHERE status <> 'REVOKED';
SELECT count(*) FROM oidc_password_challenges;
SELECT count(*) FROM rate_limit_buckets;
SELECT count(*) FROM captcha_challenges;
SELECT count(*) FROM recovery_sessions;
SELECT count(*) FROM operation_idempotency;
SELECT count(*) FROM outbox_events WHERE processed_at IS NULL AND dead_lettered_at IS NULL;
```

The epoch must differ from the restored value. Active SSO/HTTP sessions, Authorization and unrevoked refresh history must be zero. Pending outbox work may remain, but stale claims must be null. Users, credentials, registered clients, consent, audit and deletion facts remain subject to the restore review.

7. Separately install the new persistent signing key/kid and update BFF JWKS/cache policy. Do not reopen traffic with the old signing key. A same-key recovery policy would require a separately approved design and is not supported by this V1 procedure.
8. Each relying party must clear local sessions/qualification evidence, establish its current epoch through a fresh reconciliation barrier and reject delayed old-epoch responses. Do not choose an epoch by lexical, UUID or time ordering.
9. Start SSO with scheduling enabled, verify readiness/Discovery/JWKS, then start BFFs. Exercise new login, UserInfo, refresh rejection for a pre-restore token, current/global logout and pending outbox delivery before reopening the proxy.

## Rollback and limits

Do not rerun the command with the old epoch. If the barrier fails, keep traffic drained, inspect the transaction failure and restore the target or correct the operational issue. The command is transactional for the SSO database, but it cannot atomically change external BFF databases, caches, signing-key distribution or business systems. A second restore drill must use a fresh target or an explicitly recorded current epoch.

The 2026-10-06 isolated drill used two PostgreSQL databases, real `pg_dump`/`pg_restore`, two persistent PKCS#12 keys, the real maintenance PowerShell script and a Windows PTY. It verified old cookies/code/AT/RT rejection, new key/kid, new epoch with lower restored stateVersion, permanent UUID/password/admin/client/recovery facts, fresh login, generation-barrier fixture rejection, processed restored outbox/near-due deletion fixture and global Back-channel logout. The transaction suite separately verifies consent/audit/transient/refresh history/outbox claim preservation. It did not use a production backup/key, production proxy or a consumer's real cache; post-snapshot account/password/recovery-credential-use changes are not reconstructed.
