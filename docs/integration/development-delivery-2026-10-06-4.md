# Security dependency remediation development delivery — 2026-10-06

- ID **0.1.0-dev.20261006.4**; [Chinese](../zh-CN/integration/development-delivery-2026-10-06-4.md), [manifest](development-delivery-2026-10-06-4.json), [scan/method/findings](../implementation/security-scan-2026-10-06.md).
- Previous delivery IDs/JARs/manifests remain historical. This identified local combination includes dependency/build/lock changes following `.3`.

## Changes and verified scope

| Component | Actual resolved version |
| --- | --- |
| Boot / Framework | 4.1.1 / 7.0.9 |
| Security / Authorization Server | 7.1.1, unchanged |
| Tomcat embed modules | 11.0.26 |
| Jackson core / databind | 3.1.7 / 3.1.7, annotations2.21 |
| Bouncy Castle provider | 1.86 |
| pgJDBC / Log4j API+bridge | 42.7.13 / 2.25.5 |

The new apps package the above versions; three regenerated locks cover runtime and tests. Boot-managed accompanying updates are included. Public starter APIs, SSO V1–V14 and reference revocation migration remain unchanged; dependency remediation required no production Java change.

Baseline scan identified17 distinct CVEs (33 lock finding rows,32 extracted-JAR rows). Final Trivy0.75.0 identifies234 lock package rows and150 extracted-JAR package rows with0 reported vulnerabilities using the recorded same-date DB, no ignored severities/advisories. Direct opaque-JAR `fs` scan recognized no packages and is explicitly not counted as passed coverage. Semgrep1.179.0 scanned221 paths, one current-call-graph false-positive redirect warning retained,0errors. Gitleaks8.30.1 active-source/JAR/all-history scans each returned0matches with100% redaction. These are measured tool results within recorded coverage, not a universal no-vulnerability/no-secret assertion.

## Build and artifact consumption

From `las-sso-server/`, Java21 and running Docker:

```powershell
.\gradlew.bat dependencies --write-locks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:dependencies :las-sso-reference-bff:dependencies --write-locks --no-daemon --console=plain
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.4" "-PlasSsoStarterVersion=0.1.0-dev.20261006.4" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.4" --no-daemon --console=plain
```

Executed full build **13:41:00–13:45:20 Asia/Shanghai**: **486server+193starter+37referenceBFF=716**, zero failures/errors/skips,20tasks executed. PostgreSQL tests ran, including serialization/authorization and durable-revocation regressions; root/context HTTP tests exercise upgraded Tomcat. Both executable JARs generated. Known fixture shutdown pool warnings retain their scope.

Starter published only to isolated local file repository. Independent native `.module` and POM-only Gradle consumers compile old/new public APIs and verify Boot4.1.1/Framework7.0.9/Security7.1.1/Log4j2.25.5 without composite builds or inherited locks. The starter JAR bytehash intentionally remains the same because its Java/resources did not change; version and dependency publication metadata changed. Library metadata is not a guarantee that an application's own BOM/locks/overrides select patched Tomcat or Jackson: executable applications must check their final graph, as both samples do here.

## Provenance

Base `8cee3ed62fd7c53664e8026b99664c73fae5f19d`, branch `codex/fix-sso-quality-review`, **plus uncommitted worktree**. Source286files fingerprint `de9428231e852bd3c77453ec303018a2b9bd70ef0ba96ebbc209a29d89de3e8b`, same scope as `.3`; manifest12artifact/script/migration/config hashes and3new lock hashes. HEAD alone does not reproduce this content. Paths in manifest are relative to `las-sso-server/`, docs/build/external secret fixtures excluded.

| Artifact | SHA-256 |
| --- | --- |
| Server bootJAR | 2d6fdb9a86eeb54859ec031bfe417a8ab0ae7b3a2992eb4777b1fee5d49da94b |
| Reference BFF bootJAR | 7a49d0ff00cb105e7f4f43f42160a66fb4735c8b8fab8a3bf176fed19b0f8ad0 |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

Default versions remainSNAPSHOT; no earlier ID was republished. Prior TLS/browser/administrator/restore acceptance remains recorded against its original JARs; this round verifies dependency/build/tests/scans/metadata, without repeating those external drills on`.4`. All scanner containers were disposable and none remain running; private temporary reports/cache/inventories remain for local evidence. No commit/push, hosted/signature distribution, production deployment/database or business-consumer implementation occurred.

## Follow-up

Release-time advisory refresh and final application graph check, production container/OS/JDK/proxy scans, complete runtime log/external-config review, authenticated DAST/fullUI/accessibility, officialDNS/cert renewal/firewall/CDN trust, capacity/monitoring and actual consumer15m/epoch/revocation acceptance remain separate. Signed committed immutable publication and broaderB1/custom durableSession/index/replay/keyrotation are still open. This delivery closes the recorded active-build security remediation pass only.
