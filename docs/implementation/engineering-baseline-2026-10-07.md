# SSO Engineering Baseline — 2026-10-07

- [Chinese](../zh-CN/implementation/engineering-baseline-2026-10-07.md), [contribution rules](../../CONTRIBUTING.md), [repository entry](../../README.md).
- Scope: repository organization, build/verification conventions and local Git history. Existing identity, permission and deployment policies remain as documented in their contracts.

## Local Commit History

The previously verified `.20261006.7` functionality and records were preserved before mechanical cleanup:

| Commit | Purpose |
| --- | --- |
| `1c287e9` | Verified security/client/runtime baseline, migrations V13–V15, tests and deployment samples |
| `f1f1093` | Contracts, bilingual ADRs, historical acceptance/delivery manifests and launch plan |
| `09700e4` | Engineering conventions, repository verification, pinned CI inputs and shared Gradle rules |
| `b21304d` | Java formatting only, isolated from substantive behavior changes |

These are local commits on `codex/fix-sso-quality-review`; no push, hosted release or production deployment is implied. Historical manifests retain their original base commit, worktree fingerprint and artifact hashes. Their previously uncommitted-source description is correct for that recorded execution, not the current repository state. The formatted checkout has new source bytes and must not be published under the old `.7` ID.

## Organization And Rules

- Root README identifies the active three-module build; server/Starter README pages now describe current usage and link historical evidence instead of accumulating conflicting stage counts. Reference BFF has its own README.
- CONTRIBUTING defines package/module boundaries, parameterized persistence, append-only Flyway migrations, focused/full verification, explicit staging and bilingual document maintenance.
- EditorConfig defines UTF-8 and indentation. Git attributes define LF source and CRLF batch files; Markdown hard-break spaces remain valid. The Unix Wrapper is executable in Git.
- Root ignore rules cover build/IDE/Python output, local environment/key files and the existing documentation ZIP/workstation init script. Existing local files remain intact; ignored content is not evidence of safe production configuration.
- Spotless8.10.3/Google Java Format1.28.0 AOSP are pinned development tools. Three modules share Java21/release21, UTF-8 compilation, JUnit Platform, formatting and stable archive-order/timestamp rules. Application dependencies/locks and versioned SQL migrations did not change during formatting.
- `-PrequireDatabaseTests=true` is an explicit Test-task input and rejects skipped XML results. Normal focused developer tests may still skip Docker-dependent suites, but cannot be presented as database acceptance.
- `scripts/verify.py` is standard-library-only Python3.10+: repository hygiene/UTF-8/JSON/document links/locks/Wrapper checksum/migration sequence plus three-module JUnit report totals. One declared sibling LASLOG-document link is context, not a build dependency.
- GitHub Actions definitions use pinned action SHAs and read-only permissions. Linux runs the complete database-required build; Windows verifies repository text and PowerShell syntax. Only a count-only JSON summary is uploaded, not secret-bearing runtime/test logs.

## Verification

From the repository root:

```powershell
python scripts/verify.py repository
python scripts/verify.py tests --require-no-skips
git diff --check
```

From `las-sso-server/`:

```powershell
.\gradlew.bat build :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication -PrequireDatabaseTests=true --rerun-tasks --no-daemon --console=plain
```

The final local Java21/Docker run took6m38s with28tasks executed: **505server+193starter+37reference BFF=735**, zero failures/errors/skips. All three formatting checks passed; actual PostgreSQL suites ran. Existing Testcontainers/Hikari shutdown warnings retain their fixture scope; they did not change the zero-skip report or prove production capacity.

For200 changed Java files, parsed type declarations/literals/control flow matched the pre-format source; comments and import ordering/removal were excluded from that comparison. The actual Gradle skip gate rejected an external temporary disabled test fixture with a nonzero result. The report verifier separately rejected skipped, failed, errored and empty reports. Four maintenance PowerShell scripts parsed successfully. Candidate-source secret-pattern scans used100%redaction and returned zero findings; this is pattern coverage, not a universal no-secret assertion.

GitHub-hosted CI execution has not run in this local-only task; workflow configuration is delivered, not claimed green remotely. The new build verifies engineering consistency, not a rerun of every prior browser/TLS/restore/load drill against the formatted snapshot. Historical `.7` JAR byte hashes were verified unchanged. SNAPSHOT output from local verification is not an immutable production release.

## Next Work

Proceed with the [production launch plan](production-launch-plan.md), starting with production startup-gate audit/tests while collecting target-environment inputs. Formal release still requires a new identified source/artifact combination and candidate-specific target/consumer/security evidence. Local commits fulfill source tracking, not the complete P16 production-release task.
