# Implemented API and Software-Release Readiness — 2026-10-11

[中文](../zh-CN/implementation/api-release-readiness-2026-10-11.md) · [API reference](../../release/_source/docs/en/api-reference.md) · [OpenAPI3.1](../../release/_source/api/openapi.json) · [Packaging](../../release/README.md)

## Scope

Release continuation authorized on 2026-10-11: review and group local commits, then clean build/scan/package/accept `0.1.0-rc.1`. Earlier uncommitted-workspace statements below describe the verification snapshots at those times. No push/hosted release or target-production operation is authorized by this local continuation. The final candidate records its actual committed source/artifacts independently.

Prepare the first software release separately from a public production deployment. Existing `0.1.0-dev.20261009.2` and earlier delivery bytes remain immutable; its source was `297cdfc`, and new source changes do not patch the running local image or retroactively change its evidence. No hosted release, push, production database operation or automatic approval follows from this work.

## API documentation

The previous `docs/architecture/api-contract.md` is an implementation specification with some aspirational/mismatched details. New independent English/Chinese references and an OpenAPI3.1 JSON under `release/_source/` document actual MVC account/admin/internal routes, request and returned Java-record fields, authentication, CSRF/origin, idempotency, status/content-type exceptions, browser forms, protocol and private management surfaces. No public Swagger dependency or runtime documentation endpoint is introduced.

Examples of documented implementation differences: recovery password uses PUT; form login uses `rememberDevice`; password-change local errors and session/restriction filters can return JSON `{code}` rather than Problem; client creation returns200; authorization list uses display labels; current-sid SSO revocation calls all-user-session logout; registration's Location does not imply a GET user-detail API. Documentation does not silently fix or promise those behaviors.

`OpenApiContractTest` compares all `/api/v1` and `/internal/v1` MVC routes and annotated Java record fields against the spec, checks recovery enum and password-error shape. Independent `openapi-spec-validator0.7.2` validates OpenAPI3.1. Tool checks are drift prevention, not whole-protocol compliance or generated-client interoperability proof.

## Versioned tooling

The same shared version policy accepts dev dates/sequences, positive RC sequences and stable semantic versions without leading zeros. Package kind/labels distinguish development-delivery, release-candidate and software-release; unsafe inputs and impossible dev dates fail. Clean committed source, exact artifact/source/test/publication identity and overwrite refusal remain. `productionApproved=false` still applies to software packages; a stable version is not a deployment approval.

Independent Python fixtures exercise stable/RC/dev verification, hashes/inventory, resealed kind/API-version/foreign-reference/deployment-claim/skipped-test failures. They use synthetic JARs and are explicitly not application release evidence. The ordinary Linux/Windows CI now includes these tool fixtures; a complete remote release workflow was not run here.

## Confirmed refresh client-isolation defect and correction

A targeted real PostgreSQL/MockMvc test submitted the owner's CURRENT or ROTATED RT using another authenticated client's credentials. CURRENT was rejected without side effects; ROTATED returned400 but deleted the owner's Authorization before the standard provider checked client ownership. First run:3cases/1failure/0skips at2026-10-10T17:47UTC. This requires possession of a real owner's RT plus another valid client's credentials; it is unauthorized revocation, not issuance of the owner's identity to the other client.

The wrapper now requires an authenticated client and passes its internal registered-client ID into history validation. After history hash locking, the application checks matching Authorization/family/client before reuse revocation, events or outbox. Legacy CURRENT import also filters owning client. Ownership is a read under the existing history transaction rather than introducing a new parent lock before cross-history family updates. Existing CURRENT refresh locks and legal reuse policy remain. No schema/lock/dependency change or historical-RT revocation extension is added.

Tests cover CURRENT/ROTATED cross-client no mutation, missing/wrong secret, lawful owner can still refresh, existing lawful reuse revokes, concurrent refresh, legacy import, unchanged family-created/absolute timestamps across three real rotations, and rotated reuse racing current refresh. An initial missing-auth fixture expected400/401 but got a browser302 without JSON Accept; specifying JSON corrected that fixture, not a runtime response change.

