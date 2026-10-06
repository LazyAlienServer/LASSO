# LAS SSO Spring Boot Starter

Current identified local delivery: **0.1.0-dev.20261006.7**, tested with Boot4.1.1/Framework7.0.9/Security7.1.1; final TLS default/application-owned BFF mixed refresh/current/global logout ran five minutes per mode. Server V15 preserves historical ID Token hint association after refresh; starter APIs/JAR bytes unchanged. Prior .5 admin/restore and .6 alert evidence remain historical. Native-module/POM-only consumers compiled. SSO monitoring/history schema is not a consumer table/credential. [Delivery](../../docs/integration/development-delivery-2026-10-06-7.md) / [中文](../../docs/zh-CN/integration/development-delivery-2026-10-06-7.md), [hint continuity](../../docs/implementation/refresh-logout-hint-history.md) / [中文](../../docs/zh-CN/implementation/refresh-logout-hint-history.md).

这是业务系统 BFF 的初版接入骨架，当前提供：

- `GET /api/auth/login`：进入 Spring Security OAuth2 Login。
- `GET /api/auth/session`：返回不含 Token 的安全会话快照，设置 `Cache-Control: no-store`。
- Authorization Code + PKCE S256。
- `returnTo`：校验应用内目标并绑定state/Session/十分钟授权事务；默认采用目标，自有success显式接入，不能绕过业务检查。
- 每个受保护请求检查服务端授权客户端；AT 默认到期前 60 秒用 RT 续期。
- 刷新后重读授权客户端，要求 AT expiresAt 晚于当前时间；过期且无新 AT 时撤销本地认证，API 返回 `401 SESSION_EXPIRED`。
- 单 BFF JVM 内同 Session 并发刷新合并。
- SSO 明确拒绝 RT 时清除授权客户端/Session；网络或暂时故障保留会话但拒绝当前请求，返回 `503 SSO_UNAVAILABLE`。
- `POST /api/auth/logout`：只退出当前应用。
- `POST /api/auth/logout/global`：RP-Initiated Logout、短期单次 state 校验；ID Token 不暴露给脚本。
- `POST /internal/oidc/backchannel-logout`：验证 Logout Token，按 sid/sub 注销会话；同 Token 幂等、同 jti 不同内容拒绝。

## Public extension boundary

Reference-only durable sink example is now available under the optional BFF `durable-revocation` profile: [English](../../docs/integration/reference-durable-revocation.md) / [中文](../../docs/zh-CN/integration/reference-durable-revocation.md), [ADR-0009](../../docs/adr/0009-reference-bff-durable-token-revocation.md). It commits AES-GCM encrypted work to an explicitly configured reference PostgreSQL and survives JVM restart; default library/sink stays process-local and adds no JDBC dependency/schema. Pre-submit crash and durable Session/index/replay remain external, not solved by accepted task persistence.

2026-10-05 B2 update: the default registry retains revoked-sid markers for configured `session-absolute-lifetime` (default14days), including no-match Logout. Coordinated marker/index state rejects late callback/request registration using existing503 cleanup; other sids for the same subject remain independent. SPI signatures/no-arg constructor remain, custom stores must implement equivalent ordering themselves. Markers are process-local, bounded by time not count, not a durable/restart/multi-instance guarantee. [ADR-0008](../../docs/adr/0008-retain-sid-revocations-before-local-registration.md) / [中文](../../docs/zh-CN/adr/0008-retain-sid-revocations-before-local-registration.md) supersede earlier default-ordering limits below.

`LasSsoBackChannelSessionStore` is the public session-index boundary used by the Back-Channel Logout receiver and HTTP-session lifecycle hook. The default `LasSsoBackChannelSessionRegistry` remains process-local for the sample/default path; a consuming BFF may provide a bean implementing the interface to persist and invalidate its own local sessions after restart. The starter does not create or prescribe the consumer's database schema.

`LasSsoAuthorizedClientRepository` is the public Token ownership boundary. The default `DefaultLasSsoAuthorizedClientRepository` delegates to Spring's HttpSession-backed repository for the initial single-instance path. A consuming BFF may replace it with its own protected persistent adapter; implementations must key save/load/remove by local business Session and registration, not by subject alone.

