# Runtime History Retention And Cleanup

[中文](../zh-CN/operations/runtime-history-retention.md) · [ADR-0015](../adr/0015-runtime-history-retention.md) · [P05 measurement](../implementation/history-growth-2026-10-08.md)

## Lifecycle Matrix

| Data | Eligibility and retention | Owner |
| --- | --- | --- |
| users/usernames/deletion/consent | No P05 auto-delete; UUID tombstone and reserved names remain permanent; other facts need a separate accepted policy | Domain/operations |
| administrator/security/operator audit | No P05 auto-delete or silent archive; measure growth and establish independently verified storage/access/retention | Operations, not cleanup job |
| spring_session/attributes | Existing Spring Session JDBC expiry cron (normally once/minute), FK attribute cascade. P05 does not replace its repository/lifecycle; overdue large backlog needs observation because built-in deletion is not fixed-batch | Spring Session/operator |
| user_sessions | Mark ACTIVE past idle/absolute deadline EXPIRED even without another request. Retain terminal row90days after actual expiry/revocation; do not purge while surviving authorization references sid. Old sid stays rejected | History job/current request checks |
| oauth2_authorization/security | Only when every known stored Token deadline, child RT expiry and family absolute expiry have been past30days. Unknown expiry/missing required metadata or no safe deadline is protected; expired JWT acceptance is not prolonged by retained row | History job |
| oauth2_id_token_hint_history | All hashes for surviving authorization retained; deletion cascades with reclaimed/revoked authorization. No independent TTL/count trimming | Authorization service/FK |
| oauth2_refresh_token_history | Live CURRENT/ROTATED preserve reuse detection. Only REVOKED with no parent authorization and both expiry/revocation past30days can purge | Existing revoke trigger/history job |
| recovery_codes | Only used/revoked digests whose later terminal timestamp is past90days; unused valid codes retained | History job |
| admin_recovery_credentials | Existing expiry job marks revoked and audits; use/revocation/expiry all past90days before purge. No silent skip of required expiry audit | Expiry job/history job |
| outbox_events | Only supported successful global/backchannel rows, processed30days ago, no claim/dead state. Pending/leased/dead/unknown events retained for disposition | History job/delivery owner |
| rate/captcha/recovery/idempotency/reauth tables | Existing expiry semantics, every5minutes up to1000 per table, one transaction; now SKIP LOCKED and predicate recheck. Effective retry windows not shortened | Transient cleanup |
| reference_revocation | Reference-only V1–V2, completed retention30days/batch100/hour; pending/dead/leases retained, exact recovery/key draining as P03 | Reference operator |

## Configuration And Execution

`las.sso.history.batch-size=100` (1–1000), `session-retention=P90D`, `credential-retention=P90D`, `authorization-retention=P30D`, `refresh-history-retention=P30D`, `outbox-retention=P30D`; whole days up to3650, safety minima90/30days cannot be reduced. `cleanup-initial-delay`/`cleanup-interval` defaultPT5M, validated1s–1day. Do not shorten intervals or enlarge batches without target measurements. Global `las.sso.scheduling.enabled=false` keeps all app cleanup/delivery/deletion jobs off in offline maintenance; history does not start an independent daemon.

Each history phase commits independently with10-second transaction,5-second statement and500ms lock timeout; transaction-scoped advisory lock avoids concurrent same-phase execution. Busy rows skip, expiry predicates rechecked. Authorization scans at most10×batch candidates and deletes at mostbatch parents, locks RT→parent→security then rechecks; skipped candidate savepoints release partial locks. More than10000 RT rows on one authorization is skipped/reported. Parent cascades can change many rows, so batch is a parent/object limit, not a strict total child-row/WAL cap. Timeout rolls back that phase; scheduled loop continues other phases. The job never sends remote logout just because expired history is removed.

