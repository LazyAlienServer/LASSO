# Acceptance And Evidence

[Index](README.md) · [中文](../zh-CN/acceptance.md)

## This Package

Run `python scripts/verify-package.py` from extracted root. It validates exact file inventory/SHA-256, no missing/extra files, safe relative paths, package-local documentation links, declared artifact/embedded migration hashes and aligned versions. ZIP checksums are transport integrity, not signatures/trusted release authority. The source ZIP is `git archive` of the recorded commit; SDK/JARs are built from that clean commit with explicit server/Starter version and all module tasks rerun. Reproducible file ordering/timestamps are used, but no independent two-builder byte-for-byte reproducibility claim is made.

`provenance/verification-summary.json` records actual command/tool versions/times/report hashes/module test counts and scanner coverage. Zero report errors/skips required. `provenance/dependencies/` contains three locks/catalog/Wrapper settings and actual nested JAR names/hashes; recognize opaque JAR scanners may see zero dependencies until extraction. Scans do not certify absence of all vulnerabilities; see actual tool/database/rule scope, findings and review limitations. Only executed checks are current evidence.

Earlier acceptance remains historical: `.7` real TLS dual BFF/initial-hint logout/Grafana Viewer desktop/mobile and two five-minute mixed runs, `.6` three actual alert firing/resolved notifications, `.5` administrator/restore rehearsals. Short45-second AT was a fixture; one sampled RuntimeError gap was not precisely attributed and short memory observations do not rule out leaks. None implies hours-scale/default-lifetime production soak or new-package real-process validation unless explicitly rerun.

## Deployment Gates To Execute

| Area | Required environment-specific evidence |
| --- | --- |
| Inputs/startup | Approved issuer/network/clients/owners; independent secrets, persistent key, no local defaults; manually closed ingress until admin initialized; complete startup gate task remains pending |
| Database | Fresh + representative upgrade V1–V15, backup/restore/checksums/permissions; DB outage readiness200→503→200 with liveness200 |
| TLS/browser | Actual CA/domain/proxy headers/direct-port denial, Secure Cookie/CSRF/Origin, two sites/login/consent/rename stable UUID |
| Lifecycle/admin | Recent-password/admin guards, freeze/unfreeze/deletion wait/hold/cancel/execute, manual recovery and auditable handover without last-admin bypass |
| Consumer security | Real15-minute originating-session evidence, refresh not advancing auth_time, one-minute eligibility denial and epoch/in-flight barriers |
| Refresh/logout | Parallel refresh coalescing/RT reuse rejection, local-only vs all-site logout after refresh, Back-channel repeated/late callback/receiver outage and actual local revocation |
| Persistence | Default restart loses business login as agreed; durable committed task restart/retry/dead-task/key custody; pre-submit gap handled explicitly |
| Observability | Real Grafana data/Viewer restrictions, actual on-call firing/resolved receipt; stale snapshot/outbox/reuse/admin/hash cases and alert ownership |
| Recovery | Real target backup/key restoration, new epoch/kid, old Cookie/code/AT/RT rejection and each consumer clearing old evidence |
| Capacity/security | Target JDK/image/proxy/DB scans and DAST, hours-scale ramp/soak/history-growth/cleanup/RPO/RTO; establish thresholds from measurements |
| Rollout | Candidate rehearsal, immutable trusted delivery, limited launch/rollback, assigned escalation and first operational cycle |

Record version, hashes, date, environment, operator, result and exception/follow-up for each gate. This development package is complete as a handoff, not a production-release gate waiver. SSO cannot accept the business application's implementation on its behalf.