`LasSsoLogoutReplayStore` and `LasSsoRevocationTaskSink` are the public boundaries for validated Logout Token replay/execution receipts and remote revocation work. Their defaults are process-local; durable adapters are required before claiming restart-safe/multi-instance behavior. Four adapter Bean replacement remains tested; the receiver now uses the approved `process` method described below, not legacy digest-only `accept`. These interfaces do not prescribe the consumer's schema/encryption strategy.

### Verified runtime ownership — 2026-10-03

The default security chain uses the same `LasSsoAuthorizedClientRepository` for OAuth2 Login, refresh manager/filter and current-application logout. An unrelated Spring `OAuth2AuthorizedClientRepository`, even if `@Primary`, must not supply LAS Token state. Existing public logout-handler constructors remain compatible; manual/custom chains must supply the same repository as their login/refresh path.

Current logout snapshots only the owning session's Token, removes its local authorized client, clears its authentication and invalidates its HTTP Session **before** submitting to `LasSsoRevocationTaskSink`. Failure to read/remove state or submit the work is logged with a non-secret failure category; it never restores local login. Submission return means work accepted, not remote revocation completed. A durable adapter must commit accepted work before returning; the default process-local queue provides no durable guarantee. If submission fails, remote revocation is **not** claimed successful or automatically retried by the handler.

`LasSsoSessionChainIntegrationTest` exercises the actual Spring security chain, manager, repository and HTTP refresh client against a loopback Token endpoint: independent Session refresh/rotation, same-Session single-flight, distinct-Session concurrent requests, rejected/transient refresh, CSRF logout, custom repository save/load/remove, competing Spring repository and failing sink. Initial authentication and inbound Servlet requests are fixtures; this is not signed login-callback, live SSO, browser, Tomcat, restart or persistent-adapter acceptance. The strengthened logout unit test now asserts actual removal/submission order rather than just checking both calls occurred.

### Public security composition and callback registration — 2026-10-03

Inject `LasSsoSecurityConfigurer` into an application-owned `SecurityFilterChain` and call `configure(http, yourAuthenticationSuccessHandler)` once. The starter's default chain uses the same method: PKCE resolver, one LAS repository for login/refresh/logout, callback-time registration, request freshness/indexing, current logout and the POST-only Back-channel CSRF exception. The application retains route authorization and its success delegate. Do not replace the wrapper with a later unwrapped `oauth2Login.successHandler` or install the filters separately. Duplicate configuration is rejected.

Public `LasSsoAuthenticationSuccessHandler` registers verified subject/sid under the final local Session before application success/redirect; failed registration ends local login and emits `SESSION_REGISTRATION_FAILED`. Public `LasSsoSessionLifecycleListener` supports destruction and Session-ID reindex; auto-configuration installs it even for an application-owned chain. `LasSsoClientErrorHandler` is a replaceable response-only bean for expiry, temporary SSO failure and registration failure. Existing refresh-filter constructors remain compatible. Full [English composition contract](../../docs/integration/starter-security-composition.md) / [中文接入说明](../../docs/zh-CN/integration/starter-security-composition.md) include code examples, lifecycle ownership and limits.

`LasSsoOidcCallbackIntegrationTest` adds 14 cases with real Code/PKCE HTTP exchange and RSA/JWKS validation: immediate signed logout before another request, default/custom chain, repository/index/error hooks, failure cleanup, invalid signature/nonce, missing sid, repeat login, lifecycle, duplicate configuration, CSRF and HTTP refresh errors. The inbound Servlet, event dispatch and receiver routing remain fixtures. Full forced rerun: 53 starter tests, zero failures/errors/skips; reference BFF recompiled and provides optional `application-security` profile, with `test NO-SOURCE`.

B2's callback-return→next-request gap is fixed and tested. Logout-before/during-registration, separate Token/index commit, live container/browser, persistent/restart/multi-instance coordination are not made atomic by these hooks. `register` must be repeat-safe and return only when `invalidate` can use its index. B3 was subsequently fixed as described below; B4/B5 and complete release acceptance remain open.

### Revocation acknowledgement — B3, 2026-10-04

