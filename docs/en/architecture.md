# Architecture and identity

[Index](README.md) · [中文](../zh-CN/architecture.md)

## Modules

The only Gradle build is `las-sso-server/`, Java21/Spring Boot/PostgreSQL:

| Module | Responsibility |
| --- | --- |
| Server/root project | Identity, passwords, account lifecycle, SSO sessions, OIDC, eligibility, audit |
| Spring Boot Starter | Servlet BFF integration; session-owned Tokens, refresh and logout hooks |
| Reference BFF | Runnable sample and optional queue; not LASLOG or another business backend |

```text
Browser → host Nginx HTTPS → SSO → PostgreSQL
Browser → business BFF ← OIDC/Back-channel → SSO
Collector → SSO private management listener
```

SSO owns human identity and credentials. Consumers own membership, roles, business permissions and Minecraft links/admission. Identity or eligibility does not automatically authorize a business action.

## Identity and state

- Human principal is immutable UUID; OIDC identity is `(issuer, sub)`.
- `LasUserPrincipal.getName()` and `getUsername()` return UUID. `loginUsername()` is mutable.
- Login usernames are case-sensitive. Current/historical names remain reserved; current implementation rejects reusing even one's own historical name.
- Account state is ACTIVE/FROZEN/DELETED. Password state and deletion request state are separate.
- PENDING/HELD deletion restricts access to status/cancel/logout. Cancellation or unfreeze does not resurrect revoked sessions/grants.
- One SSO administrator is retained. Promotion requires the owner to cancel an active deletion request first.

## Persistence

Flyway migrations are the schema source of truth. Application V1–V16 covers identity, framework `oauth2_*` authorization/client tables, JDBC Sessions, security metadata/history, transient challenges/limits and audit/outbox. The reference queue uses independent V1/V2 in `reference_revocation`.

Runtime uses a non-DDL role. Offline migration owns application objects; runtime may read but not write Flyway history. Tokens needed by the authorization framework can be stored in usable form, so database access and backups are sensitive. Passwords, codes and client secrets use digests.

## Transactions and lifetimes

- Credential/lifecycle mutations lock the user before related security records; failure rolls back their invalidation.
- Deletion batches use stable user-first locking and preserve batch atomicity.
- Refresh authenticates the owning client before reuse/invalidation side effects. RT rotation does not extend the fixed family deadline.
- Security epoch is opaque; compare eligibility versions only within one issuer/subject/epoch. Restore requires a new epoch/key and consumer barriers.
- Logout outbox acceptance, successful HTTP delivery and consumer execution are distinct stages.

[Security](security.md) defines lifetimes; [API](api.md) and [integration](integration.md) define supported interfaces. The initial Starter is single-JVM; restarting a BFF ends its business login. Multi-instance/retained business login and independent eligibility events are deferred.
