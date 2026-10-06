# UserInfo repair and real-browser development delivery — 2026-10-05

Historical .2 snapshot. Subsequent [delivery .3](development-delivery-2026-10-05-3.md) adds default sid registration-ordering protection and maintenance artifact selection. This manifest no longer matches current source.

- Delivery ID: **0.1.0-dev.20261005.2**, an identified local development combination.
- Chinese counterpart: [UserInfo 修复与真实浏览器开发交付](../zh-CN/integration/development-delivery-2026-10-05-2.md).
- Provenance: [JSON manifest](development-delivery-2026-10-05-2.json).
- Supersedes [delivery .1](development-delivery-2026-10-05.md) for the tested server/reference-BFF login path. The .1 manifest remains historical and does not match current source.

## 1. Discovered failure and repair

Real reference-BFF `openid profile` login exchanged the Code but failed the callback with `invalid_user_info_response`: Discovery's `/userinfo` redirected the server-side Bearer request to `/login` (302). The starter's signed OP fixture requests only `openid` and has Token/JWKS endpoints, so it did not exercise UserInfo transport.

The authorization-server chain lacked resource-server support. Adding JWT alone still failed: Spring Security7.1.1 auto-selected the application `ClientAuthenticationRateLimitConverter` bean as its resource-server `AuthenticationConverter`. That client-secret budget observer returns null and is not a Bearer parser. A targeted filter probe confirmed the correct chain contained Bearer/UserInfo filters but left the request unauthenticated.

`AuthorizationServerConfiguration` now explicitly configures `BearerTokenAuthenticationConverter` with the existing JWT decoder and a UserInfo Bearer401 entry point independent of HTML Accept. Spring's OIDC provider still checks active stored Authorization/access-token state and openid scope. Missing/malformed/revoked tokens and a browser cookie alone cannot read UserInfo. No dependency, lock, schema, scope or authentication-factor change was needed.

