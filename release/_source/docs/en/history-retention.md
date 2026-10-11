# History retention and growth investigation

[Index](README.md) · [中文](../zh-CN/history-retention.md) · [Monitoring](monitoring.md)

## Retention reference

| Data | Default eligibility |
| --- | --- |
| Terminal sessions | Terminal for 90 days; surviving SID references protected |
| Used/revoked ordinary/manual digests | Terminal/expiry predicates past 90 days |
| Authorization/family | Every known Token/family deadline expired for 30 days |
| Orphan REVOKED RT | Expiry and revocation past 30 days |
| Supported successful outbox | Successful for 30 days, no claim/dead |
| Live hint/RT, pending/leased/dead/unknown | No TTL/count pruning |
| Audit/identity/username/consent/deletion/tombstone | No P05 auto-delete/archive |

Unknown expiry/unsafe deadlines protected; lost old hints not reconstructed. Consumer/reference retention is separate.

## 1. Read-only report

**Docker: package root with `dc()`.** Password enters only psql process environment from protected backup file.

```sh
dc exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/db_backup_password)" exec psql -X -h 127.0.0.1 -U las_sso_backup -d las_sso -v ON_ERROR_STOP=1' \
  < scripts/history-status.sql
```

Host protected connection service:

```text
psql --dbname=<protected-connection-service> -v ON_ERROR_STOP=1 -f scripts/history-status.sql
```

**Expected:** Counts, fanout, size and dead tuples; no Token/user values. Statement timeout 10 seconds, lock timeout 500 milliseconds.

Supply actual overrides using `-v session_days=...`, `credential_days`, `authorization_days`, `refresh_days`, `outbox_days`.

## 2. Runtime boundaries

| Setting / behavior | Default / limit |
| --- | --- |
| Interval / phase batch | 5 minutes / 100 parents or objects |
| Per phase | REQUIRES_NEW; 10-second transaction, 5-second statement, 500-millisecond lock |
| Busy rows | SKIP LOCKED/recheck, skip not success |
| Failure | Phase rollback; other phases continue |
| Authorization with >10,000 RT rows | Report and skip |
| Cascade | Parent batch not fixed child-row/WAL bound |
| Maintenance | scheduling=false disables jobs |

The theoretical ceiling of 28,800 parents/day is not measured capacity. Actual disposal must exceed eligible arrivals.

## 3. Symptoms

| Symptom | Check | Action |
| --- | --- | --- |
| Eligible backlog grows | Scheduling/interval/failure/busy | Repair lock/failure, measure before tuning |
| Growing size, few eligible | Live/unknown/permanent facts | Capacity/archive, no mass delete |
| Oversized grows | RT fanout/admission | Investigate and preserve reuse evidence |
| Delete does not shrink file | Dead tuples, autovacuum and WAL | Normal VACUUM ANALYZE reuses space |
| Hint lookup fails | Pepper/revocation/pre-upgrade loss | Check identity/invalidation, no fabricated hint |

`las_sso_history_cleanup_*`: rows/completed/skipped/failures/duration/oversized/busy; rows count after commit.

VACUUM FULL/reindex/partition/archive need independent downtime/storage/backup planning. Keep live security history.

## H01 Report and classify growth

1. At package root verify the dc target, software version and actual retention. Run the section 1 report with backup-role credentials and matching retention-day overrides.
2. Check exit 0 and record time, row/candidate counts, fanout and table/index size. Candidate estimates are not the number the application can safely delete.
3. Correlate fresh cleanup rows/completed/skipped/failure/busy/oversized metrics and compare a later report using the same policy. A single count does not demonstrate a leak.
4. Live growth calls for traffic/capacity review; eligible backlog calls for scheduling/locks/timeouts/throughput review. Permanent facts have independent retention responsibility.
5. Investigate report timeout using size/query cost/maintenance window; do not remove limits or add Token output.

**Expected:** Comparable, classified growth. **Failure:** Check backup grants; unknown expiry/oversized protection must not be bypassed by forcing REVOKED state.

## H02 Restore cleanup/tune parameters

**Prerequisites:** Measured growth, database headroom and a reviewed window. No immediate mass-clean command is shipped.

1. Check Web-process scheduling; disabled maintenance jobs are intentional. Correlate phase errors, locks and database state to identify the cause.
2. Retain the previous external configuration. Choose finite batch/interval settings and observe the minimum 90/30-day retention rules; do not blindly increase batches or prune live history.
3. Verify throughput/locks/WAL/heap/refresh/logout on representative isolated data, then install/load reviewed configuration via O03.
4. Wait a configured cycle and inspect committed rows, counters and report trends. Zero deleted rows can mean protected/no eligible data, not that everything was cleaned.
5. The database owner reviews autovacuum/dead tuples and a normal VACUUM ANALYZE window. FULL/reindex needs separate backup/space planning.

**Expected:** Sufficient disposal while preserving live protocol safety. **Failure:** A phase timeout rolls back; repair its cause, not truncate/delete protected rows.
