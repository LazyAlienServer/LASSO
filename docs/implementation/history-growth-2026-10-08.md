# P05 Runtime History Growth And Cleanup

[中文](../zh-CN/implementation/history-growth-2026-10-08.md) · [ADR-0015](../adr/0015-runtime-history-retention.md) · [Lifecycle/operator guide](../operations/runtime-history-retention.md)

## Policy And Implementation

User approved90-day terminal sessions/ordinary/manual credential digest retention,30-day fully expired authorization/orphan REVOKED RT/success outbox, protection of every surviving authorization hint, and no automatic deletion of audits/deletion/identity facts. `RuntimeHistoryCleanup` uses finite phases/independent bounded transactions/SKIP LOCKED/predicate rechecks, RT→authorization ordering and partial-lock release. Existing short-lived cleanup now skips busy rows and has timeouts. Spring Session's existing JDBC expiry/attribute cascade is separately verified, not replaced. Monitoring excludes successful outbox archive and uses the measured active-session expiry predicate.

V16 adds indexes for RT authorization delete trigger, reclaimed RT expiry, active-session expiry/terminal records, parent Token deadline, credential terminal records and live/success outbox. No application dependency/Starter/reference behavior upgrade. Existing P03 reference retention is reused; audits and pending/dead work retain explicit separate responsibilities.

## Synthetic SQL Measurement

Disposable PostgreSQL18.4 on this workstation,1000 synthetic users,100000 sessions,10000 authorizations,200000 RT rows/200000 hint rows,100000 outbox/reauth challenges/admin audit rows. Initial source was the P04 server JAR `2d11bc988aa77cecb653480dbc1f3d9d913c7e3e8d9c65803916eb88405288f7` (V1–V15). Candidate indexes existed only in this fixture; actual V16 was later measured separately. EXPLAIN ANALYZE/BUFFERS/WAL, warmed/synthetic data, not endpoint capacity or production SLA. First seed omitted metadata.updated_at and was corrected as fixture error, not product failure.

| Query | Before | Candidate result | Interpretation |
| --- | --- | --- | --- |
|100-parent authorization DELETE/RT trigger/hint cascade |1089.184ms |36.407ms | Missing RT authorization_id index dominated trigger work |
|100 active expired-session candidates |16.711ms |0.271ms | Partial expression expiry index avoids large scan/sort |
|Snapshot original vs incomplete-outbox predicate |24.169ms |7.734ms | Avoid scanning99000 successful historical rows; later candidate indexes alone did not further improve this specimen |
|1000 expired challenge deletion |16.692ms |14.592ms | Existing expiry index; change mainly adds concurrency/timeout safety, not a claimed speedup |
|100 expired family authorization candidates |4.890ms |4.589ms | Family-only candidate index did not justify inclusion; final service uses parent deadline index+family guard |

Baseline table+index bytes: sessions25.83MB, authorization1.95MB, RT41.89MB, hint32.85MB, outbox17.91MB, challenge10.33MB, audit16.44MB. These rows omit real serialized principals/Token strings/Spring Session attributes and use short payloads; do not size production storage solely from them. Hint/RT hash rows alone already dominate history at this20-refresh/authorization specimen.

## Current Java Service Measurement

Final bootJAR classes/libraries, explicitly seeded V15 database then actual V16 index upgrade, no public endpoints.35 real phase calls (5 per phase), batch100. Session expiry100 rows each: cold287.018ms then22.302–26.078ms; expired authorization100 parents each:588.557–699.053ms including JDBC multiple lock/recheck/cascade operations; success outbox100 each:16.374–18.686ms. Empty/protected phase calls12.430–23.655ms.5000 live authorizations/100000 live hints stayed unchanged. Actual V16 index creation on this seeded V15 fixture took368.618ms; this is not production upgrade downtime. Read-only history status and normal VACUUM (ANALYZE) passed. SQL36ms delete-only and Java~0.6s measurements differ in scope/transport and are not interchangeable.

Default per-phase ceiling100/5min=28800 parent objects/day; sustainable arrival must be lower than observed disposal after locks/errors/fanout. Audit/unknown-expiry/oversized authorizations deliberately remain retained, so P05 is safe lifecycle cleanup with visible operational boundaries, not universal bounded storage. Refresh/hint rate can be arbitrarily high without a separately accepted admission rule; retain live associations as approved and monitor fanout/growth. Target real payload/users/concurrency/hardware/hour-scale soak remain P01/P13.

## Verification

New actual PostgreSQL tests cover retention/unknown expiry/family/child deadline guards, terminal-session sid refusal, supported-only success outbox, audit/identity protection, credential boundaries, batch drain/idempotency, SQL failure and5s timeout rollback, refreshed-parent/held RT row races/partial lock release, advisory contention, unrelated scheduled phase progress, and oversized family protection. Existing real MockMvc refresh/reuse and initial-hint logout regressions now run cleanup between protocol operations; existing JDBC session expiry/cascade is checked. Fixture dates and pg_sleep faults are controlled, not every live process-crash interleaving.

Final forced JDK21/Docker full build: **585 server +196 Starter +50 reference =831 tests**, zero failures/errors/skips. Four tracked browser interaction regression groups passed; these are separate from JVM counts. Current server SNAPSHOT SHA-256 `72124a0d8668aeecdd425f164e03b9c3c2cd87ebe314caadfe2049cdd9f82293`, base `b901104` plus this P05 worktree. Actual production-profile JAR/hidden bootstrap/process fixture verified scheduling=false leaves expired session/old-success rows untouched, scheduling=true reclaims them while preserving pending/dead/fresh-success. The one-second history polling is fixture-only; direct loopbackHTTP with HTTPS issuer metadata is process/scheduling verification, not TLS/browser/consumer acceptance. Fixture JVM/PostgreSQL and external key/config were cleaned.

V15→V16 Flyway populated fixture validates checksums/one migration/preserved authorization+hint and idempotent rerun. Controlled SQL5s timeout/rollback and parallel-row/advisory tests verify measured cases, not whole-system deadlock freedom. The first JDBC Session test used inaccessible implementation types and was corrected to the public SessionRepository/Session interface; no production Session behavior was changed. No production writes, public deployment/new versioned package, archive deletion or full target capacity approval. Prior release bytes remain immutable; new bytes need a new delivery ID.

Final source review:537 candidate files scanned with Gitleaks8.30.1/redact100, zero findings. Repository checks passed515 UTF-8/text candidates,1246 local links and one declared sibling-document link, three dependency locks/Wrapper checksum and16 sequential migrations. Original `0.1.0-dev.20261007.1` package still verified70 files/104 links and unchanged provenance. No fixture containers remain running. Raw measurement/build/scan reports stay private outside source; this step does not claim a new dependency/image/DAST scan, production deployment or target-capacity approval.
