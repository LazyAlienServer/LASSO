# Operations

[Index](README.md) · [中文](../zh-CN/operations.md)

## Daily Checks

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

Monitor V15 hint/history growth and authorization deletion, audit/outbox/security retention and DB bloat. There is no delivered standalone history TTL/compactor or complete capacity policy; do not delete rows based on guesswork. Concurrent/outbox work needs controlled ownership rather than manually toggling processed flags.

## Reference Durable Revocation

Default BFF process restart loses local login. Optional `durable-revocation` uses its own DB and a 32-byte base64 AES key with key-id matching `[A-Za-z0-9._-]{1,64}`; schema/config are described in [integration](external-integration.md). Protect queue and key backups independently. Only committed tasks recover after restart; an interruption before submit can still leave remote authorization active. Dead tasks require investigated, authorized handling; no operator HTTP replay API is provided. Do not rotate away the active queue key while pending tasks require it; the reference loads one key, not a multi-key ring. Do not claim it persists login or supports complete multi-instance revocation.

## Incidents

For credential/key exposure, freeze ingress as appropriate, revoke affected sessions/authorizations/clients through supported administration, replace secrets under a validated continuity plan and retain sanitized audit evidence. For signing-key compromise or DB restoration, coordinate all consumers/old-kid cache barriers. For DB down, readiness should be503 while liveness remains200; restore connectivity without a crash-loop restart storm. For suspected abuse, inspect fixed low-cardinality metrics and risk/audit outcomes; do not disable CSRF/rate limits to clear symptoms. Recovery contact is the existing QQ group; contact-channel agreement alone is not proof of claimant identity or a complete manual-recovery procedure.
