# Grafana Import And Mixed-Load Acceptance — 2026-10-07

- [Chinese](../zh-CN/operations/grafana-mixed-load-2026-10-07.md), [delivery .7](../integration/development-delivery-2026-10-06-7.md), [refresh/logout repair](../implementation/refresh-logout-hint-history.md).
- Actual same-workstation acceptance with final hashed .7 JARs. Controlled ten-minute exercise, not saturation, production sizing, long-term memory or multi-instance certification.

## Grafana

Grafana12.2.0 image digest `sha256:74144189b38447facf737dfd0f3906e42e0776212bf575dc3334c3609183adf7`, separate disposable container/network, loopback3300. Anonymous access/sign-up disabled, random external administrator credential used only for API provisioning. Import used the unchanged repository dashboard SHA-256`f4e735ddd45c666fbe950e3585ea2544db34dcf67bfc23aea3a80866bf713141`, a server-side private Prometheus datasource, and a separate Viewer account.

Headless Edge/Playwright authenticated as Viewer at1440×1000 and390×844. Eight headings/panels rendered with real query data, canvas pixel variation, no query/page errors or horizontal page overflow. Mobile scroll explicitly mounted Grafana's lazy panels. Viewer could read dashboard but API save/overwrite and datasource creation returned403; canEdit/canSave were false. Desktop/top and lower mobile screenshots retained in approved private temporary evidence, without tokens/credentials. This is actual imported/rendered dashboard acceptance, not merely ten syntactically valid expressions. It does not certify production SSO for Grafana, every Grafana permission/plugin route or upgrade compatibility.

## Topology And Workload

Final run **2026-10-07 00:36:27–00:48:02 Asia/Shanghai**, including setup/browser checks; default301.154s, application-owned302.178s. Actual privateDockerPG18.4, Temurin21 container JARs, Nginx TLS include, fixture CA/SNI-validating HTTP clients and JVM truststore, browser resolver and one leaf-SPKI exception. SSO/BFF/DB ports unpublished; only fixture proxy/Grafana/Prometheus map loopback. SSOheap1GiB/BFF512MiB/pool10 and five explicitly seeded independent Argon2 identities are test conditions, not production defaults/registration acceptance. Per mode four subjects each have two separate browser sessions plus an unrelated sentinel: nine browser sessions total.

Closed-loop workers pace at800ms, send protected/session requests concurrently, perform periodic Basic eligibility queries, current logout and SSO reentry at80s intervals, global logout/fresh password+Code/PKCE/Token/UserInfo at140s intervals. Fixture client AT lifetime45s and BFFrefresh-before10s deliberately force actual refresh without waiting the production15minute AT lifetime. No production policy/TTL was changed. Watcher verifies sentinel stays authenticated while other subjects logout. Same-subject twins are isolated until deliberate global logout.

An in-flight refresh during deliberate revocation may return401SESSION_EXPIRED rather than200anonymous snapshot. Only the post-logout terminal check permits that exact category; normal traffic still requires200/authenticated and no unrelated-session failure. Earlier probe treated it as a failure; that correction is separate from .6's reproduced400 IDhint defect. Initial mobile heading timeout was a lazy-mount probe error; earlier attempts and protocol diagnostics are retained separately from final passes.

## Results

12,598 successful operation observations across two modes, including initial login timings outside the main duration; not identical to rawHTTPrequest count. Composite browser operations include multiple redirects/requests. 48password/OIDC logins,24current logouts,24SSOreentries,16global logouts; five subjects/nine sessions per mode and sentinel retained throughout. No unexpected workload failures. Prometheus sampled120successful refreshes,0reuse,40successful Back-channel deliveries. Final DB40processedBack-channel,0pending/dead,0reuseevents,0internal-auth failure bucket rows; retained active SSO sessions/RT rows follow intentional SSO-preserving current logout and the sentinel, not a zero-resource claim.

| Observation | Default count / p95ms | Application-owned count / p95ms |
| --- | --- | --- |
| Password/OIDC login | 24 / 1204.78 | 24 / 639.77 |
| Session read (includes expected post-logout terminal checks) | 3198 / 24.11 | 3197 / 28.32 |
| Protected request | 2774 / 21.91 | 2760 / 25.55 |
| Eligibility query | 279 / 65.96 | 278 / 71.67 |
| Current logout | 12 / 345.04 | 12 / 411.57 |
| SSO reentry | 12 / 303.56 | 12 / 268.38 |
| Global logout to observed twin termination | 8 / 9819.47 | 8 / 9021.69 |

Global timing includes redirects, worker scheduling/polling, and observed terminal state; it is not an isolated HTTPdelivery latency or guaranteed Back-channel propagation SLA. Limited samples make p95 at8/12 operations approximate upper observed values. The .7 history correction allowed repeated global logout after real refresh to finish and fresh login to continue.

89process/SQL samples: SSO container memory406.9–926.2MiB (first406.9,last637.4), BFF309.3–402.8MiB (first309.3,last393.6), sampledDockerCPU peaks85.00%/40.35%, PIDs96/73. Container memory includes native/JVM overhead and is not heap usage; DockerCPU differs from logical-processor-normalized JMX. One generic RuntimeError sampling gap was retained during the run that also restarted BFF between modes; metadata is insufficient to conclusively attribute it. Sampled lock waiters0/DBconnections≤11. No OOM/crash was observed, but upward/warmup memory values and short duration do not exclude a leak or establish capacity headroom. No load ramp, largeDB query-plan stress, uncontrolled admission or multi-instance evidence.

## Evidence And Cleanup

Private runtime summary SHA-256`ef41bee420b87e2fa7d1eb405b2ef0b614bf99b67dfa01959e2625427595f37a`; default report`fbd67f0a55c0c39ac833137ea2111b134fba40d50ddf8af5f48d1d0b92286a0d`, application`6b41b1889fe5b271f0e93223964bcfa59df0eecb6ca503f3fc4a06679f5e05fb`, browser`726445836ff13520c80bafa91bb2210c8e1dcad538d7d6a61f4eb9e1dcc86720`. [Manifest](../integration/development-delivery-2026-10-06-7.json) binds final artifact/source/test inputs; older .6JARs preserved.

DedicatedGrafana/Prometheus/TLSapp/proxy/DB containers, DBvolumes/network, account/password/collector/admincredential files, signing/TLS/trustkey files removed;9443/9080/9092/3300free. Rawlogs/screenshots/reports remain approved private temporary evidence, without durable retention guarantee. Targeted final logs found no fixturepassword/pepper/clientsecret/admincredential/JWT/Bearer/Cookie patterns; this is not all possible log auditing. No globalCA/hosts install or productiondatabase/deployment/commit/push.

Next: production Grafana auth/retention/backup/security updates and privateTLScredentialrotation/oncall, hours-scale mixed ramp/soak with realistic hardware/network/database volume and metric overhead, history table lifecycle sizing, remaining UI/domain/external probes and formal release. No production readiness declared.
