# Reference BFF

Runnable sample, not LASLOG. Full settings/optional queue: [中文](../../docs/zh-CN/reference-bff.md) / [English](../../docs/en/reference-bff.md).

From parent build after local SSO:

```sh
./gradlew :las-sso-reference-bff:bootRun
```

Default port8088. Profiles: application-security for own chain, https for external identity/Secure Cookie, durable-revocation for independently configured encrypted queue. Default restart loses login; queue preserves accepted tasks only. Real deployment supplies independent client/secret/HTTPS endpoints outside source.