`LasSsoTokenRevocationQueue` now explicitly requires HTTP **200** per RFC 7009 §2.2, ignores response content and disables redirect following. Unexpected 2xx, 3xx and errors retry the original endpoint under the existing eight-attempt/random-backoff policy. Exhaustion logs only `HTTP_<status>` or a non-secret transport category, without Token/client secret/response body/exception text. The queue remains process-local; it stops and logs after exhaustion, with no durable dead-letter or restart guarantee. `submit` acceptance still is not remote success.

18 actual HTTP tests cover the status matrix, Basic/form data, retry/recovery, no forwarded redirect, transport failure, bounded exhaustion and safe logs. One uses the real public queue scheduler; others use a package-private scheduler seam recording delays without waiting, with real HTTP tasks. Latest full forced rerun is **71 starter tests** (server 178), zero failures/errors/skips; reference BFF recompiled, `test NO-SOURCE`. Earlier 53-test callback evidence above is historical. See [English](../../docs/integration/logout-delivery-acknowledgements.md) / [中文](../../docs/zh-CN/integration/logout-delivery-acknowledgements.md). B3 sender behavior is fixed/tested; B4/B5, persistent acceptance and deployment remain open.

### Strict receiver and execution receipts — B4, 2026-10-04

The receiver requires fixed issuer/RS256/typed signature, required short iat/exp (at most 120s, 60s skew), nonblank first-party sid/jti, empty logout-event object and no nonce. Default decoding uses mature Spring/Nimbus plus verified raw identity-type checking; a custom decoder must preserve that validation. Fixed issuer is now mandatory in client configuration.

**Approved runtime upgrade:** `LasSsoLogoutReplayStore.process(..., retainUntil, Runnable action)` is added with `SUCCESS/CONFLICT/IN_PROGRESS`. `accept` is retained for source compatibility, but accept-only adapters receive **503 without execution until upgraded**. The default process reserves issuer/jti/digest, caches only successful execution, returns cached 204 without another invalidation, rejects conflicts, and returns 503 for in-progress/execution/store failure. Failed attempts preserve digest and release execution for retry; receipts cover exp+60s. The default session registry preserves still-live failed indexes rather than removing them before invalidation.

33 actual RSA/JWKS receiver cases, 15 execution cases and five replay-store tests pass, including concurrent retries, partial failure, time boundary, actual JWKS 503, legacy/custom process compatibility and safe logs. Latest full forced rerun: **178 server + 117 starter**, no failures/errors/skips; BFF recompiled and `test NO-SOURCE`. The 71/53 counts above are historical stages. No schema/dependency/lock changed.

Read [ADR-0003](../../docs/adr/0003-logout-replay-execution-receipts.md), [English upgrade contract](../../docs/integration/backchannel-logout-receipts.md) / [中文](../../docs/zh-CN/integration/backchannel-logout-receipts.md) before upgrading adapters. Default receipts are single-JVM, not distributed exactly-once; persistence/leases/crash recovery, real consumer and ordering remain open. B5 was subsequently repaired below.

### Safe return target — B5, 2026-10-04

`/api/auth/login?returnTo=/path` and the configured direct authorization entry now reject unsafe/duplicate/oversized values with no-store400. The target/created time stays in the standard server-side OAuth2 request attributes; it is not sent to OP. Matching Session/state consumes once and checks ten-minute expiry; callback parameters cannot override it. Public `LasSsoReturnTo.target(request)` exposes only validated/registered success; default uses `LasSsoReturnToSuccessHandler`, application delegates explicitly opt into final navigation after checks. Existing custom success delegates are not skipped, and no-target default still goes `/`.

45 URI/transaction tests and a 28-case signed callback suite pass; B5 adds59 cases. Latest forced run **178 server/176 starter**, no failures/errors/skips; BFF resources/profile recompiled, `test NO-SOURCE`. Old pending requests without timestamp must restart; consumption locks only one JVM/same Servlet Session object. Conservative syntax and integration examples: [ADR-0004](../../docs/adr/0004-bind-local-return-target-to-login-transaction.md), [English](../../docs/integration/login-return-target.md) / [中文](../../docs/zh-CN/integration/login-return-target.md). Shared stores/multi-instance, real browser/MVC/proxy and consumer policy remain unverified. Navigation does not grant access/recent-password evidence or replay dangerous writes.

