# LAS SSO Project Status And Production Launch Plan

- Updated: 2026-10-11. [Chinese](../zh-CN/implementation/production-launch-plan.md).
- This is the current execution plan; [status history](status-issues-and-plan-2026-10-02.md) retains findings and evidence by date. Tasks below are planned, not already implemented or approved production changes.
- Scope: SSO server, starter, reference BFF and their deployment/operations. LASLOG/AlienCommon are external implementation owners; their evidence is required for launching those consumers.

## 1. Current Baseline

Latest immutable development package is [0.1.0-dev.20261009.2](../integration/development-delivery-2026-10-09-2.md): source297cdfc,834strict tests, V1–V16, independent ZIP/SDK/Docker/Compose evidence. Subsequent uncommitted [API/software-release preparation](api-release-readiness-2026-10-11.md) adds implemented OpenAPI/bilingual reference, stable/RC packaging and a reproduced refresh client-ownership correction; forced workspace843tests/zero skips passed. It is not a new immutable release. The older `.7` rows below remain historical, not the current source or count.

### Software distribution versus production opening

2026-10-11 steering: use **single external same-host Nginx** with loopback-only application publication ([ADR-0017](../adr/0017-single-external-nginx-container-edge.md)). Official domain/HTTPS will be supplied at formal target launch. Continue software candidate work now; do not wait for production DNS/certificates or mark those target checks completed. The user reports SSO/PostgreSQL started on their host; no target HTTPS/login/monitoring/restore acceptance has been returned.

This P01–P19 plan governs target production operation. Publishing a first installable **software release** does not require an operator's live public domain, production recipients, target backup or every consuming business site to have opened. Software gating is: freeze documented supported scope, fix demonstrated defects, review/commit exact sources, select a new version, clean forced build/publish, exact current scans and independent artifact/API/SDK/install/upgrade/maintenance/protocol checks, retain trusted immutable distribution and limitations. Final supported paths must have adequate evidence; raw test counts do not replace them.

P06–P10 and target P13/P14/P17–P19 remain necessary before public production use. P11/P12 apply only to the consumers actually opening. Remote hosted CI/CD, public Maven/GitHub and OpenID certification are not implicitly mandatory; repeatable source/artifact traceability and necessary verification are. Historical-RT successor revocation is an optional enhancement, not an inferred protocol/V1 gate; consumers still need proven local safety, reliable known-token work and explicitly assessed/accepted unknown remote-state risk. See the paired API preparation record. P18's formal-release wording below refers to production acceptance, not the downloadable software package.

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

