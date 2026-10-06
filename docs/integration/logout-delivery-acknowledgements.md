# Logout delivery and revocation acknowledgements

- Status: B3 fixed and verified with PostgreSQL/loopback HTTP on 2026-10-04; deployment acceptance remains separate.
- Chinese counterpart: [退出投递与撤销回执](../zh-CN/integration/logout-delivery-acknowledgements.md).
- Applies to server `BackChannelLogoutDelivery` and starter `LasSsoTokenRevocationQueue`.

## 1. What counts as success

| Path | Acknowledged status | Everything else |
| --- | --- | --- |
| Server → registered BFF Back-channel endpoint | Exactly **200 or 204**, matching the existing LAS receiver contract | Retry; never set `processed_at` merely because HTTP completed |
| Starter → discovered OP revocation endpoint | Exactly **200**, per [RFC 7009 §2.2](https://www.rfc-editor.org/rfc/rfc7009.html#section-2.2) | Retry; do not claim revocation acknowledgement |

`202 Accepted`, other uncontracted 2xx statuses, redirects and 4xx/5xx are not acknowledgement. The two paths explicitly disable redirect following with the existing HTTP request factory, and inspect status through `RestClient.exchange` rather than relying on the default retrieve error rules. Response bodies and `Location` are not interpreted as successful delivery and are not logged. The original URI remains the retry target; Logout/Refresh Tokens and client credentials are not forwarded to a redirect destination.

RFC 7009 requires ignoring the revocation response body. A 200 is also returned for an already invalid token, so acknowledgement is not proof that a previously live token was found, nor independent observation of every downstream server's state. Likewise, Back-channel 200/204 is the receiver's protocol confirmation; the sender cannot independently prove arbitrary receiver database effects.

## 2. Retrying and exhaustion

No migration, authentication change, new HTTP dependency or replacement retry policy is introduced by B3. Existing connect/read timeouts remain 3/5 seconds and the application-level limit remains eight attempts. Retries preserve the existing random exponential delay: after failed attempt `n` (1–7), schedule in `1..min(300, 2^n)` seconds. No new `Retry-After` policy, status-specific early dead-lettering or circuit breaker was added.

**Server outbox:** claim and conditional completion/retry updates retain their existing transactions and claim-token ownership. A rejected status leaves `processed_at` null, increments `attempt_count`, records `HTTP_<status>`, clears claim fields and sets `available_at`. Eight failures set `dead_lettered_at` and emit an error category; dead-lettered events are no longer claimed. One failed receiver does not prevent another claimed event from completing. A later acknowledged retry clears `last_error_code` and marks processed. The local `GLOBAL_LOGOUT_REQUESTED` marker path remains distinct from HTTP delivery events.

**Default starter queue:** `submit` returning still means process-local work submission, not OP acknowledgement or durable acceptance. Unacknowledged HTTP responses schedule the existing retries; the eighth failure logs a non-secret category and stops the request. This queue has no persistent dead-letter record, receipt API, restart survival or multi-instance guarantee. A durable `LasSsoRevocationTaskSink` remains consumer-owned under its documented acceptance contract; B3 does not supply its schema or make submission/local logout atomic.

HTTP failures are recorded/logged as `HTTP_<status>`; transport failures use a non-secret exception class such as `ResourceAccessException`. Exhaustion/worker logging does not include response content, URI/transport exception text, Token or client secret. This targeted behavior is regression-tested, not a complete application/production log scan.

## 3. Executed verification

Both new single-case regressions failed on the original code on 2026-10-03 at about 23:55 Asia/Shanghai: the server marked a 302 delivery processed, and the default starter queue did not retry its original endpoint. Both passed after the fix; expanded coverage ran on 2026-10-04.

- `BackChannelLogoutDeliveryIntegrationTest`: **20** passing cases using actual PostgreSQL 18.4 Testcontainers, Spring delivery/signature issuance and actual HTTP. Covers 200/204, unexpected 201/202/206, 301/302/303/307/308, 400/401/429/500/503, retry then success, all eight failures/dead-letter exclusion, abrupt connection close, receiver independence, cleared claims and safe error/log output. Retry due dates are advanced in the fixture to avoid sleeping through random production backoff; state updates/HTTP remain real.
- `LasSsoTokenRevocationQueueTest`: **18** passing cases using actual HTTP. Covers exact 200 with Basic/form fields, unexpected 2xx/redirects/errors, retry then success, transport failure, eight-attempt exhaustion, no redirect forwarding and safe logging. One test uses the real default scheduler; the matrix uses a package-private injected scheduler that records delay values and runs HTTP tasks without waiting. This is not a change to the public scheduler/constructor or a long-duration timing test.
- Related suites: **73 server** cases (20 + existing 53 persistence) and **20 starter** cases (18 + existing two current-logout tests), all zero failures/errors/skips.
- Full forced rerun on 2026-10-04 at about 00:10–00:12 Asia/Shanghai:

  ```powershell
  .\gradlew.bat test :las-sso-reference-bff:compileJava --rerun-tasks --no-daemon --console=plain
  ```

  **178 server + 71 starter**, zero failures/errors/skips; reference BFF recompiled and has `test NO-SOURCE`.

Responses containing a secret canary and actual Logout Token/request Token values are checked against captured exhaustion logs and database error codes. Earlier red XML files were overwritten by subsequent Gradle runs; command output and the [status map §3.5](../implementation/status-issues-and-plan-2026-10-02.md) retain the phase evidence. A test assertion-overload compile error was corrected during expansion and is not a production defect.

## 4. Remaining acceptance

No real TLS/reverse proxy/registered production BFF or live OP revocation was exercised. Timeout/slow-body saturation, full-duration scheduling, stale-claim races, crash/restart recovery, capacity and multi-instance deployment are not covered by this status matrix. It validates sender acknowledgement/retry behavior, not B4 Logout Token validation/replay/execution semantics or B2 logout-before-registration ordering.

B4, durable task acceptance, version delivery and real consumer end-to-end acceptance remain independent work. Changes are uncommitted, unpublished and undeployed; no production-ready claim follows from these green suites.

Subsequent B4 strict validation/single-JVM execution receipts were verified on 2026-10-04, under user-approved ADR-0003. Latest full result is 178 server / 117 starter. See the separate [receiver/adapter upgrade contract](backchannel-logout-receipts.md); durable/real-consumer and registration-ordering limits still apply.
