# LAS SSO Project Status And Production Launch Plan

- Updated: 2026-10-07. [Chinese](../zh-CN/implementation/production-launch-plan.md).
- This is the current execution plan; [status history](status-issues-and-plan-2026-10-02.md) retains findings and evidence by date. Tasks below are planned, not already implemented or approved production changes.
- Scope: SSO server, starter, reference BFF and their deployment/operations. LASLOG/AlienCommon are external implementation owners; their evidence is required for launching those consumers.

## 1. Current Baseline

Repository follow-up: the previously verified functionality and documents are now committed locally (`1c287e9`, `f1f1093`), with engineering conventions (`09700e4`) and mechanical formatting (`b21304d`). See [engineering record](engineering-baseline-2026-10-07.md) / [Chinese](../zh-CN/implementation/engineering-baseline-2026-10-07.md). Rows below identify the historical `.7` delivery; current formatted source is not the same fingerprint or a new production release. The complete engineering build again passed735tests with zero skips.

| Item | Verified state |
| --- | --- |
| Latest development combination | `0.1.0-dev.20261006.7`; [delivery](../integration/development-delivery-2026-10-06-7.md), [manifest](../integration/development-delivery-2026-10-06-7.json) |
| Source at `.7` acceptance | Branch `codex/fix-sso-quality-review`, base `8cee3ed62fd7c53664e8026b99664c73fae5f19d` plus then-uncommitted worktree; 303-file fingerprint `ee911dc45866501f70ce9773fdc19228bf1fafd55b37f3ef82a5117c300fe0d2`; later local commits recorded above |
| Full tests | 505 server + 193 starter + 37 reference BFF = **735**, zero failures/errors/skips, actual PostgreSQL; these are results for this combination, not future changes |
| Runtime baseline | Java21, Boot4.1.1, Framework7.0.9, Security/AS7.1.1, Tomcat11.0.26, PostgreSQL18 fixtures, Flyway V1–V15; versions/locks remain authoritative |
| Scans | 241 lock/157 extracted-JAR package records, zero reported vulnerabilities in recorded DB; Semgrep233 paths, one reviewed redirect false positive, zero errors; three secret-pattern scans zero matches. Coverage/DB freshness limits remain |
| Delivery | Identified local JARs, POM/module and 18 artifact/config hashes; independent native/POM-only consumers passed. No committed immutable production release yet |
| Readiness | Development and isolated preproduction acceptance complete at recorded layers; **formal production release is pending** |

### Completed Evidence

| Area | Implemented and verified | Remaining boundary |
| --- | --- | --- |
| Identity/account | UUID principal, passwords, recovery, administrator/account lifecycle, JDBC SSO sessions | Complete public UI/recovery/operator and target-environment acceptance |
| OIDC/password evidence | Code/PKCE/JWKS/UserInfo, RT rotation/reuse, browser-bound password reentry, stable signed auth_time | Real consumer local-session/pending-operation/15m enforcement |
| Security repairs | A1–A3, S1–S6 and B2–B5 principal repairs; B1 session-owned Token core | B1 durable submission/recovery adoption and custom adapters; optional multi-instance remains separate |
| Refresh/global logout | V15 HMAC-only issued ID Token hint associations, typed lookup, authorization cascade; regression and actual refreshed global logout passed | Production migration, history size/cleanup and pepper rotation |
| Transport | Actual private Nginx/TLS/SecureCookie/negative forwarding checks on default/application BFF chains | Official domains/public CA/renewal/CDN/firewall/target topology |
| Maintenance/restore | Real hidden bootstrap/first change/offline reset and PG dump/restore/new key+epoch/old credential refusal | Earlier .5/.1 evidence is historical; final release and production backup/key/operator custody need rehearsal |
| Monitoring | Private stateless scrape, fixed metrics/cachedDB, three real alert firing/resolved paths; Grafana12.2 desktop/mobile Viewer import/render denial | Production transport/credential/Grafana/real recipient, remaining critical external probes |
| Workload | Two five-minute modes, five subjects/nine browser sessions each, 12,598 operations/120 refreshes/no reuse, sentinel retained | Controlled short-AT paced sample, not saturation/long-duration memory/production capacity |

### Known Gaps To Carry Forward

P02 source gates now validate explicit production profile/issuer/context/secrets/Cookie/Flyway/health and persistent RSA, then security epoch/admin/ACTIVE clients after migration; see [implementation](production-startup-gates-2026-10-07.md). They do not prove target TLS/proxy/secret custody or real domain ownership. Durable reference revocation commits accepted tasks, but a crash before submission can lose work; production retention/replay/key-rotation is not verified. Hint/session/authorization/audit growth, target sizing, client addresses, notification and backup inputs remain open. Temporary evidence is not long-term release retention.

