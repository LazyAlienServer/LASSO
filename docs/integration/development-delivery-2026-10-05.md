# Identified server/starter development delivery — 2026-10-05

Historical .1 snapshot: real reference-BFF acceptance subsequently found a UserInfo failure. Use [delivery .2](development-delivery-2026-10-05-2.md) for the repaired server/browser evidence; this manifest is not the current-source fingerprint.

- Delivery ID: **0.1.0-dev.20261005.1**; local development compatibility tuple, not a hosted immutable production release.
- Chinese counterpart: [可辨识服务端/Starter开发交付](../zh-CN/integration/development-delivery-2026-10-05.md).
- Machine-readable provenance: [manifest](development-delivery-2026-10-05.json).
- Scope: SSO-03 public reauthentication customization/verified OIDC evidence access, SSO-06 local identified artifacts, and SSO-04 server epoch contract. Consumer business storage/permissions/high-risk operations remain external.

## 1. Coordinates and provenance

| Item | Development tuple |
| --- | --- |
| Server | `las-sso-server-0.1.0-dev.20261005.1.jar` |
| Starter | `org.las:las-sso-spring-boot-starter:0.1.0-dev.20261005.1` |
| Reference BFF | `las-sso-reference-bff-0.1.0-dev.20261005.1.jar` (sample only) |
| Toolchain | Java 21, Boot 4.1.0, Framework 7.0.8, Security/AS 7.1.1, Nimbus JOSE 10.9.1/client SDK 11.38.2 |
| Database | Existing Flyway V1–V14; no new migration for this delivery |
| Default version behavior | Remains 0.1.0-SNAPSHOT without the explicit build properties |

Source base commit is `8cee3ed62fd7c53664e8026b99664c73fae5f19d` on `codex/fix-sso-quality-review`, **plus uncommitted worktree changes**. HEAD alone cannot recreate this artifact. The manifest fingerprints 263 relevant source/build/test/Gradle files using sorted relative-path/NUL/file-SHA256/LF rows, aggregate `96c47532d86d04efd33006ca23db66ddde65f36a3427ac309e178579a959d846`. It excludes docs, build outputs, workstation init scripts/external configuration and secrets. File paths in the manifest are relative to `las-sso-server/`.

| Artifact | SHA-256 |
| --- | --- |
| Server boot JAR | `c4957310e2f5bd33f99aaf41634f7ea94225bb3ebfdd230143a82975a6e8d587` |
| Starter JAR | `513874a0e5cb8d6918c7649617ca795c8bb3b7c2866f5ce6d4292717b50d05b5` |
| Reference BFF boot JAR | `699b8491d5a5762d33db2588cee788aa0e7d00bff98b6ff6ab10e2a5abc8e9a3` |

The manifest also records POM/module and three lock hashes. These identify the actual local build; they are not a digital release signature or a source commit. Do not overwrite the delivery ID with changed content: choose a new identifier/recompute provenance. A rebuilt JAR may have a different byte hash even from equivalent source; compare source fingerprint as well as artifact hash. Production Secret/issuer/key/client configuration is not in this tuple.

## 2. Public authorization customization — SSO-03

Existing `configure(http, applicationSuccess)` remains compatible. A new public overload accepts `Consumer<OAuth2AuthorizationRequest.Builder>` and applies it after Spring's PKCE customizer, before local transaction/return-target attributes. It is application-owned configuration, not browser query forwarding:

```java
las.configure(http, applicationSuccess, builder ->
    builder.additionalParameters(parameters -> {
        parameters.put("prompt", "login");
        parameters.put("max_age", "0");
    }));
```

Use one overload once per chain. The simple example forces password authentication for every authorization on that configured chain; it is not a new `/reauthenticate` consumer endpoint or a recommended blanket ordinary-login policy. Applications that need per-operation requests must establish their own pending local transaction and trusted standard resolver/customizer logic; do not forward arbitrary request parameters. The customizer must preserve client/redirect/state/nonce/PKCE attributes; replacing Spring's builder fields is application-owned behavior, not a safe automatic merge guarantee.

After successful Spring issuer/audience/signature/time/nonce/Code/PKCE validation and LAS session registration, the application success handler can access `OidcUser.getIdToken().getAuthenticatedAt()` (verified `auth_time`), stable subject and `sid`. It must independently correlate that result to the initiating local browser/login session/pending operation, reject missing/future/stale evidence, retain the actual signed evidence time and apply its own fifteen-minute policy/expiry/revocation. Ordinary callback or refresh does not establish recent password evidence; refreshing does not advance the old Authorization's `auth_time`. No high-risk action is automatically replayed. No account-level sharing/MFA/business permissions were introduced. The default login path ignores browser-supplied prompt/max_age/auth_time; the public customizer explicitly supplies trusted values.