V16 adds partial expiry/terminal/outbox indexes and the missing RT authorization index that the delete trigger needs. These are ordinary Flyway indexes, not concurrently-created online indexes: rehearse on representative data, reserve upgrade time/disk and close/drain traffic. P02 rejects future/partly applied schemas; don't roll an older P02 binary back against V16 without its validated compatibility. Fresh and V15-upgrade tests are separate from production backup/recovery.

## Observe Safely

Run packaged/source `scripts/history-status.sql` with psql connected to the explicit reviewed DB and `ON_ERROR_STOP=1`; supply actual configured day variables if overridden:

```text
psql --dbname=<protected-connection-service> -v ON_ERROR_STOP=1 -v session_days=90 -v credential_days=90 -v authorization_days=30 -v refresh_days=30 -v outbox_days=30 -f scripts/history-status.sql
```

Use a protected connection service/pgpass/secret mechanism, never password in command or URI. Report is read-only10s/500ms and contains counts/fanout/sizes/dead tuples, no token/hashes/user IDs. Candidate counts are estimates; job also excludes unknown state, live references, locks and child deadlines. Large reports can time out safely; investigate query cost instead of removing time limits.

Prometheus names: `las_sso_history_cleanup_rows_total`, `...completed_runs_total`, `...skipped_runs_total`, `...failures_total`, `...duration_seconds_count/sum`, `...oversized_authorizations_total`, `...busy_authorizations_total`. The seven phase labels are fixed, no identity/config strings. Row counters publish only after commit. A skipped run is not completed; completed with zero rows means nothing eligible/protected/busy within sampled candidates. Oversized/busy counters count encounters, not distinct account/family counts. Existing snapshot aggregates incomplete outbox only; scrape still reads cached gauges rather than issuing SQL.

Compare eligible backlog over time with phase throughput. Defaults can process at most28800 parent objects/day per phase (100×288), less with locks/fanout/errors; six transient tables each at most288000 expiry rows/day under1000/5min. Sustainable disposal must exceed arrival of newly eligible rows. Reference queue batch100/hour is at most2400 completed receipts/day. Shorten backlogs under controlled measurements, never mass-delete live history or dead tasks.

## Growth And Operator Actions

One SSO login adds user-session/JDBC session+attributes; each OIDC authorization adds parent/security and initial RT/hint when issued. Each successful refresh usually adds one ROTATED/CURRENT history transition and one new hint hash; retries/duplicate save don't automatically mean another hash. For a continuously active14-day family with15-minute AT/60-second refresh lead, about1440 rotations/family are possible; busy/adversarial consumers can refresh more, so this is an illustrative normal-flow model, not a bound. Retained hints/RT multiply by active authorization/client/browser count; each new family and abandoned code has its own deadlines. `last_seen` updates are rate-limited5minutes but request validation still locks/checks session.

Treat repeatedly skipped/oversized/unknown-deadline authorizations as operator backlog. Use count-only reports and trusted metadata investigation; isolate/drain affected writers and choose a reviewed handling path preserving RT reuse/hint contracts. Do not truncate child tables or flip statuses to bypass safeguards. P05 deliberately does not invent an online token-volume admission policy. Audits/deletion facts need separate archive validation and ownership before deleting anything; encrypted/off-host backup still retains data according to its own policy.

`DELETE` creates dead tuples/WAL and generally does not return table space to OS. Observe `pg_stat_user_tables`, autovacuum/analyze timestamps, n_dead_tup, disk/WAL/replication and bloat; normal `VACUUM (ANALYZE)` reuses space without ACCESS EXCLUSIVE rewrite. `VACUUM FULL`, reindex/partition changes need separate downtime/storage/backup planning. Do not disable autovacuum, run schema repair or place passwords/Token contents in EXPLAIN/runtime evidence.

Pepper changes affect RT/hint/recovery/transient digest recognition. No seamless multi-pepper lookup/rehash exists; plan invalidation, key custody, epoch/consumer barriers where restoration applies. Never call a Token refresh/new issue time recent-password evidence. P05 verification is isolated; actual target hardware/scale/soak, backups/archives and consumers remain P01/P08–P14.