业务系统仍需配置 confidential client 的 issuer/client-id/client-secret/精确回调及 openid,profile。自定义 SecurityFilterChain 时必须显式接入相同 PKCE、刷新、会话登记和退出规则。参数包括 `las.sso.client.refresh-before-expiry`、`post-logout-redirect-uri`、`logout-success-uri`、最长 15 分钟的 `logout-state-ttl`；退出地址必须登记。

默认授权客户端、刷新合并、全局退出 state、Back-Channel 索引和重放缓存只覆盖单 JVM。四类公开 adapter 与运行期刷新/退出链路已有验证；业务方持久实现、真实双浏览器/重启及跨实例行为仍待验收，增加业务数据库不会自动补齐。Jackson 3 仅作为本库测试运行时依赖，用于真实 OAuth2 HTTP JSON 转换；生产消费端须提供其支持的 JSON 转换器，参考 BFF 的 Web starter 已提供该基础。

LASLOG 初期选择单实例、业务重启后重新顶层 SSO，SSO JDBC 独立保留。B1 运行期/B2 原窗口/B3 发送/B4 严格接收与单 JVM 回执已有独立证据；真实接入/重启/持久撤销接受仍待验。最新见[状态清单](../../docs/implementation/status-issues-and-plan-2026-10-02.md)。

## Dependency delivery

Current identified combination **0.1.0-dev.20261006.3** adds only deployment/header and optional referenceHTTPS inputs; starter APIs/classes/locks/bytehash remain unchanged,486server193starter37reference716 zero failures/errors/skips. Real default/application-owned TLS/SecureCookie/HTTPSBack-channel acceptance does not provision consumer trust or production firewall/CDN config. [English](../../docs/integration/development-delivery-2026-10-06-3.md) / [中文](../../docs/zh-CN/integration/development-delivery-2026-10-06-3.md) / [manifest](../../docs/integration/development-delivery-2026-10-06-3.json) are current provenance.

Latest combination **0.1.0-dev.20261006.2** repairs server-side revoked-cookie OIDC continuation and verifies real administrator maintenance;486server193starter37reference716 zero failures/errors/skips. Starter classes/API/dependencies/bytehash remain unchanged. [English](../../docs/integration/development-delivery-2026-10-06-2.md) / [中文](../../docs/zh-CN/integration/development-delivery-2026-10-06-2.md) / [manifest](../../docs/integration/development-delivery-2026-10-06-2.json) supersede earlier current-source provenance; no consumer gate/shared storage or hosted release is implied.

Latest combination **0.1.0-dev.20261006.1** adds only SSO executable recovery/script/tests, retaining starter bytehash/API/dependencies:479server/193starter/37reference709 zero failures/errors/skips. Real isolated backup/restore/persistent-key/old token refusal does not implement consumer cache or persistent BFF Sessions. [English](../../docs/integration/development-delivery-2026-10-06-1.md) / [中文](../../docs/zh-CN/integration/development-delivery-2026-10-06-1.md) / [manifest](../../docs/integration/development-delivery-2026-10-06-1.json) supersede prior snapshot provenance.

Latest identified tuple is **0.1.0-dev.20261005.4**,461server/193starter/37referenceBFF691 zero failures/errors/skips,278source/9artifactscriptmigration/3locks. Starter classes/API and bytehash unchanged from.3; reference adds opt-in JDBC/Flyway/PG and tests. [English .4](../../docs/integration/development-delivery-2026-10-05-4.md) / [中文](../../docs/zh-CN/integration/development-delivery-2026-10-05-4.md) / [manifest](../../docs/integration/development-delivery-2026-10-05-4.json) supersede earlier provenance below, not a hosted release.

Current identified delivery is **0.1.0-dev.20261005.3**,461server/193starter654 zero failures/errors/skips,267-file scope including maintenance scripts,8artifact/script hashes/three locks; default/app-owned actual Edge flow rerun and native/POM-only public API compile. [English .3](../../docs/integration/development-delivery-2026-10-05-3.md) / [中文](../../docs/zh-CN/integration/development-delivery-2026-10-05-3.md) / [manifest](../../docs/integration/development-delivery-2026-10-05-3.json) are current provenance. Earlier .1/.2 and stage counts below are historical, not current-source hashes. Durable/custom-store ordering/restart/hosted release remain separate.

Java 21 library; the reference BFF is an example, not LASLOG. Current auto-configuration and session/refresh/logout behavior are described in `../README.md`. Defining an application SecurityFilterChain disables the default chain; do not assume client endpoints/filters alone provide business authorization.