References: [Spring converter selection](https://github.com/spring-projects/spring-security/blob/7.1.1/config/src/main/java/org/springframework/security/config/annotation/web/configurers/oauth2/server/resource/OAuth2ResourceServerConfigurer.java), [OIDC UserInfo](https://openid.net/specs/openid-connect-core-1_0.html#UserInfo).

## 2. Regression and real browser

New regression: `OidcPasswordReauthenticationIntegrationTest.realIssuedAccessTokenCanReadUserInfoWithoutBrowserCookieAndRevocationStopsIt`. It uses real account/password login and Code/S256 exchange before reading UserInfo without a browser session. Repeatedly red before repair (expected200, actual302), still red with JWT-only configuration, green with the explicit converter. Expanded assertions verify GET/POST200, UUID sub/preferred_username/no email; anonymous and HTML-Accept401, anonymous WWW-Authenticate; cookie-only401; revoked and malformed Bearer401.

```powershell
.\gradlew.bat :test --tests "org.las.sso.config.OidcPasswordReauthenticationIntegrationTest.realIssuedAccessTokenCanReadUserInfoWithoutBrowserCookieAndRevocationStopsIt" --no-daemon --console=plain
.\gradlew.bat :test --tests "org.las.sso.config.OidcPasswordReauthenticationIntegrationTest" --tests "org.las.sso.config.AuthorizationConsentIntegrationTest" --no-daemon --console=plain
```

Focused combination: **15 cases (11+4)** at about03:36 Asia/Shanghai, zero failures/errors/skips; PostgreSQL18.4/Testcontainers actually ran. Tagged temporary filter/callback diagnostics were removed from repository source before the .2 build.

Real .2 boot-JAR processes use Java21, loopback HTTP issuer `http://127.0.0.1:9000`, reference BFF `http://127.0.0.1:8088`, disposable PostgreSQL18.4, local clients/ephemeral signing key and headless system Edge driven by Playwright1.55.1. Each run uses two independent BrowserContexts and a newly registered account. Both the **default chain** and **application-security profile** passed:

| Scenario | Actual assertions |
| --- | --- |
| Registration | Actual CSRF/CAPTCHA/registration API and ALTCHA widget solving; ten new four-group recovery credentials, no printed/retained plaintext |
| Dual login | Both password logins complete real Code/PKCE/Token/JWKS/UserInfo callbacks to/protected; same UUID, distinct BFF cookies, two active SSO sids/authorizations |
| Password reauthentication | prompt=login/max_age=0 reaches SSO password page; other-browser challenge401; wrong password issues no new evidence; correct re-entry completes the BFF callback with later signed auth_time and stable SSO sid |
| Current-app logout | A anonymous, B remains logged in, both SSO sessions active; A returns through ordinary top-level SSO without password entry |
| Global logout | A completes real SSO logout/return-state; B becomes anonymous through actual signed Back-channel delivery within the harness15-second wait; zero active SSO sessions |

The isolated harness adds standard reauthentication parameters to a BFF-generated authorization Location, preserving server-side state/nonce/PKCE. It privately inspects signed claims in the disposable fixture DB while the BFF validates the callback. This verifies SSO protocol, **not a consumer pending-operation/fifteen-minute high-risk gate**. Public trusted-customizer evidence remains the separate signed fixture/package-external API tests in .1.

After the forced build, the final manifest-hashed server/BFF JARs were restarted and both security-mode flows passed again. Temporary scripts/logs are under the pre-approved external `browser-acceptance` directory, not project defaults. The disposable services/database are stopped/removed after acceptance.

A targeted check of the final four process logs found no fixture password/client-secret canaries or Bearer/JWT/recovery-code/session-cookie value patterns. This limited check is not a complete source/history/production secret scan.

Early attempts exhausted the disposable registration limiter (429); only fixture buckets were reset. Playwright URL-route parameter injection initially left ordinary login at/protected; using the BFF-generated Location with explicit standard parameters corrected the harness. These setup failures were not additional production defects.

## 3. Identified artifacts and full checks

All three module versions are .2 with the properties below; defaults remain0.1.0-SNAPSHOT. FlywayV1–V14 is unchanged. Starter coordinate: `org.las:las-sso-spring-boot-starter:0.1.0-dev.20261005.2`.

Source is base `8cee3ed62fd7c53664e8026b99664c73fae5f19d` on `codex/fix-sso-quality-review` **plus uncommitted worktree**. HEAD alone cannot reproduce it. Manifest263-file source/build/test/Gradle fingerprint: `64bee2f4a7d0563aa7b43f64e31226132cd298fd5d1a56d2321b15be86df1365`. Paths are relative to las-sso-server; docs/external workstation configuration/secrets are excluded.

| Artifact | SHA-256 |
| --- | --- |
| Server boot JAR | `b1a0b4a98e2601cf96522c8b82d4b9a75b905e9e0480f3c4e75ea6f6bc85b8ba` |
| Reference BFF boot JAR | `d9d7ca250c4bd825572a67d2d53f73780084a50bd1ef4b97b9491662ae15d3c9` |
| Starter JAR | `513874a0e5cb8d6918c7649617ca795c8bb3b7c2866f5ce6d4292717b50d05b5` |

Starter's unchanged byte hash is intentional: the repair changes server configuration/regression, not starter classes. POM/module versions/hashes and three lock hashes are recorded separately.

From las-sso-server/, Java21/Docker:

```powershell
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261005.2" "-PlasSsoStarterVersion=0.1.0-dev.20261005.2" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261005.2" --no-daemon --console=plain
```

Forced build about **03:53–03:57 Asia/Shanghai**: **461 server+178 starter=639**, zero failures/errors/skips,18 tasks executed, both boot JARs/metadata generated. BFF has test NO-SOURCE; browser evidence is an external acceptance harness. Isolated file publication .2 and independent native.module/POM-only consumers recompiled package-external old/new public APIs and resolved all Security7.1.1, without composite substitution/inherited locks. This is actual artifact consumption, not Maven CLI/hosted release.

## 4. Remaining acceptance

Local HTTP/browser evidence does not verify production TLS/proxy/Secure-cookie topology, complete UI/accessibility, live refresh-expiry timing, durable starter work/replay/index, restart/multi-instance or notification-before-registration ordering. Consumer pending/fifteen-minute expiry/revocation/business permissions remain external. Actual backup/epoch barriers, sustained capacity, full scans, interactive administrator maintenance, dedicated external exact URIs, committed source and hosted immutable release remain separate.

.1 content is preserved; changed content is .2. Hashes identify local artifacts, not release signatures. No commit/push/production DB/remote publication/deployment occurred.
