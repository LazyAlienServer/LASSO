# Final-artifact acceptance — 2026-10-06

- [Chinese](../zh-CN/operations/final-artifact-acceptance-2026-10-06.md), [current .5 delivery](../integration/development-delivery-2026-10-06-5.md), [manifest](../integration/development-delivery-2026-10-06-5.json), [capacity and readiness](capacity-readiness-2026-10-06.md).
- Scope: isolated single-instance SSO, default/application-owned reference BFF security chains and local maintenance/recovery. No production database or business consumer implementation.

## 1. Exact artifacts and repeated checks

The dependency-upgraded `.20261006.4` JARs first passed TLS/default+application-chain browser, administrator maintenance and restore drills. The subsequent bounded database-outage observation exposed readiness incorrectly staying200. This led to `.20261006.5`; **all three external drills were repeated with the .5 artifacts**, not merely inferred from .4.

| Final artifact | SHA-256 |
| --- | --- |
| SSO bootJAR | f845e54b7dcde555894a66d9a52f458c48baeb5cb95e4db3c5a7461461f3ae65 |
| Reference BFF bootJAR | 34af02ba84a1026c20171a35349bad49568ccd6b3985d57d5a3b92c6c2c8e78e |
| Starter plain JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

Each orchestration verifies manifest hashes before starting; maintenance scripts receive exact JarPath/ExpectedSha256. A later rebuild after improving the regression fixture did not change any of these runtime bytes; the final source fingerprint includes that final test. Runtime acceptance and source/test provenance therefore refer to the same final combination. Older IDs/JARs remain historical.

## 2. TLS and both BFF chains

Final browser runs **19:33:24–19:34:07 Asia/Shanghai**, 2026-10-06. Existing [TLS procedure](tls-proxy-acceptance.md) was reused: dedicated Docker network, PG18.4, Java21 containers, Nginx, independent short-lived CA/leaf and persistent RSA signing key; app/DB ports unpublished, proxy ports workstation-loopback only. Actual repository forwarding include and packaged reference `https` profile used. Node verifies fixture CA/SNI/hostname; JVM truststore verifies server-side HTTPS; Edge uses a process-specific resolver and one leaf-SPKI exception, not a global CA installation or ignore-all HTTPS.

Default and `application-security` BFF both passed:

- Discovery endpoint origins, canonical HTTPS callback despite hostile Forwarded/Prefix/Host/Proto/For/Port, fixed HTTP redirect, unknown-host421/HSTS/actuator404;
- actual ALTCHA registration, two independent same-subject password logins, Code/PKCE/JWKS/Token/UserInfo;
- host-only Secure/HttpOnly/Lax __Host Cookie, Path=/, no Domain, JavaScript cannot read it, browser does not send it over HTTP;
- browser/request-bound password reentry: other-browser401, foreign-origin403, wrong-password401 without increased evidence, correct signed later auth_time/stable sid;
- missing-CSRF logout403 retains login; current logout clears only its own BFF Cookie, other login and SSO retained; SSO reentry, global logout, forged return-state400, actual signed HTTPS Back-channel clears the other browser;
- observed proxy-IP CAPTCHA/budget and plain/encoded login refusal despite spoofed alternate forwarding headers.

Fixture thresholds2/3, budget clearing between modes, harness-injected reauth parameters, same-site test hosts and private CA retain their original limits. This does not validate public certificate renewal/CDN/multihop/cross-site topology, production20/50 window rollover, consumer pending-operation/15m gates or all UI flows. Eight final container logs passed targeted secrets/JWT/recovery/Cookie checks.

## 3. Administrator maintenance

Final run **19:52:45–19:55:07**. Real PowerShell entry scripts/Windows PTY hidden input and headless Edge against the final Java21 JARs and disposable PG:

