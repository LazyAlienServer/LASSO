# Changelog

## Unreleased

- Standardize application/internal API errors as Problem Detail, including security/Servlet failures and password errors; API authentication failures return 401 rather than HTML redirects. Request IDs cover early security responses.
- Require UTF-8 application JSON and internal Basic credentials; reject invalid encodings, scalar-to-string coercion and null client-list elements. Internal Basic challenges now declare UTF-8 and return a Problem body.
- Set Servlet response encoding to UTF-8 before protocol security filters obtain a writer, fixing JWKS responses that previously advertised ISO-8859-1 despite an ASCII public-key body.
- Advertise actual scopes, supported grants and Back-Channel Logout in Discovery. Successful DTOs and native OAuth/OIDC/monitoring formats remain supported.
- Consolidate Chinese/English guides into docs/, OpenAPI into api/.
- Replace duplicate guide sets and old status diaries with current procedures, ADRs and selected archived evidence.
- Package shared tools/templates via an explicit file manifest rather than rewriting script/document text.
- Maintenance wrappers require explicit JarPath/ExpectedSha256, default production; local development passes Profile local. Source/package scripts are identical.

## 0.1.0-rc.1

[Release](https://github.com/LazyAlienServer/LASSO/releases/tag/v0.1.0-rc.1) · [Evidence](https://github.com/LazyAlienServer/LASSO/blob/7f2b905e7d6b4161d1bf7d380558ead8e8415f2b/release/verification/0.1.0-rc.1.json)

- Refresh owner-client validation before family side effects.
- Implemented OpenAPI, RC/stable tooling and bilingual procedures.
- Standalone single external Nginx deployment and private metrics guidance.
- Known image issue: inherited pebble High findings; see version evidence.

Earlier local deliveries: [archive](https://github.com/LazyAlienServer/LASSO/tree/main/docs/archive). Dates/test counts apply only to original source/artifacts.
