# Client Secret mutation concurrency and rollback verification

- Status: S2's existing repair expanded and PostgreSQL/MockMvc verified on 2026-10-05; live maintenance/consumer/capacity acceptance remains separate.
- Chinese counterpart: [客户端Secret变更竞争与回滚验证](../zh-CN/implementation/client-secret-concurrency.md).
- Tracking: [status map §3.15](status-issues-and-plan-2026-10-02.md).

## Implementation and invariant

Supported client `patch`, `rotateSecret` and `disable` operations acquire the client's `registered_client_metadata` row before reading the mutable RegisteredClient and associated configuration. After waiting, the current record is read again, so a patch cannot save a stale pre-rotation Secret. `enable` changes only metadata/status/version and obtains the row's write lock through its UPDATE; it never rewrites RegisteredClient/Secret. No new production change was required in this expansion.

Rotation retains a single active Secret: new hash/configVersion, authorization/consent removal, Back-channel outbox, security event and success audit commit together. A later eligible name-only edit preserves the rotated Secret and version; security-relevant scope edits still advance their version and revoke where required. A disabled client may have its Secret rotated during maintenance, but authentication stays denied until enabled. Enabling does not resurrect old Secret, Consent or Authorization. Existing status/version semantics, including repeated enable incrementing version, were not silently changed.

This contract covers owning-service mutations, not direct RegisteredClientRepository writers, local startup seeders, manual SQL or new authorization issuance racing with mutation. It does not establish a complete application/database lock graph. Callers that pre-own conflicting locks or write outside this boundary need their own consistent transaction design.

## Executed expansion

`AdminClientConcurrencyIntegrationTest` adds **23** actual PostgreSQL 18.4/Spring-proxied/MockMvc cases:

- nine ordered writer pairs: name edit/rotation, scope shrink/rotation, disable/rotation, enable/rotation in both orders, and rotation/rotation;
- actual `pg_blocking_pids`/latch observations before the first transaction commits, final hash/version/status/name/scope, removed authorizations/consents and one transactional outbox notification;
- old/new Secret through the configured first-party Basic filter, and the configured OAuth token endpoint: old Secret gives `invalid_client`, new Secret reaches `invalid_grant` for a deliberately nonexistent Code (no fabricated successful grant);
- disable→rotate→enable retaining only new Secret and no resurrected authorization;
- unrelated client rotation completing while another client's metadata is locked;
- five actual PostgreSQL audit trigger faults, one outbox trigger fault and one idempotency-record trigger fault, verifying earlier mutations/versions/security/authorization/outbox roll back;
- first rotation rollback followed by a waiting name edit observing only committed old Secret;
- concurrent same-key idempotent rotation producing exactly one plaintext result and one nonsecret replay, changed-key-material refusal and failed-write retry.

Target client/User/issuer actor and Authorization/Consent are created through owning services. The issuer actor is a registered service-level fixture, not a browser administrator authorization acceptance test. Authorization security `sid` is explicitly assigned for outbox verification. Fault triggers/functions are only in the disposable DB and removed in finally blocks. Nine-table client snapshots include RegisteredClient, metadata/backchannel URI, Authorization/Consent/security metadata, client security events/audits and matching outbox; they do not claim every actor-session or auxiliary-table field remains unchanged. Independent idempotency checks cover no plaintext in the persisted result. Inbound client authentication requests use MockMvc; they are actual filter/provider/hash checks, not live TCP/client grant acquisition.

First 17-case expansion passed, then the 23-case/91-related run had one assertion mismatch: the existing OAuth failure handler returns HTTP 400 for `invalid_client`, while the test assumed 401. The test was corrected to assert existing 400 plus the distinct error code; no protocol handler was changed or bypass accepted. The final related run passed **91 = 23 + 11 client service + 53 persistence + 4 consent**, zero failures/errors/skips. Additional combined query/client verification passed **110 = 23 + 17 query contract + 6 query foundation + 11 client service + 53 persistence**.

Commands from `las-sso-server/`, Java 21/Docker:

```powershell
.\gradlew.bat :test --tests "org.las.sso.admin.AdminClientConcurrencyIntegrationTest" --tests "org.las.sso.admin.AdminClientServiceTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.config.AuthorizationConsentIntegrationTest" --no-daemon --console=plain
```

The subsequent common forced build of the identified development tuple executed **460 server / 178 starter** tests, zero failures/errors/skips, 18 actionable tasks including server/BFF boot JARs and publication metadata. S2 final XML has 23 cases at `2026-10-04T17:46:24.196Z` (2026-10-05 Asia/Shanghai). See the [development delivery](../integration/development-delivery-2026-10-05.md) for exact build command/provenance. Earlier XML was overwritten by later Gradle runs; command output/status §3.15 preserves stage history.

## Remaining scope

The identified stale-edit/Secret rotation defect is repaired with broader controlled writer/failure evidence. Live administrator/maintenance/client rollout, real grant/refresh issuance racing with client mutations, load/starvation/deadlocks, crash/restart/multi-instance and complete secret/log scanning remain separate. No new migration, dependency, permission or client authentication protocol was introduced. Work remains uncommitted; no hosted release/deployment occurred.
