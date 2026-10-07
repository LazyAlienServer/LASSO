# External Configuration

Templates reference external environment variables and contain no live passwords, keys or default client credentials. Copy a selected template to a protected configuration location outside the package, review the topology, and supply required values through the service environment/protected file.

| File | Purpose |
| --- | --- |
| `server/server.properties.example` | Main SSO process: DB, canonical issuer, persistent PKCS12 key and peppers |
| `server/monitoring.properties.example` | Optional `monitoring` profile: separate private listener and independent bcrypt collector hash |
| `reference-bff/bff.properties.example` | HTTPS BFF identity/Cookie/refresh settings; actual client must be registered separately |
| `reference-bff/durable-revocation.properties.example` | Optional reference-only independent DB/AES queue, not SSO schema |

`production` is an operator-selected profile name, not a shipped provisioning system. Avoid local/test profiles. Base server port9000/management9091 are examples; bindings must match proxy/firewall. Files are `.example` inputs, not ready-to-run secrets or a mechanism automatically selected by the app.

Linux startup passes `--spring.config.additional-location=file:/etc/las-sso/server.properties`; protected environment supplies variables. PowerShell maintenance uses `SPRING_CONFIG_ADDITIONAL_LOCATION` and explicit profile/JAR/hash. See [中文部署](../docs/zh-CN/deployment.md) / [English deployment](../docs/en/deployment.md).

Keep signing/queue keys, TLS certificates/private keys, peppers, raw collector/webhook passwords, recovery codes, `.env` and database backups outside this directory. Supply monitoring raw password to collector only, hash to app; no OIDC client or human password reuse.
