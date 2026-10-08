# Browser Interaction Regressions

From this directory: `npm ci`, then `npm test`. Node22 and installed Microsoft Edge are used by default; set `LAS_UI_BROWSER_CHANNEL` for another Playwright browser channel, or install the matching Playwright Chromium and set the channel to `chromium`.

These tests serve current source templates/assets through an isolated loopback HTTP fixture and stub only protocol responses. They exercise actual DOM/JavaScript/native dialog/clipboard state: one-time credential/Secret erasure, visible idempotent-error recovery, registration copy/download feedback and password cleanup. No SSO/DB/credentials or production server is contacted. This is not OIDC/TLS/domain integration evidence; actual JAR/PostgreSQL browser flows remain separately recorded in P04.

No traces/screenshots/auth payloads are saved. Do not configure real passwords or secrets for this fixture. The source does not depend on workstation tooling paths. Keep the lockfile tracked, `node_modules/` ignored.
