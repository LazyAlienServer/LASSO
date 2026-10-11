# GitHub Source Publication Review — 2026-10-11

[中文](../zh-CN/operations/github-source-publication-2026-10-11.md) · [Repository](../../README.md) · [Contribution rules](../../CONTRIBUTING.md) · [RC evidence](../integration/release-candidate-2026-10-11-rc1.md)

## Reviewed snapshot

Reviewed `LASSSO/` as its own repository, not the parent LAS workspace or sibling LASLOG/Velocity repositories. At audit HEAD `7f2b905e7d6b4161d1bf7d380558ead8e8415f2b`, the working tree was clean and **608 files / 14,313,583 bytes** were tracked. Largest file was a 1,295,102-byte concept PNG; no reachable history blob exceeded 50 MiB. There was no configured Git remote.

The active branch is `codex/fix-sso-quality-review`. Local `main` is still `5252e51`, 24 commits behind the active branch. Publishing only the current local main would omit the recent implementation, CI, packaging, deployment and release evidence. Choose how to bring the reviewed branch into GitHub's default branch; this review does not rename, merge or rewrite branches.

## Source to retain

| Paths | Reason |
| --- | --- |
| `las-sso-server/src/` and both subprojects' sources/tests | Actual server, Starter and runnable reference sample; include tests and templates/static assets |
| Gradle build/settings/catalog, all dependency locks, Wrapper scripts/properties/JAR | Reproducible bootstrap and dependency identity; Wrapper JAR is an intentional binary exception |
| `las-sso-server/src/main/resources/db/migration/` | Append-only application schema history; do not exclude SQL globally |
| `las-sso-server/deploy/`, `las-sso-server/scripts/`, `scripts/` | Reviewed Docker/Nginx/monitoring/maintenance templates and verification tools |
| `release/_source/` | Source of the independent package's documentation, OpenAPI and operator tools, not a generated release directory |
| `release/verification/` | Sanitized version/test/hash/scan-scope records; no raw secret findings or runtime captures |
| `.github/workflows/verify.yml` | Pinned Actions and Java/PostgreSQL/Python verification; uploads only count-oriented test summaries |
| `README.md`, `CONTRIBUTING.md`, `CONTEXT.md`, `LAS_SSO_DESIGN.md`, `docs/`, `.editorconfig`, `.gitattributes`, `.gitignore` | Product/engineering/API/ADR/operating context and traceability |

Explicit local/test fixture credentials and local-only client settings belong in the source. They are not production material; production startup rules reject the documented example values. Environment placeholders and property names such as password/pepper are not secrets themselves. Do not remove tests or globally ignore `.properties`, YAML, JSON, SQL or all `release/` merely because those formats can carry secrets.

## Local files to exclude

Keep `.idea/`, `.gradle/`, `build/`, `bin/`, `target/`, `node_modules/`, Python caches, `.build-docker/`, `.build-release/`, logs and heap dumps local. Historical `SAS-Test/` is ignored and is not part of the active build.

Actual `deployment.env`/`restore.env`/server/monitoring/queue configuration copies, `secrets/`, PKCS12/private keys/certificates, password files, recovery codes, Cookie/Token/browser storage state, HAR files, raw acceptance/security logs and database dumps never belong in the source repository. Prefer external protected settings or `.local/`; ignore rules are a convenience, not a replacement for staged-file review.

Generated application JARs, image tar exports, versioned release directories and ZIPs are not Git source files. A verified RC ZIP may later be a **GitHub Release asset**, with the matching checksum and candidate limitations. The existing RC image's inherited pebble High findings remain explicit; GitHub source publication does not resolve them or approve production.

This review adds targeted ignore rules for deployment copies/secret directories, backups/captures/exported archives and application binaries, retaining `gradle/wrapper/gradle-wrapper.jar`. Templates ending in `.example`, application migrations and versioned evidence remain tracked. No existing tracked file was removed and no local deployment data was deleted.

## Optional material and repository choices

- `design/`: generated concept artwork/brand assets. The inspected overview/session/recovery mockups show demonstration identity/device data and masked codes, not real authentication captures. They may stay for design history, or be moved deliberately to a design repository if a smaller code-only repository is desired. No automatic deletion is warranted.
- `research/transactional-email-pricing-2026-07-29.md`: historical public-source research. It discusses email/MFA assumptions that are not the accepted V1 scope. Retain as explicitly historical reference or deliberately archive elsewhere; do not present it as current requirements.
- `AGENTS.md`: an existing one-line tooling instruction stub; optional for public readers, not secret content.
- No root `LICENSE` was found. Public/private GitHub visibility and an intended license are owner decisions. Do not invent a license or assume public hosting grants reuse rights.
- Commit metadata contains one non-noreply email identity. A public push publishes that author/committer metadata. Changing future Git identity does not change existing commits; any anonymization/history rewrite would need a separate explicit decision and would affect release provenance.

## Current source and history checks

Gitleaks **8.30.1**, `--redact=100`: all 608 current source candidates produced **exit0 / zero findings**. All refs' **36 reachable commits** produced one historical `generic-api-key` finding at `ProductionConfigurationGateTest.java`, commit `f5048d2`, line206. This is the already reviewed synthetic MockEnvironment keystore-path fixture; original exit1 and finding identity are retained without suppression. The current production-path property value is not a real key or credential.

Reachable history includes a deleted `docs.zip` blob (92,670 bytes, 21 documentation entries). Its SHA256 is `f279a48b87db295e6743cbe7949e3f4f699e73ffbe8c9b84c96141c3f86eca88`; depth3 archive scanning produced exit0/zero findings and no sensitive entry paths. An ordinary push carries reachable deleted files too; `.gitignore` does not purge history. No discovered secret requires a history purge here. Raw scanner output and path/hash manifests are retained privately outside the repository.

These findings describe the inspected snapshot, not a promise that every future file is safe. Review exact staged paths before a commit; use the repo verifier/secret checks again after changes.

## Upload boundary

The initial review prepared ignore/documentation changes without committing or uploading. The user subsequently requested publication to `https://github.com/LazyAlienServer/LASSO.git`. Official GitHub CLI2.102.0 confirmed that the repository is public, empty and accessible with ADMIN permission. Publish the reviewed latest source as main using a fast-forward local branch update and an ordinary push; do not rewrite history or upload generated release assets in this source-publication step. No license is added or inferred. GitHub CI execution is checked after the push rather than claimed from local results.

## Initial publication and hosted-runner finding

Source publication succeeded on public main at `20e78ab` with 610 tracked files, preserving existing history. Local main was fast-forwarded and tracks origin/main; old development branches were not uploaded. Actual credentials/builds/release ZIPs remain ignored and RC bytes are unchanged.

First [GitHub Verify run](https://github.com/LazyAlienServer/LASSO/actions/runs/38147584998): Linux Java21/PostgreSQL/formatting and report verification passed. Windows repository checks passed, but release-tool fixtures failed because hosted TEMP used the Windows `RUNNER~1` alias while resolved destinations used the canonical long path. A local unresolved-path harness reproduced the same three failed cases/four error subcases; resolving fixture roots before containment checks passed that harness and all14 Python tests. Only test roots changed; the shipping verifier's path traversal/symlink checks remain intact. The fix is a new ordinary commit/push, not an amendment or replacement of the immutable RC package. CI on the follow-up commit must be checked independently.