## 2. Launch Scope And Task Conventions

- V1 is username/password, no MFA/email release requirement; SSO owns identity/account lifecycle/eligibility, consumers own business permissions and local password gates.
- Initial single-instance BFF restart ends business login; top-level SSO login establishes a new one. Persistent cross-restart login and multi-instance SDK are not V1 launch tasks.
- `SERVICE_ACCESS_CHANGED` remains deferred. Query reconciliation/active refresh and the consumer's one-minute observation/refusal requirement are still required; Logout is not eligibility synchronization.
- Listed modules are task targets, not a claim that a new implementation is needed everywhere. Prefer existing code/configuration; fix only gaps demonstrated during the task.
- Task completion needs evidence location/date/version/command/outcome and an accountable owner. For external work, attach the consumer/operator's evidence; do not silently mark it completed by reference-BFF tests.
- No calendar dates are invented before environment/owners are supplied. Finish dependencies before opening traffic. Commit/push/remote release or production DB/deployment execution requires the relevant explicit request.

### Module Key

| Key | Module/path and owner |
| --- | --- |
| S | `las-sso-server/src/main/{java,resources}`: identity/OIDC/security/account/admin/logout/observability and Flyway |
| K | `las-sso-server/las-sso-spring-boot-starter/`: reusable client security/session/refresh/logout APIs |
| R | `las-sso-server/las-sso-reference-bff/`: default/custom-chain and durable-revocation acceptance sample |
| D | `las-sso-server/deploy/`, `scripts/`: proxy/monitoring and maintenance deployment inputs |
| Q | Server/starter/reference tests and isolated real HTTP/browser/DB/failure/load harnesses |
| O | Operator-owned target infrastructure, secrets, backups, release storage and actual notification recipients |
| X | External LASLOG/AlienCommon BFF owners: own session, credentials for service queries, permissions/cache/database |

All tasks start **pending**. `Required` blocks the corresponding declared production scope; `Conditional` blocks only if that path/topology is selected; `After launch` is not part of V1 release gating. Final launch tasks necessarily include actual operational work.

## 3. Phase A: Scope And Local Completion

| ID / task | Status / launch effect | Modules / owner | Dependencies | Concise steps | Completion evidence |
| --- | --- | --- | --- | --- | --- |
| P01 Release scope and environment inputs | Pending / Required | S/K/R/D docs + O/X | None | 1. List first-launch consumers, routes/topology and owners.<br>2. Supply domains/issuer/client callbacks, server/DB sizing, expected traffic, backup RPO/RTO and alert recipient.<br>3. Record initial limits, release candidate/version and maintenance window. | Non-secret environment/owner matrix, capacity targets and scope frozen; secrets referenced by location only |
| P02 Production configuration and startup gates | **Implemented in source / target environment pending** | S `config`, security, health; D launch inputs; Q | P01 scope; target values and topology still needed | 1. Compare runbook gates with code for issuer/key/pepper/client/Cookie/profile/schema.<br>2. Add bounded fail-closed validation and a tested maintenance/bootstrap startup path for missing admin, using existing policy; document any decision gap.<br>3. Test valid production config and missing/default/invalid values, non-web maintenance and local/test compatibility. | `production-startup-gates-2026-10-07.md`; focused JDK21/Docker PostgreSQL/PKCS12/servlet evidence. Actual domain/secret custody/target proxy/backup/consumer and formal launch evidence remain required |
| P03 Durable revocation and operator recovery contract | Pending / Required outcome, sample use Conditional | K sink/handlers; R `revocation`; S outbox; O/X | P01; local work can start now | 1. Verify accepted-work persistence, pre-submit failure/crash behavior and honest local/remote logout reporting.<br>2. Define bounded retention, dead-task inspection/manual recovery and queue-key rotation/draining; implement minimum missing sample safeguards with focused tests.<br>3. Publish the consumer adapter requirements and test restart/lease/DB/endpoint failures without exposing Token material. | Durable accepted task recovery; no false remote-success claim; explicit remaining pre-submit gap/compensation and operational recovery, plus X adoption in P11 |
| P04 Core UI and lifecycle completion | Pending / Required for enabled routes | S account/admin/templates/assets; Q; O recovery workflow | P01/P02; local work can start now | 1. Complete real-browser recovery/old-code, deletion/cancellation, tombstone and promotion flows, wrong-Origin/CSRF/expiry cases.<br>2. Test keyboard/mobile/download/one-time secrets/error states and administrator password/temporary-expiry boundaries.<br>3. Record QQ manual recovery evidence requirements, administrator-code custody and account-deletion dispute procedure. | Released flows and accessibility checks pass; no repeated plaintext disclosure; recovery/operator checklist is usable; partner consent Conditional before partner entry |
| P05 Session/history growth and cleanup | Pending / Required bounded behavior | S OIDC/session/transient cleanup + DB; R queue retention; Q | P01 expected scale, P03 retention | 1. Measure session/auth/RT-hint/audit/outbox/queue growth and query plans with representative rows.<br>2. Specify retention/cleanup ownership and limits, preserving required audit facts and hint continuity for live authorization.<br>3. Test batches/expiry/cascade/rollback/races and polling cost; document pepper/key rotation. | Bounded backlog/growth at expected scale, no cleanup of live grants/audit outside policy, no history resurrection; new policy needs ADR before implementation |

