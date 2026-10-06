# Reference BFF durable revocation profile

- [Chinese](../zh-CN/integration/reference-durable-revocation.md), [ADR-0009](../adr/0009-reference-bff-durable-token-revocation.md), [delivery .4](development-delivery-2026-10-05-4.md).
- Sample implementation under `las-sso-reference-bff/src/main/java/org/las/sso/reference/revocation`, not a starter API/consumer database migration.

## Enable explicitly

Use profile `durable-revocation`, optionally together with `application-security`. Supply these external settings with no built-in credentials/key defaults:

| Property | Requirement |
| --- | --- |
| reference.revocation.jdbc-url | Dedicated jdbc:postgresql URL; do not use the SSO identity database |
| reference.revocation.username/password | Reference queue database account; sample migration requires schema/table creation privileges |
| reference.revocation.key-id | Stable1–64 character identifier using letters/digits/period/underscore/hyphen |
| reference.revocation.key-base64 | Independently generated32-byte random AES key, standard Base64; retain across restarts |
| reference.revocation.allow-loopback-http | Defaultfalse; explicittrue only for development literal127.0.0.1/IPv6 loopback endpoint |
| reference.revocation.worker-enabled | Defaulttrue; false accepts tasks but pauses delivery for maintenance/acceptance |

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun --args="--spring.profiles.active=durable-revocation --spring.config.additional-location=$ReferenceQueueConfigUri"
```

Use trusted confidential registration with advertised revocation endpoint and client_secret_basic. Settings may be external protected files/environment, never logged/submitted to Git. Profile startup explicitly runs the sample `revocation-db/V1__revocation_tasks.sql` with Flyway history in reference_revocation. Missing configuration/DB/key/schema mismatch fails startup. Default profiles create no datasource/schema, use the unchanged in-memory sink; reference dependencies are in its bootJAR, not exported by starter. The sample uses DriverManagerDataSource for short transactions, not a production pool sizing decision.

## Acceptance and delivery

submit commits encrypted material independently of outer transactions; local Session is already ended. On exception the logout handler logs category, does not restore login or claim remote success. A crash before submit commit can lose work. Task UUID and identity/endpoint/hint/keyId are authenticated as GCM AAD; client secret is only resolved in memory at delivery. Pending task snapshots cannot silently move across issuer/client/endpoint changes. Current-secret-only rotation is supported.

Claims use DB time/SKIP LOCKED/two-minute lease/token fencing. A claim counts toward eight attempts even if its process dies before HTTP. Failed HTTP/status/payload/configuration attempts persist randomized retry, eighth failure persists dead letter. An expired eighth claim becomes LEASE_EXHAUSTED without a ninth request. Only200 confirms; redirects never followed. HTTP200/failed DB completion leaves lease for recovery; duplicate RFC7009 requests are expected, not exactly-once. Successfully completed rows erase Token ciphertext/nonce.

## Inspect and recover

Safe metadata-only inspection on the explicit reference database:

```sql
SELECT id, registration_id, attempt_count, available_at, lease_until,
       completed_at, dead_lettered_at, last_error_code
FROM reference_revocation.tasks
ORDER BY created_at;
```

Monitor oldest pending task, leased/expired/dead counts and categorical worker errors. Do not select/export decrypted Tokens or Client Secrets. Completed receipts/dead rows are not auto-deleted. Define retention and alerts for the environment; encrypted dead material still requires access controls and key protection.

Dead letter is terminal until an operator separately diagnoses endpoint/client/key/DB availability and deliberately schedules recovery. This version supplies no replay API/automatic dead reset, retention cleanup or authenticated key rotation. Changing the sole key before draining/recovering old-key tasks makes them unreadable. Back up DB/key separately; an old DB restore can replay already-delivered revocations and may omit newer accepted tasks. Do not claim disaster-recovery continuity without its own exercise.

## Verified limits

37 reference tests include actualPostgreSQL18.4/Testcontainers and realHTTP plus profile wiring. Two real Edge/SSO BFF modes verified accepted task survives a stopped/new JVM and invalidates the original Refresh Token; database fixtureSQL accelerates lease/retry tests, not live wait/crash-at-every-instruction tests. Only the task queue persists; browser sessions/authorized clients/Logout replay/sid markers/index remain default process-local and are lost on BFF restart. Concurrent claim ownership is verified, not full distributed BFF login/refresh coordination. ProductionTLS/proxy/key rotation/pooled capacity/operator recovery/consumer adoption remain separate.
