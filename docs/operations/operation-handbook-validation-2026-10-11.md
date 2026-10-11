# Operation Handbook Validation — 2026-10-11

[中文](../zh-CN/operations/operation-handbook-validation-2026-10-11.md) · [Workflow index](../../release/_source/docs/en/operation-index.md) · [Reading guide](handbook-reading-guide.md) · [API/release status](../implementation/api-release-readiness-2026-10-11.md)

## Source and scope

Validation covers the current uncommitted handbook/API/release-tool work on `codex/fix-sso-quality-review`, whose HEAD is `297cdfc0dcf06a9db421064d5328b7bb71f07859`. HEAD alone does not identify these edits. The temporary `0.1.0-rc.1` template render is a tool fixture, not a selected candidate, application build or distribution. Existing development archives retain their original bytes.

English and Chinese package sources each contain **17 handbook pages and 57 numbered workflows**: U01–U11, A01–A10, F01–F10, I01–I06, O01–O04, B01–B02, M01–M04, H01–H02, R01–R04, J01, T01 and V01–V02. Each workflow has at least three ordered actions and explicit expected-result/failure guidance; shared page prerequisites and linked procedures supply authentication/deployment context.

## Implementation checks and corrections

- Compared page labels/request order with account, recovery, session and administrator scripts/templates. Administrator dialogs authenticate their own password before mutation. Recovery-code reauthentication requires another generation click; authentication alone does not rotate codes.
- Checked Cookie/CSRF/origin, actual statuses, applicable idempotency keys, one-time output and current-sid/user-wide logout against controllers/services. Historical usernames, including the owner's, are currently rejected; conflicting UI text does not establish reuse support.
- Kept manual recovery credentials separate from bootstrap temporary passwords. Holds preserve original deletion due time; release does not restart thirty days. Consumer evidence derives from validated password authentication, not local login success, refresh or dialogs.
- Matched queue columns to reference V1/V2 and maintenance parameters to the script/context. Queue reads use read-only transactions, ten-second statement/500-millisecond lock limits, 100-row samples and a total incomplete count. Rotation rechecks drainage after all producers/workers stop.
- Backup/restore/upgrade/TLS/systemd blocks stop on failure. Backup output uses restrictive creation, `noclobber` and a per-dump list. Restore waits for PostgreSQL health, restores before application migration and uses the migrator with `--no-owner --no-acl --exit-on-error --single-transaction`.
- Unknown remote refresh results remain explicit. Old A's revocation HTTP 200 does not prove unknown B/family cleanup; historical-RT compensation is an optional separately selected enhancement.

## Render, coverage and parsing

A fresh temporary render using the real `copy_templates()` passed:

| Check | Result |
| --- | --- |
| Bilingual filenames/workflow IDs | Matching 17 pages/57 workflows per language |
| API workflow directory | All 59 OpenAPI operation IDs in each language |
| Local Markdown links | 415 valid destinations within the rendered tree |
| Release placeholders | VERSION/DELIVERY_EN/DELIVERY_ZH replaced |
| Bash / PowerShell / JavaScript | 50 `bash -n`, 16 Windows PowerShell 5.1 parser, 2 Node module-syntax checks |
| Failure-stop behavior | 10 stubbed checks: dump, restore, upgrade migration, Nginx test, systemd verification in both languages |

Failure-stop checks substituted stubs returning exit 19 and verified no later command ran. They did not execute deployment operations. A temporary harness recursion error was corrected; the final checks passed and the fixture container was removed.

## Isolated PostgreSQL exercise

Used two disposable network-isolated PostgreSQL **18.4** fixture containers, no published database ports, and the actual `postgres-init.sh` role/default-grant script. Temporary credentials and dump bytes were not printed or committed.

1. A small non-secret table/sequence fixture passed backup-role custom dump/list and migrator single-transaction restore, migrator ownership and backup-role reads.
2. Applied actual **V1–V16 SQL scripts** as the migrator and added a non-secret sentinel table. All **28 public tables** survived custom dump/list/restore into the other empty application database, with matching counts and sentinel data.
3. Executed all **four bilingual application read-SQL blocks** from the backup handbook using the backup role. Executed both queue-inspection blocks in a separate reference database initialized by reference V1/V2.
4. `history-status.sql` passed before and after restore using the backup role. Restored-object default grants remained usable.
5. A second restore into the nonempty target was rejected; its sentinel count remained unchanged, confirming empty-target/stop-on-error guidance.

These directly executed SQL checks did **not** run Flyway metadata/checksum validation, a current-version JAR, interactive epoch/key maintenance, real account data, old-Token refusal, browsers, encryption/off-host storage or RPO/RTO measurement. Those require B02/V01/V02 runtime/target evidence. Fixture containers/volumes, temporary dump/logs and generated credentials were removed.

## Supporting checks and boundary

`python -B -m unittest discover -s scripts/tests -v` passed all **nine** release-tool tests. Repository checks passed **579 UTF-8/text candidates, 1,701 local links, one declared sibling link, three dependency locks, Wrapper checksum and V1–V16**. `git diff --check` passed; line-ending conversion warnings are not whitespace errors.

Gitleaks **8.30.1**, `dir --redact=100`, scanned **595 exact tracked/unignored source candidates**, including these handbooks and bilingual records: **exit 0, zero findings**. The path/hash manifest, redacted report and scanner logs are retained privately. Ignored historical packages/builds are outside that scope; this does not replace final artifact/history/dependency/image scans. The record's local date is 2026-10-11; the initial complete-source scan finished at 2026-10-10T20:29:50Z. The final source scan is repeated after inserting this sanitized result so the stored snapshot includes the record itself.

The prior 843-case complete dirty-workspace JVM build remains its dated result; this handbook work did not rerun or relabel it as a clean RC build. No actual new RC/stable archive or target deployment was produced. Linux systemd/UID custody, public CA renewal, real collector/Grafana/notifications, representative upgrade/restore and launched-consumer integration require actual environment evidence. The existing stopped local instance and historical delivery data were preserved.