## Refresh result uncertainty and release scope

Lost B cannot be recovered by local CAS. Existing `/oauth2/revoke` does not resolve consumed A to revoke successors, and RFC7009 HTTP200 is not family-cleanup proof. This is a known contract limit, not automatically a mandatory protocol defect or V1 feature. Recommended consumer baseline: local fail-closed/restart invalidation, persistent accepted known-token work, truthful unknown remote status, verified family/AT/resource boundaries and explicit residual-risk acceptance. Do not intentionally reuse A to trigger high-risk replay handling. Stable-ID/historical-RT compensation remains an optional separately decided reliability enhancement.

LASLOG adapters/replay/worker/browser acceptance remain LASLOG-owned; account-site-only SSO release does not await all consumers. Target public CA/renewal, Linux secrets/UIDs, actual image security, backup/restore/notifications and capacity are deployment acceptance, not a claim supplied by local package tests.

## Verification status

Later [host-edge preparation](../operations/external-nginx-validation-2026-10-11.md) records the confirmed single external Nginx topology, 14 tool cases, 18 pages/63 workflows per language, real Compose parsing/TLS probe traffic and a new standalone SNAPSHOT runtime fixture. Formal target domain/HTTPS is deferred by user instruction; exact RC provenance/acceptance is still pending.

The later [operation-handbook validation](../operations/operation-handbook-validation-2026-10-11.md) records bilingual workflow coverage, shell parsing/failure-stop checks and isolated SQL/dump/restore exercises. The earlier source-scan/repository counts below are their historical snapshots, not the final handbook input set.

JDK21/Docker, `spotlessApply build -PrequireDatabaseTests=true --rerun-tasks --no-daemon --console=plain`: **597 server + 196 Starter + 50 reference = 843 tests, zero failures/errors/skips**, 7m2s, at UTC2026-10-10T18:00–18:01. Includes three API drift cases and six new refresh ownership/lifetime/race cases. This is a forced complete dirty-workspace SNAPSHOT build, not clean-source immutable release provenance. Existing Hikari shutdown/container-refused warnings remain test-fixture output, not production health or capacity evidence.

Nine Python tool tests pass, including actual stable/RC template rendering, matched bilingual pages/local links, package-local policy import in a fresh verifier subprocess, and resealed API/kind/deployment/skipped/tamper/extra negatives. Exact JAR manifest-version comparison rejects an RC executable relabeled as a stable package even with resealed hashes. Synthetic JARs are tool evidence only. A real-template check found imported Python cache bytes; template copying now excludes `__pycache__`/`.pyc`/`.pyo`. The final OpenAPI has49 paths/59 operations/54 named schemas; independent OpenAPI3.1 validation succeeds. Remote CI and a real new RC artifact have not been executed. Existing `.2`/`.1`/`.20261007.1` ZIP hashes match their original records.

The ordinary independent verifier disables cache creation before importing its packaged version policy; the default Python command is tested without relying on `-B`. API-document Gradle input tracking was added and the final three API tests passed again; that focused rerun replaces the server's on-disk XML with only those three reports, so the prior843 forced run remains the dated full result, not the count of current focused reports.

Gitleaks8.30.1/redact100 exact tracked/unignored source snapshot:587 files, zero findings. An initial entire-workdir scan included5.82GB of ignored builds/historical acceptance and reported674 matches, all outside the exact source-candidate set; raw reports remain private, not silently suppressed or treated as source findings. This check does not replace final exact-artifact/history/dependency/image scans. Repository571 text candidates/1515 local links/three locks/Wrapper/V1–V16 passed.

Remaining software-delivery work: review/commit intended sources under explicit user authorization, choose exact RC/release ID and storage/identity trust method, strict clean build/publish/scan/package for that ID, independently extract/consume/build and run final supported OIDC/browser/maintenance/upgrade exercises. Public hosting/CD and OpenID certification are not silently added gates; reproducible traceable verification is required. Target-production opening is a separate P06–P10/P13–P19 scope.
