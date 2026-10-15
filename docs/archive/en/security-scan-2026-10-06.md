# Historical security scan and dependency remediation — 2026-10-06

- [Chinese](../zh-CN/security-scan-2026-10-06.md), [delivery .20261006.4](https://github.com/LazyAlienServer/LASSO/blob/8390243505b781c25447fa4e125532f421f56b76/docs/integration/development-delivery-2026-10-06-4.md), [artifact/source manifest](../releases/development-delivery-2026-10-06-4.json).
- Scope: active SSO server, starter and reference BFF. Baseline is identified `.20261006.3`; remediation is a new `.20261006.4` combination. Earlier D1 consent-page repair did not establish that every dependency was safe.

## 1. Coverage and tools

| Check | Method and actual coverage | Baseline → remediated result |
| --- | --- | --- |
| Dependency locks | Trivy 0.75.0 `fs --scanners vuln --list-all-pkgs`, all three actual lockfiles, including runtime/test entries | 232 → 234 package rows; 33 → 0 finding rows, 17 → 0 distinct CVEs |
| Packaged libraries | Extract exact `BOOT-INF/lib` entries from both hashed boot JARs; include plain starter; Trivy `rootfs --scanners vuln --list-all-pkgs` | 148 → 150 package rows; 32 → 0 finding rows, same 17 → 0 distinct CVEs |
| Source | Semgrep 1.179.0 local scan, `p/java`, `p/javascript`, `p/security-audit`; metrics/version check disabled | 219 → 221 scanned paths; one warning retained, zero scan errors |
| Secret patterns | Gitleaks 8.30.1, `--redact=100`: active source snapshot, three JARs (`--max-archive-depth 2`), Git history `--all` | Zero matches in each path, both snapshots |

The direct Trivy `fs` scan of opaque boot JARs returned **zero targets/packages** and supplies no dependency evidence. Explicit extraction addresses that limitation: new server has 84 nested libraries, BFF 63; scanner identifies 150 package records, which is not the same metric as 147 library files. Starter does not bundle its dependency graph, so its lock and independently resolved publication metadata matter.

The final active-source snapshot inventories 287 files: module sources/resources/tests, `gradle`, scripts, deploy inputs, build/settings/wrappers/locks and Compose. Source provenance uses the established 286-file scope, excluding Compose. Semgrep scanned-path count is a language/rule coverage metric, not all inventoried files; this is not PowerShell, SQL, Thymeleaf/template, bytecode or full interprocedural security verification. Gitleaks zero matches do not prove that local-only example credentials, arbitrary formats or ignored external files contain no secrets. History scan includes repository history, not every ignored/untracked file in the wider LAS workspace. No finding source, credentials, Cookie, Token or private key is included in this record.

Trivy DB schema2: `UpdatedAt=2026-10-06T01:08:27.80517672Z`, `DownloadedAt=2026-10-06T05:19:56.75879896Z`; the same DB was used before/after (`--skip-db-update`). No severity filter, ignore file or `--ignore-unfixed` was used. Database completeness and future advisories remain independent of this result.

Gitleaks Windows archive SHA-256 verified: `d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e`. Semgrep rulepack SHA-256:

- java: `5e652fa9ac09c9fac36bbadc3562a0c8eed1a47438a9d1f545e021ae3c5648b1`
- javascript: `e65e8449157ef5d587f2c2a0c17ed388910ca90336e514d27941ccd25543cf4e`
- security-audit: `b109a039df712f30c6d3e25e1e8358053fd0f1c91b92d0e8d2871cd141fe602f`

## 2. Dependency findings and action

Severity below is **scanner severity**, not a conclusion that LAS exposes the relevant vulnerable feature. For example, Spring rates CVE-2026-47884 MEDIUM and requires XsltView/mapping conditions, while this DB labels it CRITICAL; Tomcat's DIGEST/FORM issues also have lower vendor ratings and specific container-authentication conditions. Upgrade avoids retaining affected packaged versions without inventing exploit reproduction or blanket application RCE claims.

| Dependency | Distinct baseline advisories | Scanner severity | Resolved action |
| --- | --- | --- | --- |
| Log4j API | CVE-2026-49844 | MEDIUM | 2.25.4 → 2.25.5, Boot BOM |
| Tomcat embed core | CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 | CRITICAL | 11.0.22 → 11.0.26, explicit app override |
| Bouncy Castle provider | CVE-2026-8763; CVE-2026-13506 | CRITICAL; HIGH | 1.84 → 1.86 |
| pgJDBC | CVE-2026-54291 | HIGH | 42.7.11 → 42.7.13, Boot BOM |
| Spring MVC | CVE-2026-47884 | CRITICAL | Framework 7.0.8 → 7.0.9, Boot BOM |
| Jackson core | CVE-2026-89407, CVE-2026-89425 | HIGH | 3.1.4 → 3.1.7 |
| Jackson databind | CVE-2026-68497, CVE-2026-91776, CVE-2026-91777 | HIGH | 3.1.4 → 3.1.7 |
| Jackson databind | CVE-2026-19032, CVE-2026-59889, CVE-2026-77310, CVE-2026-83557 | MEDIUM | 3.1.4 → 3.1.7 |

Boot moves 4.1.0 → **4.1.1**, preserving the supported minor line and Security/Authorization Server **7.1.1**, Nimbus JOSE10.9.1/OIDC SDK11.38.2. Boot's Tomcat11.0.24 predates later vendor fixes, including the 11.0.26 HTTP/2 regression fix, so both executable apps override all embed modules to11.0.26. Jackson BOM3.1.7 maintains the existing3.1 patch family (annotations2.21); no mixed core/databind patch. Bouncy Castle1.86 incorporates later vendor hardening beyond the DB's1.85 minimum. Related BOM changes include Spring Session4.1.1, Logback1.5.38, Micrometer1.17.1 and test libraries; three locks were regenerated and actual packaged entries checked.

Primary release/advisory references checked:

- [Boot4.1.1 dependency BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom)
- [Tomcat11 vendor advisories](https://tomcat.apache.org/security-11.html)
- [Spring CVE-2026-47884](https://spring.io/security/cve-2026-47884)
- [pgJDBC security/channel-binding fix](https://jdbc.postgresql.org/security/)
- [Bouncy Castle1.86 release](https://www.bouncycastle.org/download/bouncy-castle-java/)
- [Log4j API CVE-2026-49844](https://logging.apache.org/security.html)
- [Jackson core3.1.7](https://github.com/FasterXML/jackson-core/releases/tag/jackson-core-3.1.7), [databind3.1.7](https://github.com/FasterXML/jackson-databind/releases/tag/jackson-databind-3.1.7)

## 3. Retained source warning

Rule `out.java.lang.security.audit.unvalidated-redirect.unvalidated-redirect`, WARNING, `src/main/java/org/las/sso/security/LoginFailureResponseJitter.java:45`. Both immediate and scheduled branches redirect to the method argument. The sole production caller is `LasAuthenticationFailureHandler.java:68`: destination is the container context path plus fixed `/login?error`, not a browser parameter, saved request, header-provided URL or arbitrary return target. The other caller is a test with a fixed string.

Disposition: **false positive for the current call graph**, manually reviewed; scanner warning remains in raw report. No suppression/disabled rule, invented allowlist repair or test-count inflation. Container context/forwarding trust still requires controlled deployment; the `.3` forwarding defect and fix have their own acceptance record. A new caller passing user input requires re-review.

## 4. Verification and evidence handling

Full forced Java21/Docker build **2026-10-06 13:41:00–13:45:20 Asia/Shanghai**, 20 tasks executed: **486server +193starter +37reference BFF =716**, zero failures/errors/skips; actual PostgreSQL fixtures ran. Existing renderer/consent, persistence/serialization, real root/context Tomcat HTTP, OIDC/reauthentication, callback/logout and durable JDBC suites passed on the upgraded graph. Both boot JARs and publication metadata generated; isolated native Gradle-module and POM-only consumers compile old/new public APIs and resolve Boot4.1.1/Framework7.0.9/Security7.1.1/Log4j2.25.5. No new production Java or migration was needed for this dependency remediation.

Baseline and final raw reports/inventories/build execution logs remain separately in local approved temporary evidence directories `sso-security-scan` and `sso-security-scan-20261006-4`. They are not committed artifacts, have no long-term retention guarantee and must remain private because SAST reports can contain source. Remote requests fetch rules/DB/artifacts only; local source scan has metrics disabled. Scanner containers use `--rm`; Docker has no remaining running fixture containers. An executable rewrite racing with Gitleaks caused a temporary preparation error; preparation now checks identical bytes before writing, final inventory was regenerated, and secret scans reran against it. This was tooling, not a product finding. Known Testcontainers/Hikari shutdown warnings do not alter the zero-skip XML results or establish production capacity.

Final report SHA-256 anchors:

| Report | SHA-256 |
| --- | --- |
| Baseline three-lock SCA | 8661ba72ba528cea1202eec37cd411db39a9a4d3b93793ebf7db178a4776a461 |
| Baseline extracted-JAR SCA | bcb38e95c8135d5366aeeebdfdb62ef13ca9dff39b9a25400b352cc49d279ffa |
| Final three-lock SCA | 2b6ac4408300a1dde277b6431074a9f29c449cea1e52be8027361e8c21a9134c |
| Final extracted-JAR SCA | 261184b31e217cac3253ded98b51fff8bb61c79516b012a0c85f915c1d54d7ab |
| Final Semgrep | 0a230f68ecc24ee02b9547e8aafee176b540e3f7bce9cec1c05346580e272054 |
| Each zero-finding Gitleaks JSON | 37517e5f3dc66819f61f5a7bb8ace1921282415f10551d2defa5c3eb0985b570 |

This closes this identified active-build scan/remediation pass, not all security acceptance. Production image/OS/JDK/proxy scans, complete runtime log/ignored external-config review, authenticated DAST/full UI/accessibility, fresh advisory review at release, capacity/monitoring, signed committed distribution and external consumer adoption remain. Prior `.3` TLS/browser/maintenance/restore evidence is historical; this round did not repeat those external drills with `.4` JARs.