Confirmed first opening: **SSO account site, single-host Docker Compose, same-host Nginx HTTPS, separate SSO/PostgreSQL containers, no initial CDN**. The user owns initial release/deployment/on-call/key/backup/manual recovery. Official domain/target hardware/traffic/RPO/RTO/backup and notification methods remain pending in the [input matrix](../operations/docker-compose-deployment.md). P11/P12 gate each actual consumer opening, not account-site-only launch. Local Docker acceptance is not target P06–P10 completion.

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
| P01 Release scope and environment inputs | **Scope/topology/owner confirmed; target values pending / Required** | S/K/R/D docs + O/X | None | 1. Account-site only, single-host Compose/Nginx/no CDN; user owns initial operations.<br>2. Supply official domain, hardware/traffic, backup RPO/RTO and channel.<br>3. Record limits, candidate/storage and window. | [Input matrix](../operations/docker-compose-deployment.md); no fixture sizing adopted |
| P02 Production configuration and startup gates | **Implemented in source / target environment pending** | S `config`, security, health; D launch inputs; Q | P01 scope; target values and topology still needed | 1. Compare runbook gates with code for issuer/key/pepper/client/Cookie/profile/schema.<br>2. Add bounded fail-closed validation and a tested maintenance/bootstrap startup path for missing admin, using existing policy; document any decision gap.<br>3. Test valid production config and missing/default/invalid values, non-web maintenance and local/test compatibility. | `production-startup-gates-2026-10-07.md`; focused JDK21/Docker PostgreSQL/PKCS12/servlet evidence. Actual domain/secret custody/target proxy/backup/consumer and formal launch evidence remain required |
| P03 Durable revocation and operator recovery contract | **Source/reference tools verified; consumer/target adoption pending** | K sink/handlers; R `revocation`; O/X; S outbox remains separate | P01 and P11 target adoption | 1. Verify accepted-work persistence, pre-submit failure/crash behavior and honest local/remote logout reporting.<br>2. Define bounded retention, dead-task inspection/manual recovery and queue-key rotation/draining; implement minimum missing sample safeguards with focused tests.<br>3. Publish the consumer adapter requirements and test restart/lease/DB/endpoint failures without exposing Token material. | [P03 record](revocation-recovery-2026-10-07.md):808 strict tests, reference V2 replay/retention/key races and real JAR/PowerShell maintenance. Pre-submit compensation and real consumer adapter adoption remain P11; SSO outbox has a separate recovery workflow |
| P04 Core UI and lifecycle completion | **Current source/browser supplement verified; target operators/conditional partner checks pending** | S templates/assets; Q browser; O real recovery/custody | P01 target inputs; P02 | 1. Reuse completed UI/DB/administrator evidence and supplement recovery/old-code/deletion/tombstone/promotion.<br>2. Verify desktop/mobile/keyboard/secret/error states and fix actual defects.<br>3. Apply user-approved two-independent-bases recovery records, credential custody and dispute runbook. | [P04 record](ui-lifecycle-acceptance-2026-10-07.md), [operations](../operations/manual-recovery-and-lifecycle.md); isolated production HTTPS/browser and maintenance evidence, tracked DOM regressions. Actual claimant/operator custody/training/target TLS and partner consent remain applicable launch checks |
| P05 Session/history growth and cleanup | **Source/isolation verification complete; target capacity/audit archive pending** | S history/session/transient/observability + V16; R P03 retention reused; Q | P01 target rates/hardware still needed | 1. Measure representative growth/query plans.<br>2. Implement user-approved90/30-day runtime retention with finite batches/timeout, protect live hints/RT and audit facts.<br>3. Verify migration/rollback/races/expiry/protocol continuity and publish status/vacuum/pepper guide. | [P05](history-growth-2026-10-08.md), [ADR-0015](../adr/0015-runtime-history-retention.md); synthetic SQL/current service tests, target rates/real payload/admission/archives remain P01/P08/P13 |

## 4. Phase B: Target Preproduction Environment

| ID / task | Status / launch effect | Modules / owner | Dependencies | Concise steps | Completion evidence |
| --- | --- | --- | --- | --- | --- |
| P06 Host, network, DNS and TLS | **Compose/local acceptance complete; target pending / Required** | D Docker/Nginx; S transport; O | P01 remaining values | 1. Provision isolated target Linux/network/public CA hostname.<br>2. Verify Docker private networks/host firewall/renewal and exact images.<br>3. Repeat canonical header/Cookie/Origin/unknown Host/private-port checks on target. | [Local record](docker-compose-acceptance-2026-10-09.md); target exact domain/CA/renewal/external checks remain required, no fixture certificate exception promoted |
| P07 Persistent secrets and signing-key custody | Pending / Required | S key/pepper/monitor config; K/R clients; D/O | P01/P02/P06 | 1. Provision independent persistent key/kid, peppers, DB/client/collector/queue secrets through protected inputs.<br>2. Verify filesystem/roles/mounts, absence of local seeds/defaults, restart stability and wrong-key failure.<br>3. Rehearse rotation/recovery effects and owner access without storing secret values in release evidence. | Persistent same-key restart/new-key recovery, no local profile or example credential in deploy, validated secret injection/custody/rotation procedure |
| P08 PostgreSQL V1–V16 deployment and rollback | Pending / Required | S migrations + persistence; D/O DB; Q | P02/P05/P06/P07 | 1. Deploy least-privilege runtime DB and versioned migrations into fresh and representative prior schema copies.<br>2. Validate V15 cascade/V16 index creation downtime, pool/timeouts/health and jobs.<br>3. Rehearse backup and schema-compatible rollback; older P02 binary rejects future schema, restore requires security barriers. | Target migration/permissions/index-time/health and safe rollback evidence; no production trial writes |
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

Next software work: complete the [external host-edge templates/checks](../../release/_source/docs/en/host-nginx-deployment.md), then review/commit under explicit authorization and select the clean candidate ID. Formal domain/HTTPS inputs wait for target launch by user request. Target P06–P10 use this selected standalone Compose when inputs are supplied. Existing P02–P05 and bundled Docker evidence remains historical; target capacity/custody/notifications/backup remain open. P11/P12 apply per future consumer.

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
