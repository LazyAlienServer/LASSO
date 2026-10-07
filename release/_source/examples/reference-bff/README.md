# Reference BFF — {{VERSION}}

Runnable sample `las-sso-reference-bff-{{VERSION}}.jar`, not a deployed LASLOG backend. Register its own confidential client and exact HTTPS callback/logout/Back-channel URIs, supply external identity settings, and put its loopback8088 behind a controlled TLS proxy.

```text
java -jar examples/reference-bff/las-sso-reference-bff-{{VERSION}}.jar --spring.profiles.active=https --spring.config.additional-location=file:/etc/las-reference/bff.properties
```

Default uses Starter's chain; add `application-security` for the application-owned example. Add `durable-revocation` and its independent DB/key only when explicitly selected. Templates are under [config](../../config/README.md). Development localHTTP/default client values exist inside the sample; never omit external HTTPS identity settings in a live environment.

Browser endpoints `/api/auth/login`, `/api/auth/session`, current/global logout and signed Back-channel receiver are demonstrated. Default Session/Token/index/replay work is single-instance; restart loses business login. Queue profile persists accepted encrypted tasks only, not business Sessions or cross-storage atomic logout. See [接入](../../docs/zh-CN/external-integration.md) / [Integration](../../docs/en/external-integration.md).