## 4. Phase B: Target Preproduction Environment

| ID / task | Status / launch effect | Modules / owner | Dependencies | Concise steps | Completion evidence |
| --- | --- | --- | --- | --- | --- |
| P06 Host, network, DNS and TLS | Pending / Required; CDN/prefix Conditional | D Nginx include; S/R transport config; O | P01 | 1. Provision isolated preproduction equivalent to target host/network and public CA hostname.<br>2. Install canonical proxy/unknown-host refusal/firewall/private management listener and certificate renewal.<br>3. Exercise callback/Cookie/Origin/header spoof, inaccessible upstream/metrics and expiry/renewal on selected topology. | Actual exact domains/TLS/renewal and trust-boundary tests; no browser certificate exceptions; no unspecified multihop trust |
| P07 Persistent secrets and signing-key custody | Pending / Required | S key/pepper/monitor config; K/R clients; D/O | P01/P02/P06 | 1. Provision independent persistent key/kid, peppers, DB/client/collector/queue secrets through protected inputs.<br>2. Verify filesystem/roles/mounts, absence of local seeds/defaults, restart stability and wrong-key failure.<br>3. Rehearse rotation/recovery effects and owner access without storing secret values in release evidence. | Persistent same-key restart/new-key recovery, no local profile or example credential in deploy, validated secret injection/custody/rotation procedure |
| P08 PostgreSQL V1–V15 deployment and rollback | Pending / Required | S migrations + persistence; D/O DB; Q | P02/P05/P06/P07 | 1. Deploy least-privilege runtime DB and versioned migrations into fresh and representative prior schema copies.<br>2. Validate V15 indexes/cascade/history, pool/timeouts/health and scheduled jobs.<br>3. Rehearse pre-change backup and application/schema-compatible rollback; never assume an old JAR can use new schema or restore without resetting security state. | Migration/schema history, permissions, query/health results and safe rollback procedure; no production trial writes |
| P09 Production monitoring and real notifications | Pending / Required core coverage | S observability; D Prometheus/AM/Grafana; O | P06/P07/P08; reuse .6/.7 inputs | 1. Provision private scrape transport/credential rotation and Grafana auth/Viewer/retention/backup.<br>2. Wire readiness/snapshot/outbox/reuse plus disk/backup freshness/clock/resource alerts to the real owner, with retry/escalation.<br>3. Inject failure/recovery, verify delivered/resolved notifications and no high-cardinality/secret labels; prioritize missing domain metrics by released risks. | Actual recipient acknowledgement, failure/recovery/rotation records, private metric/UI access; full metric taxonomy may follow later, critical coverage cannot |
| P10 Backup, disaster restore and maintenance custody | Pending / Required | S maintenance/session/epoch; D scripts; O/X | P07/P08/P09 | 1. Configure encrypted scheduled/off-host backup, separate key custody, retention and failure notification.<br>2. Restore an actual preproduction backup to isolated DB; perform expected-epoch/new-key barrier, verify V15 cascade and permanent facts.<br>3. Clear consumer sessions/cache/old kid/evidence, test fresh login/outbox/deletion/reset using final candidate, measure RPO/RTO. | Actual backup recoverable, old credentials rejected, real X barriers complete, responsible operator can execute hidden-input recovery |

## 5. Phase C: Consumer Integration And Capacity

