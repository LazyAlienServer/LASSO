# Spring Security consent-page patch and dependency delivery

- Status: D1 / CVE-2026-47877 dependency repair and controlled compatibility verification completed on 2026-10-04; full scanning/live release acceptance remains separate.
- Chinese counterpart: [Spring Security同意页补丁与依赖交付](../zh-CN/implementation/spring-security-consent-patch.md).
- Tracking: [status map §3.13](status-issues-and-plan-2026-10-02.md).

## 1. Verified advisory and scope

The [official Spring advisory](https://spring.io/security/cve-2026-47877/) identifies default-consent-page unencoded user-controlled values, affected Spring Security 7.1.0 and 7.0.0–7.0.6, with OSS fixes 7.1.1 and 7.0.7. A custom consent page is excluded by the advisory, but LAS SSO uses the dependency's default renderer for reviewed partners; no custom consent renderer was introduced as mitigation. The published [Security 7.1.1 BOM](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-bom/7.1.1/spring-security-bom-7.1.1.pom) includes Authorization Server and the other Security modules at the same patch.

The checked [7.1.0 renderer](https://github.com/spring-projects/spring-security/blob/7.1.0/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/web/DefaultConsentPage.java) directly concatenates values; [7.1.1](https://github.com/spring-projects/spring-security/blob/7.1.1/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/web/DefaultConsentPage.java) uses HTML escaping. The earlier application-level injection probe did not reproduce XSS and is not recast as a successful exploit. The new dependency-renderer regression isolates rendering using fixture converter/authentication results, proving a dependency defect without claiming that every fixture value passes LAS client/scope/UUID validation or that script execution was demonstrated in a browser.

## 2. Build alignment and reviewed locks

`gradle/libs.versions.toml` now declares `spring-security = "7.1.1"`. Server and reference BFF import the Security BOM after the existing Boot management. Starter locally overrides Boot's `spring-security.version` property and exports `api(platform(Security BOM))` plus its actual directly used config/OAuth2 Client/JOSE API dependencies with explicit catalog versions. These are existing transitive libraries, now explicitly declared for consumer delivery.

| Resolved coordinate family | Previous → current |
| --- | --- |
| Server's nine Security entries, including Authorization Server/test | 7.1.0 → 7.1.1 |
| Starter/reference BFF's eight Security entries each | 7.1.0 → 7.1.1 |
| Nimbus JOSE in all three modules | 10.9 → 10.9.1 |
| Nimbus OAuth2/OIDC SDK in starter/BFF | 11.37.2 → 11.38.2 |
| Exported Security BOM in starter/BFF locks | New 7.1.1 platform entry |

Three lock files were regenerated with Gradle, not hand-edited. The starter's four pre-existing test-Jackson additions were preserved; they precede D1. No other lock coordinate/version/configuration changed. Boot remains 4.1.0, Spring Framework 7.0.8, Jackson 3.1.4 and Java 21. Upstream Security metadata requests some newer Framework/Jackson/Micrometer versions, but existing Boot management remains authoritative for this checked tuple. This is an explicit patch override validated here, not a claim that Spring's whole Boot BOM was upgraded or that this is the upstream default combination. No migration, account/permission policy, secret, public route or production source change was required.

## 3. Published metadata compatibility

The initial BOM-only approach resolved the source build correctly but did not export the alignment into Gradle `.module`. Exporting the native platform fixed that format; an isolated POM-only consumer still selected Security 7.1.0. Direct versioned config/client/JOSE dependencies then made both consumers select the full Security graph at 7.1.1.

Two disposable file-repository publications (`0.1.0-d1-probe`, then `0.1.0-d1-probe-2`) were created outside the workspace with the existing publication task. A standalone Gradle probe consumed the latter alongside native Boot 4.1.0 platform management, without SSO composite substitution, SSO resolution rules or inherited locks. One repository used `gradleMetadata()`; the other used `mavenPom()` with `ignoreGradleMetadataRedirection()`. It asserted every resolved `org.springframework.security` module was 7.1.1. Both passed after the direct dependency correction. The initial POM-only 7.1.0 failure is preserved as phase evidence.

This proves those Gradle/native and Gradle/POM-only metadata paths, not an actual Maven CLI run (Maven was unavailable), arbitrary consumer dependency overrides/strict old locks or a hosted SDK release. Consumers still own their resolved BOM/version/lock review; the exported platform is a recommendation, not an enforced platform. No remote upload or global Maven Local publication was performed. Default starter/server version remains `0.1.0-SNAPSHOT` after the probe; final normal-version POM and module metadata were regenerated. Work remains uncommitted.

Repeat local publication into an explicitly disposable repository, with property arguments quoted on Windows:

```powershell
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$ProbeRepositoryUri" "-PlasSsoStarterVersion=0.1.0-d1-probe-2" --no-daemon --console=plain
```

The standalone probe has two subprojects, each declaring native `implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))` and the probe starter. Its task reads `runtimeClasspath.incoming.resolutionResult`, requires a nonempty Security module set and checks every version equals 7.1.1. Use a new probe artifact version if repeating with changed content. Do not use the temporary probe as a release coordinate.

## 4. Regression and packaging evidence

`DefaultConsentPageDependencyTest` adds **five** cases through the actual dependency endpoint filter/default renderer with controlled converter/authentication inputs: client ID, consent state, principal name, requested scope and previously authorized scope. Valid red run on 7.1.0: five failures from raw output; after 7.1.1: five pass, each unsafe string absent and escaped form present. The first attempt produced six 400 responses before rendering; it was fixture setup, not proof of XSS. Converter isolation corrected it and the unsupported user-code case was removed; device flow/request-URI rendering is not claimed covered.

`AuthorizationConsentIntegrationTest` adds **four** actual configured Spring security/provider/PostgreSQL/MockMvc cases: reviewed-partner approval and JDBC Consent persistence, Code/PKCE exchange/signed UUID/audience/nonce/RT, original caller state preserved in the redirect, cancellation without approval, another subject unable to approve and first-party no-consent behavior. A caller-state injection marker is not rendered because the actual consent flow generates its own consent state. This supports compatibility, not application XSS reproduction. Login uses the real password filter/encoder against registered test accounts; Servlet/browser transport remains mock.

Final focused server **110 = 5 + 4 + 10 reauth + 53 persistence + 2 JDBC session + 25 Basic budget + 11 client management**. Focused starter **131 = 28 signed callback + 10 session chain + 33 signed logout validation + 15 receipt + 45 return target**, plus reference BFF compilation. Zero failures/errors/skips. Full forced run at about **22:24–22:26 Asia/Shanghai**, 2026-10-04: **323 server / 176 starter**, zero failures/errors/skips; **18 actionable tasks executed**, BFF recompiled, `test NO-SOURCE`, server/BFF boot JARs and normal-version publication metadata generated. Nine added server cases over S6's 314. Final renderer XML timestamp `14:25:25.244Z`, consent XML `14:25:21.810Z`; database tests actually ran PostgreSQL 18.4 Testcontainers.

Commands from `las-sso-server/`, Java 21/Docker:

```powershell
.\gradlew.bat dependencies :las-sso-spring-boot-starter:dependencies :las-sso-reference-bff:dependencies --write-locks --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.config.DefaultConsentPageDependencyTest" --no-daemon --console=plain

.\gradlew.bat :test --tests "org.las.sso.config.DefaultConsentPageDependencyTest" --tests "org.las.sso.config.AuthorizationConsentIntegrationTest" --tests "org.las.sso.config.OidcPasswordReauthenticationIntegrationTest" --tests "org.las.sso.persistence.AuthorizationPersistenceTest" --tests "org.las.sso.session.JdbcSessionPersistenceTest" --tests "org.las.sso.security.FirstPartyClientRateLimitIntegrationTest" --tests "org.las.sso.admin.AdminClientServiceTest" --no-daemon --console=plain

.\gradlew.bat :las-sso-spring-boot-starter:test --tests "org.las.sso.client.LasSsoOidcCallbackIntegrationTest" --tests "org.las.sso.client.LasSsoSessionChainIntegrationTest" --tests "org.las.sso.client.LasSsoLogoutTokenValidationIntegrationTest" --tests "org.las.sso.client.LasSsoBackChannelLogoutControllerTest" --tests "org.las.sso.client.LasSsoReturnToTest" :las-sso-reference-bff:compileJava --no-daemon --console=plain

.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication --rerun-tasks --no-daemon --console=plain
```

DependencyInsight confirmed runtime selection/locks in all three modules. Inspection of both boot JAR `BOOT-INF/lib` lists found all included Security JARs at 7.1.1; server contains fixed Authorization Server, both contain Nimbus JOSE 10.9.1, BFF contains OAuth2/OIDC SDK 11.38.2. This is packaging evidence, not execution of those JARs. Early red test XML was overwritten by later runs; commands/output/status §3.13 retain phase evidence.

## 5. Remaining acceptance

D1's named dependency has been upgraded and the patch/checked compatibility verified. A1/A2 subsequently gained direct TCP/Tomcat root/context evidence in the [Servlet record](servlet-path-security.md), status §3.14; latest execution is 419 server / 176 starter, while 323/176 above preserves D1. Full SCA/SAST/secret/log scanning, browser script execution/real consent/CSP/TLS/proxy, persistent keys/live artifact/backup/restart/deployment and wider S2/B1 delivery remain separate. No other CVE/release closure is inferred. No schema/production DB/deployment/commit/hosted publication occurred. Preventive checks are renderer encoding regressions and artifact resolution, backed by runtime and packaging evidence.
