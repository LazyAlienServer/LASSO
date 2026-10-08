# Operations

[Index](README.md) · [中文](../zh-CN/operations.md)

## Daily Checks

Manual recovery: contact in existing QQ then verified private channel, confirm immutable UUID/current name and two independent existing-record bases, record conclusions/operator/reason/request-audit/expiry/outcome without plaintext. Insufficient evidence means no issuance. Never request password/code/Token/Cookie. Protect administrator codes independently of host/DB; record custody location/owner/generation only, remove old copies after rotation. For deletion disputes record requestID/reason, bounded hold expiry/public non-sensitive summary and review/release/owner cancellation result; release does not restart30-day wait. Real operator assignment/custody/training are deployment tasks, not proved by software download checks.

Observe DB-aware readiness, private scrape/snapshot freshness, login/admin failures, refresh/reuse, logout backlog/age/dead tasks, DB pool/storage and certificate expiry. Inspect audit/security events via `/admin/audit`; use time/requestId/action to correlate without Token/Cookie/password data. Ownership of CA renewal, backup validity, actual notification receipt and escalation belongs to operations. See [monitoring](monitoring.md).

Keep a release inventory: package version, verified SHA-256, source commit, deployed config revision without values, JDK/PostgreSQL/proxy versions and migration history. Do not regenerate secrets on restart. An SSO outage is not an account ban; consumers distinguish denial from temporary unavailable status.

## Maintenance Commands

From package root, supply the same protected DB/key environment, and server-only external config. Stop web writers and keep public ingress closed. Verify the package first, then load exact values:

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:/etc/las-sso/server.properties'
$m = Get-Content -LiteralPath './provenance/release-manifest.json' -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.server.path
$h = $m.artifacts.server.sha256
./scripts/reset-admin-password.ps1 -Profile production -JarPath $j -ExpectedSha256 $h
```

Reset reads passwords without echo and targets the current administrator; it is not an arbitrary user reset API. Bootstrap uses `-Username` or `-UserId` as in [deployment](deployment.md). Restoration additionally requires `ExpectedEpoch`/reason and explicit scheduling disable, as in [recovery](backup-and-recovery.md). Packaged maintenance rejects missing/mismatched hash before starting Java and never selects a wildcard/latest artifact. Keep interactive recovery output private and audit operator/reason/result.

## Upgrades And Rollback

1. Verify a distinct candidate package/version and its source/test/scan coverage; rehearse fresh and previous-schema DB on an isolated copy. Back up DB and separately record key/pepper/config custody.
2. Drain ingress, stop writers/workers, record active epoch and Flyway history, install the new immutable directory. Keep external settings separately versioned without exposing values. Start one SSO process, verify migrations/readiness/TLS/browser/consumer refresh/logout, then restart consumers and reopen gradually.
3. Record deployment times, verified hashes and acceptance. On failure keep ingress drained. Never run an older binary against a changed schema without a tested compatibility decision. If restoring an older snapshot, use the full epoch/new-signing-key/consumer barrier; ordinary file rollback is not sufficient.

V16 supports finite history cleanup: every5minutes batch100 per phase, terminal sessions/used-revoked ordinary/manual digests90days, fully expired parent/family+30days, orphan REVOKED RT after expiry/revoke+30days, supported successful outbox30days. All live hints/RT, pending/leased/dead/unknown events and audit/identity/deletion facts remain protected. No whole-storage bound or audit archive is claimed. Global scheduling=false disables jobs for maintenance; each phase10s transaction/5s statement/500ms lock, busy rows skip and failed phases roll back. More than10000 RT hashes per parent is reported/skipped; cascades mean parent batch is not child/WAL bound. Default ceiling28800 parents/day per phase requires measured disposal above eligible arrival, target capacity still separate.

Read-only `psql --dbname=<protected-connection-service> -v ON_ERROR_STOP=1 -f scripts/history-status.sql` reports counts/fanout/sizes with no Token/user values; supply actual configured retention day variables when overridden. Monitor `las_sso_history_cleanup_*` rows/completed_runs/skipped_runs/failures/duration/oversized_authorizations/busy_authorizations; rows count after commit, skips not success. Do not mass delete unknown/large families, live hints or dead work. Ordinary VACUUM (ANALYZE) reuses dead space; DELETE doesn't shrink files. Audit archives/retention, bloat, backup, pepper invalidation and target admission/throughput require owner review.

## Reference Durable Revocation

Default BFF restart loses login. Optional durable queue has its own PostgreSQL V1–V2 and32-byte AES key. Only committed tasks recover; pre-submit gaps require consumer intent/OP compensation. V2 provides offline `reference-revocation-maintenance.ps1` with exact reference JAR/hash/current key ID/operator/reason/YES console. Replay requires dead task/current replay_count, validates encrypted payload and audits a new8-attempt campaign. Completed-only receipts expire after30days by default, batch100/hour; pending/dead/leases never purged. Rotation refuses all incomplete work and same/reused key IDs or material; key registry verifies configured material and fences retired-key processes. Stop all producers/workers including pre-V2 binaries before maintenance; no OIDC discovery/Web/worker runs in the small maintenance context. Use protected `reference.revocation.next-key-base64` for next material, never arguments; switch active configuration only after committed rotation. Queue/key backups remain separate, no persistent login/multi-key ring/cross-storage atomicity.

## Incidents

Reference queue commands from package root (external reference queue config in `SPRING_CONFIG_ADDITIONAL_LOCATION`, not SSO config):

```powershell
$m = Get-Content -LiteralPath './provenance/release-manifest.json' -Raw | ConvertFrom-Json
$j = Join-Path $PWD $m.artifacts.'reference-bff'.path
$h = $m.artifacts.'reference-bff'.sha256
./scripts/reference-revocation-maintenance.ps1 -Command replay -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'receiver repaired' -TaskId $TaskId -ExpectedReplayCount $ReplayCount
./scripts/reference-revocation-maintenance.ps1 -Command purge-completed -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'completed receipt retention'
./scripts/reference-revocation-maintenance.ps1 -Command rotate-key -JarPath $j -ExpectedSha256 $h -ExpectedKeyId $CurrentKeyId -Actor $Operator -Reason 'queue drained key rotation' -NextKeyId $NextKeyId
```

Select the action you need; rotation requires drained producers/workers and no incomplete task. Query only task ID/key_id/replay_count/attempt_count/timestamps/error code in `reference_revocation.tasks`, never ciphertext/Token. Replay resets a dead task's budget only after expected-generation comparison, authenticated payload and committed audit; worker still compares current endpoint/client/issuer. Keep unresolved dead tasks, diagnose and compensate at OP if necessary; purging does not resolve them. Prepare next material externally only for rotation, keep current key during the command, then install new active configuration after success and remove next-key input. Operator audit remains retained; define access/custody and monitor growth. V2 guards do not protect against a pre-V2 binary: keep all such processes stopped.

For credential/key exposure, freeze ingress as appropriate, revoke affected sessions/authorizations/clients through supported administration, replace secrets under a validated continuity plan and retain sanitized audit evidence. For signing-key compromise or DB restoration, coordinate all consumers/old-kid cache barriers. For DB down, readiness should be503 while liveness remains200; restore connectivity without a crash-loop restart storm. For suspected abuse, inspect fixed low-cardinality metrics and risk/audit outcomes; do not disable CSRF/rate limits to clear symptoms. Recovery contact is the existing QQ group; contact-channel agreement alone is not proof of claimant identity or a complete manual-recovery procedure.