- empty-DB bootstrap mismatch returns failure with state unchanged; success creates exactly one administrator in password-change-required state;
- two temporary-password browser sessions, management/account/OIDC/recovery gates, missing CSRF/foreign Origin/wrong password/mismatched confirmation refuse changes;
- successful forced change invalidates old temporary credential/other session, fresh login works;
- ten new80-bit recovery codes display once, actual download matches the displayed set and is deleted, reload does not redisplay plaintext;
- final-password dual BFF login, stopped-SSO maintenance reset cancel/mismatch/reuse/short-password leave snapshots unchanged; successful hidden reset invalidates recovery/session/authorization state;
- same-key SSO restart delivers actual Back-channel, old Cookie/AT/RT/password rejected, new password logs in, global logout clears both BFFs.

One .5 attempt during a concurrent forced rebuild failed a bootstrap transcript assertion before browser execution. A later isolated repetition passed; no persistent product defect or password exposure was established, and the failed attempt remains recorded. Populated-user promotion/deletion cancellation, live24h expiry, operator custody/printing and complete administrator UI/accessibility remain separate. Four final process logs passed targeted secret checks.

## 4. Backup and restore

Final browser run **19:41:17–19:42:32**, guard checks through **19:43:03**. Actual PG `pg_dump -Fc`/`pg_restore --exit-on-error`, two persistent PKCS#12 keys, exact-hash PowerShell expected-epoch command and real PTY confirmation:

- restored UUID/account/credential/client/admin/recovery facts preserved; pending outbox claim released;
- new epoch and signing key/kid, old SSO/BFF Cookie, pending Code/callback, AT and RT refused; lower restored counter is scoped to a new opaque epoch;
- original password permits fresh dual-browser Code/Token/UserInfo login; restored pending outbox and due-deletion fixture run after scheduling reopened; global logout delivers Back-channel;
- no-console, cancelled confirmation and repeated old-epoch command fail with security snapshot unchanged.

Generation-barrier logic and post-backup freeze/deletion timing remain explicit test fixtures, not a business application's cache barrier or complete browser deletion acceptance. This does not restore facts committed after the backup, test production backup media/RPO/RTO or make a relying party's cached old kid safe automatically. Twenty final restore process/orchestration logs passed targeted secret checks.

## 5. Build, scans and cleanup

Final forced build **19:43:45–19:49:43**, **487server+193starter+37reference=717**, zero failures/errors/skips,20tasks executed, PostgreSQL tests actually ran. The new real HTTP/PG regression checks readiness200→503→200, liveness remains200 and only coarse status is returned. Native-module and POM-only isolated .5 consumers compiled and resolved Boot4.1.1/Framework7.0.9/Security7.1.1/Log4j2.25.5. Publication is local-file only.

Final three-lock/extracted-JAR SCA:234/150 package rows,0 reported vulnerabilities; Semgrep221paths/one retained current-call-graph redirect false positive/0errors; Gitleaks source/JAR/all-history0matches. Source inventory288 includes Compose; established fingerprint scope287 excludes it. Same rules/DB/limitations as [scan record](../implementation/security-scan-2026-10-06.md); raw reports remain private temporary evidence.

Checks verified that all dedicated TLS/admin/restore/capacity containers and DB volumes, TLS network, detached apps and generated key/trust/cert/secret-setting/state files were removed. Ports9000/8088/9080/9443 free. No global CA/hosts modification. Old and new non-secret logs, scanner cache and inventories remain in private approved temporary storage without long-term retention guarantee. Runtime targeted checks cover8TLS+4admin+20restore+2capacity logs; they are not a claim that every possible runtime log has been exhaustively audited.

Current remaining work: actual production domains/cert renewal/firewall/CDN/deployment, production image/OS/JDK scanning, complete runtime logging/DAST/UI/accessibility, sustained load/monitoring/alerts, production backup/key custody, actual consumer adoption and committed/signed immutable publication. BroaderB1/custom durable Session/index/replay and explicit deferred capabilities retain their own scope.