Identified local development delivery **0.1.0-dev.20261005.1** (2026-10-05) now pairs server/starter/locks/source fingerprint and artifact SHA-256. Public `LasSsoSecurityConfigurer.configure(http, success, Consumer<OAuth2AuthorizationRequest.Builder>)` supports explicit trusted standard prompt/max_age customization while keeping the old overload. Applications preserve state/nonce/PKCE and independently consume verified OidcUser ID Token auth_time for their pending local session/operation/expiry; default browser query parameters are not forwarded. Two signed callback tests and two external native/POM-only artifact consumers compiled the public APIs, Security7.1.1 alignment preserved. Latest tuple **460server/178starter**, zero failures/errors/skips. Server service-access adds stateEpoch, consumers update strict DTO/cache generation. [English delivery](../../docs/integration/development-delivery-2026-10-05.md) / [中文](../../docs/zh-CN/integration/development-delivery-2026-10-05.md) / [manifest](../../docs/integration/development-delivery-2026-10-05.json) carry provenance/build/migration/limits; no committed/hosted production release or live consumer acceptance is claimed, defaults remain SNAPSHOT.

D1 patch baseline (2026-10-04): this build uses Spring Security 7.1.1 under Boot 4.1.0. The starter exports a native Security BOM platform and versioned config/OAuth2 Client/JOSE API dependencies so generated `.module` and POM metadata carry the patch graph. An isolated local file-publication probe with native Boot 4.1.0 management selected all Security modules at 7.1.1 through both native metadata and POM-only resolution; BOM-only POM delivery initially selected 7.1.0 and was corrected. No actual Maven CLI/forced-old consumer locks/live application or hosted release is claimed. Consumers must review their resolved versions and align their BOM/locks; this is a regular platform, not `enforcedPlatform`. The default starter version remains 0.1.0-SNAPSHOT. Latest forced regression: 323 server/176 starter, zero failures/errors/skips, BFF recompiled/NO-SOURCE and Security 7.1.1 boot-JAR packaging verified. [English patch record](../../docs/implementation/spring-security-consent-patch.md) / [中文](../../docs/zh-CN/implementation/spring-security-consent-patch.md) describe sources, Nimbus patch changes, publication probes and limits.

## Development composite build

LASLOG now has an explicit, isolated dependency probe. From `LASLOG/back-end/laslog-server`:

```powershell
.\gradlew.bat verifySsoStarterDependency -PlasSsoSourceBuild=../../../LASSSO/las-sso-server
```

It substitutes only this library project and does not add OIDC auto-configuration to the LASLOG runtime. The path is opt-in, not an implicit sibling dependency. Source mode uses the included SSO build's own locks; the isolated probe does not overwrite LASLOG's artifact-mode locks. Use the SSO checkout/revision/locks intentionally. Artifact mode uses `lasSsoArtifactRepository` and optional `lasSsoStarterVersion` in LASLOG, with reviewed probe lock updates for a selected artifact version.

## Publishing

The `starter` Maven publication now exists. From the SSO server build, a local artifact test can use:

```powershell
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository -PlasSsoPublishRepository=file:///absolute/path/to/test-repository
```

`lasSsoPublishRepository` is required to configure that target task. `lasSsoStarterVersion` optionally overrides this library's version independently of the server; default remains `0.1.0-SNAPSHOT` for local development. For CI/releases choose a fixed approved version, record its source revision and locks, and enforce immutability at the artifact repository. No remote repository or credentials are hardcoded or deployed by this configuration.

The current configurable repository is suitable for a local file-backed publication check. Protected remote upload authentication and repository retention/immutability are deployment configuration still to be designed; this is not evidence of a hosted SDK release. `publishToMavenLocal` also exists, but LASLOG does not implicitly search global Maven Local.

## Limits relevant to LASLOG

`LasSsoSessionRegistrationFilter` is internal to the public configurer. SSO-01/B2 signed-flow, B3 HTTP, B4 strict single-JVM receipts and B5 return targets have scoped evidence. Four adapters remain public/process-local or HttpSession; replay requires process upgrade. Identified local delivery is recorded above; committed production release/live consumer, persistence, ordering, real browser/restart/deployment remain open.