| ID / task | Status / launch effect | Modules / owner | Dependencies | Concise steps | Completion evidence |
| --- | --- | --- | --- | --- | --- |
| P11 Real client registration and initial BFF integration | Pending / Required per launched consumer | S admin/client/API; K; R reference; X owns business changes | P01/P06/P07/P08/P03 | 1. X supplies reachable exact callback/logout/backchannel URIs and intended audience/scope.<br>2. SSO registers independent clients and delivers configuration/secret method.<br>3. X integrates session-owned repository/security chain/reliable revocation adapter; test real Code/PKCE/state/nonce/refresh/current/global logout and process restart. | Separate consumer evidence: no cross-session Token mix, same-session refresh single-flight, restart ends business login, signed Back-channel and accepted revocation tasks recover |
| P12 Consumer password-evidence and eligibility gates | Pending / Required for protected/high-risk routes | S reauth/query contract; K supported hooks; X cache/session/permissions | P11/P10 restore contract | 1. X binds pending operation/state/nonce/PKCE/evidence to initiating local Session and expiry, validates signed auth_time; refresh cannot supply recent-password evidence.<br>2. Implement query expiry/active refresh/unavailability/refusal and fresh epoch reconciliation rejecting old cache/in-flight replies.<br>3. Test other-browser/expired/revoked/restart/freeze/deletion/restore and actual ≤1minute observation/refusal plus15minute recent-password rule, preserving business authorization. | Actual consumer gate tests; SSO reference parameters or fixture generation logic cannot close this task |
| P13 Target-hardware ramp/soak and resource limits | Pending / Required capacity evidence | S/K/R/D/Q; O target hardware; X realistic load | P05/P06/P08/P09; P11 for actual-consumer measurements | 1. Define representative user/data counts, concurrency/rates and acceptable latency/error/recovery limits.<br>2. Ramp mixed login/OIDC/default15mAT refresh/logout/query workloads, include errors/worker restart/DB or endpoint outage and hours-scale soak selected from risk.<br>3. Measure heap/native memory/GC/pool/locks/polling/history growth; tune admission/resources and repeat only changed/failed scenarios. | Declared traffic limit supported without sustained growth/exhaustion or identity/expiry leakage; measured capacity, not a fixed-hour or tiny-workstation test claim |
| P14 Release-environment security validation | Pending / Required | S/K/R/Q + D/O images/JDK/proxy/config | P06–P13; final scan repeated for P16 artifact | 1. Refresh advisory DB, scan resolved locks/extracted JARs and actual deploy image/OS/JDK/proxy; review unresolved findings by applicability.<br>2. Exercise authenticated DAST/negative protocol/Cookie/CSP/CSRF/Origin/headers and exposed-route authorization.<br>3. Inspect runtime/access/audit/notification/external config for secrets, use controlled canaries, repair actual failures and retain redacted evidence. | No untreated applicable Critical/High release finding; key negative paths/secret-boundary tests passed; zero-target scans cannot count as coverage |

## 6. Phase D: Release Rehearsal And Formal Launch

| ID / task | Status / launch effect | Modules / owner | Dependencies | Concise steps | Completion evidence |
| --- | --- | --- | --- | --- | --- |
| P15 Release candidate and final rehearsal | Pending / Required | S/K/R/Q/D + O/X | P02–P14 | 1. Select a new candidate ID after required fixes; run full tests with Docker, build JARs/metadata and hash exact config/migration inputs.<br>2. Run target-topology smoke, final administrator bootstrap/forced change/reset/recovery and backup restore, actual-consumer negative/expiry/restart and alert checks.<br>3. Reconcile evidence vs enabled release scope, rollback compatibility and unresolved limits. | Candidate-specific results, zero unaccounted DB skips, final exact artifacts/config tested; older .5/.6/.7 evidence not silently promoted |
| P16 Traceable immutable production delivery | Pending / Required traceability; public hosting optional | Repo/build/locks; K publication; D docs; O release storage | P15 | 1. With explicit authorization, commit intended source/docs/locks excluding local secrets/unrelated work and record revision.<br>2. Build controlled immutable server/starter combo; retain JAR/POM/module/migration/config hashes, provenance and review/signature method in restricted release storage.<br>3. Verify clean consumer resolution and retain release/security evidence beyond temporary folders; changed bytes need corresponding revalidation. | Commit + version + immutable bytes + retained manifest/rollback artifacts; internal controlled storage is sufficient, public Maven/GitHub publication not required |
| P17 Production deployment before public traffic | Pending / Required | D service/proxy/scripts; S migrations; O/X | P10/P15/P16 | 1. Back up, drain/close public traffic, install exact config/artifacts and migrate DB with accountable operator.<br>2. Initialize/verify unique administrator/clients, stable issuer/key, readiness and private monitoring; keep writers/jobs consistent with maintenance procedure.<br>3. Run production smoke and a rollback check before opening selected consumers. | Version/schema/config identity verified, no default secret/public management path, backup and rollback available, launch checks pass |
| P18 Limited opening and formal production acceptance | Pending / Required formal release | O release owner; S/K monitoring; X selected consumers | P17/P11/P12/P13/P14 | 1. Open only named tested clients/routes with declared initial traffic limits and observation period.<br>2. Observe login/refresh/query/outbox/DB/resources/alerts plus real-user flows; stop expansion on defined failures and execute tested rollback/recovery.<br>3. Owner records acceptance, environment/version/support contacts and any post-launch tracked work, then expands within validated capacity. | Explicit launch record and stable limited opening; no relying party opens business permissions based solely on SSO success |
| P19 First production operations cycle | Pending / Required evidence after opening | O + D monitoring/backup; S/K/X | P18 | 1. Confirm first scheduled backup, notification delivery and operational handoff.<br>2. Inspect history/session/outbox/queue growth and certificate/key/client rotation schedules under real traffic.<br>3. Record incidents, capacity headroom, residual tasks and next review without discarding launch evidence. | Initial operations report and successful backup/owner handoff; subsequent operational review date set by owner |