Two new signed callback tests exercise a package-external consumer chain with prompt=login/max_age=0, retained state/nonce/S256/returnTo, Code/PKCE HTTP exchange/RSA-JWKS, successful delegate's verified auth_time access and immediate logout; the other confirms default untrusted query parameters are not forwarded. The OP is a signed loopback fixture, inbound Servlet/lifecycle dispatch is fixture, and auth_time is created by that OP fixture. They do not demonstrate a live LAS SSO/browser/local fifteen-minute consumer gate. The existing server reauthentication/JDBC tests verify the SSO prerequisite separately. No browser-facing response adds Token/auth_time; test-only headers are fixture output, not production API.

## 3. Query compatibility — SSO-04

The development server query now includes `stateEpoch`. Update strict DTOs and use versions only within `(issuer, subject, stateEpoch)`; unknown subjects also include the current epoch. Epochs are opaque equality namespaces, not sortable UUID/time. Restore requires new global security epoch before serving; consumer reconciliation must retire old caches/in-flight replies and establish current generation safely. Missing/blank epoch is 503/no-store, not a synthetic allowed/unknown result. Full contract, seven-field shape, reason priority and restoration obligations: [query record](../implementation/service-access-epoch-contract.md), [Chinese](../zh-CN/implementation/service-access-epoch-contract.md), [ADR-0007](../adr/0007-scope-service-access-versions-to-security-epoch.md).

Existing five-minute SSO challenge/V14, B4 `process` SPI/legacy accept-only 503, B5 timestamped state/returnTo and no business claims remain applicable. Existing consumers must preserve their adapter contracts. This delivery adds a public method overload and query field, not new stored token/credential data.

## 4. Build and actual local artifact consumption

From `las-sso-server/`, Java 21/Docker:

```powershell
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261005.1" "-PlasSsoStarterVersion=0.1.0-dev.20261005.1" --rerun-tasks --no-daemon --console=plain

.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261005.1" --no-daemon --console=plain
```

The actual isolated file-backed development repository was created under the pre-approved temporary directory; no remote credentials/global Maven Local were used. It contains this exact coordinate/JAR/POM/.module. An external two-project Gradle consumer used native Boot 4.1.0 platform, no SSO composite substitution or inherited locks: one consumed `.module`, one POM-only with Gradle metadata redirection disabled. Both **compiled package-external source using the old/new public configure signatures**, and resolved all Security modules at 7.1.1. This is real artifact consumption/compile/resolution, not an actual Maven CLI, live BFF startup or full application integration. The machine-specific temporary repository is not persisted as a project default; for another workstation publish/transfer the identified artifacts into an explicit development repository and verify hashes. Server is a boot JAR, not a starter dependency.

Forced build on 2026-10-05 approximately **01:45–01:49 Asia/Shanghai**: **460 server / 178 starter**, zero failures/errors/skips, **18 tasks executed**. Reference BFF recompiled, `test NO-SOURCE`; boot JARs/publication metadata generated with the delivery ID. S2 adds23, query contract17/load1 adds18, callback adds2 over historical419/176. S2 XML `17:46:24.196Z`, lifecycle17 `17:47:04.060Z`, load1 `17:47:10.736Z`, callback30 `17:49:06.338Z` (UTC date is 2026-10-04). Database tests actually ran. Worktree/source fingerprint and artifact hashes were captured after the build/local publication.

## 5. Delivery limits and external inputs

SSO-03 public source/compiled artifact customization and verified-token access are delivered at the tested layer; relying-party pending transaction/callback/expiry/revocation remains external. SSO-06 now has an identified local development tuple, hashes/locks/source provenance/commands and two artifact consumption modes. It still lacks committed source/release signature, hosted immutable distribution, live consumer acceptance and production release.

SSO-04 has server lifecycle/epoch/wire and a small workstation eight-worker/80-query observation (59.57rps/p95 203.5ms), not full production SLA/real restore/cache coordination. SSO-05 still needs external exact reachable callback/logout URIs before dedicated registration. B1 persistent reliable acceptance/notification ordering, actual dual-browser/SSO/ref-BFF operation, production TLS/proxy/keys/backup/crash/capacity/scanning remain independent. No commit/push, production database, hosted release or deployment occurred; only identified local development publication.
