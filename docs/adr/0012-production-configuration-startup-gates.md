# ADR-0012: Production Configuration And Startup Gates

- Status: accepted implementation, 2026-10-07
- [Chinese](../zh-CN/adr/0012-production-configuration-startup-gates.md)

## Context

The SSO server already distinguished `local`/`test` ephemeral signing keys from persistent non-development keys and already checked database readiness. The deployment runbook still allowed an unsafe gap: a non-local process could start with an HTTP/example issuer, development secrets, an unsafe Cookie override, unreviewed Flyway locations, no usable administrator, or an ACTIVE client with development redirect/secret settings. A URI's presence and a healthy database do not prove that public authentication is safe to open.

## Decision

1. A non-development process must explicitly activate `production`. `local` or `test` cannot be combined with it. Production validation occurs before normal configuration binding and reports stable category codes without secret/path/driver causes.
2. Production requires a canonical HTTPS issuer whose path equals the servlet context path, persistent key path/alias/password/kid, distinct high-entropy peppers and database/key passwords, PostgreSQL JDBC, embedded Flyway `classpath:db/migration`, validation/clean protections, JDBC session schema `never`, database-aware readiness, coarse health details and restricted actuator exposure. The `__Host-las_sso` Cookie must remain Secure/HttpOnly/Lax/Path `/` with no Domain. Pepper/CAPTCHA minimum32 UTF-8 bytes; DB/key passwords minimum12; all five must be distinct. Length is not an entropy guarantee. HTTPS endpoint policy rejects loopback and reserved example domains, both at production client registration and startup.
3. The configured PKCS12 entry must contain a matching RSA private/public pair of at least 2048 bits. The signing configuration fails with a stable category and does not retain the password char array.
4. Production Web startup performs a database snapshot after Flyway: security epoch must exist; exactly one active usable SSO administrator must have a username/password and either a normal active credential or a non-expired temporary password; effective deletion pending/held state blocks readiness. Empty client tables are allowed so first-party clients can be registered after bootstrap. Existing ACTIVE clients must have metadata, audience, PKCE, exact supported scopes, `client_secret_basic`, Code+Refresh grants, HTTPS redirect/post-logout/Back-channel URIs and no known local example secret. Disabled clients do not block startup but are not issued by the gate.
5. The three supported maintenance commands require `production`, `WebApplicationType.NONE`, scheduling/session cleanup disabled, monitoring disabled and an interactive console. Bootstrap rejects a password command-line option before requesting the console. Maintenance remains the path for an empty database; Web does not open in the absence of an administrator.

## Consequences

After Flyway initialization, only fully applied SUCCESS migrations with matching checksums are accepted; future schema versions and pending/above-target/missing migrations do not silently count as compatibility.

Production startup now fails closed before a connector accepts traffic when these conditions are invalid. An administrator may still start with `PASSWORD_CHANGE_REQUIRED` while its non-expired temporary password is valid; the existing request-time filter forces the change. The first startup after a restore or key change still requires the documented epoch, key and consumer barriers; the gate is not a restore operation and does not perform cross-system reconciliation. Client registrations, external domains, TLS, secret custody, backup and target capacity remain operator tasks.

Configuration checks use current environment/property values and do not guarantee a trusted proxy, public CA, real client ownership or secret-manager custody. The gate does not require a client in an empty database, because the bootstrap/admin flow must remain usable. Future multi-instance and reliable consumer revocation remain separate decisions.