## 7. Execution Order And Parallel Work

```text
P01 -> P02/P03/P04/P05 (local completion; parallel where independent)
P01 -> P06 -> P07 -> P08 -> P09/P10 (target preparation)
P03 + target inputs -> P11 -> P12 (each consumer, external ownership)
P05 + target environment -> P13; P06-P13 -> P14
P02-P14 -> P15 -> P16 -> P17 -> P18 -> P19
```

Recommended next session: **P03 durable-revocation recovery**, then P04 core UI/operator workflows. P02 source implementation/tests are complete; target environment verification remains. Request P01 external inputs concurrently. P06 onward requires target inputs; P11/P12 require consumer evidence. A production consumer cannot bypass its own tasks. Gate tasks applicable to P01's declared first-launch scope; topology changes applicability, not historical facts.

### Inputs To Supply

| Input | Used by |
| --- | --- |
| Official SSO/first-consumer domains, issuer and exact callback/logout/Back-channel URLs | P01/P06/P11 topology, certificate and registration |
| Host/container layout, CPU/memory/JDK/DB size/network and CDN/proxy chain | P06/P08/P13 target environment and capacity |
| Initial user count/concurrency/rate and acceptable latency/error/recovery limits | P01/P13 workload and traffic limit |
| Backup location/retention, acceptable data-loss/recovery time and key-custody owner | P07/P10 RPO/RTO and restore |
| Operations/release/manual-recovery/on-call owners and reachable channels | P04/P09/P16–P19 real handoff |
| Consumer owners, first-launch routes and existing integration progress | P11/P12 external evidence, without taking over business implementation |
| Controlled artifact storage, release/version rules and maintenance window | P15–P18 distribution, rollback and limited opening |

Supply non-secret values and custody methods only; passwords, Tokens, private keys and recovery codes do not belong in this plan.

Do not create a new delivery ID for this documentation-only plan. Implementation/config/artifact changes use a new ID, full/focused checks proportional to risk and updated paired evidence. Tasks stay pending until actual work and verification complete.

## 8. Explicitly Deferred

| Item | V1 treatment |
| --- | --- |
| Independent eligibility events | Deferred; query compensation/reconciliation remains P12 |
| Multi-instance SDK/shared login retained across restart | Deferred; initial restart logout and reliable revocation adoption still P11 |
| MFA/Passkey/email/new identity protocol/business permission Claims | Not a V1 launch gate; maintain existing password-based evidence |
| Avatar uploads/arbitrary external media, multiple administrators/approvals, KMS/HSM | Later design/acceptance; not silently added |
| Full metric inventory/all optional panels/each possible alert live-fire | Later beyond required release-risk observability; actual critical recipient/backup/resource coverage still P09 |
| Fully atomic cross-storage local logout/revocation outbox | Separate design; document the pre-submit gap and implement required reliable accepted-task behavior in P03/P11 |

## 9. Progress Record

For each P-ID append owner, pending/in-progress/completed/blocker, changed modules/version/revision, commands/report location, accepted negative tests and residual scope. Gating failures remain blockers until fixed/retested; do not substitute an unverified dialog or a different stage's passing count. This plan reconciles current state with [readiness](implementation-readiness.md), [tests](test-plan.md), [deploy guide](../operations/sso-deployment-guide.md), [runbook](../operations/operations-runbook.md) and existing ADRs; it does not authorize production execution by itself.
