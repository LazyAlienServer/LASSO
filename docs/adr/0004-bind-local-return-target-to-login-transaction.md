# Bind local return targets to the login transaction

Status: user-confirmed on 2026-10-04; default/single-JVM and application-owned chain implementation verified, deployment acceptance pending.

Chinese counterpart: [返回目标绑定登录事务](../zh-CN/adr/0004-bind-local-return-target-to-login-transaction.md).

## Context

B5 discarded `returnTo` and always redirected default login to `/`. Simply reading a callback parameter or a subject/session-global target would allow target substitution or mixing across authorization requests. A starter redirect that bypassed an application-owned success handler could skip its business checks.

## Decision

The user selected explicit application integration over forced starter redirect for every chain:

- Invalid, repeated or oversized `returnTo` yields no-store 400 before authorization starts. Only conservative application-local paths are accepted; no external origins, authority, fragments or ambiguous path/control encoding.
- Store a validated target and creation time in the standard Spring authorization request's internal attributes, alongside its state/nonce/PKCE. The target is not an OP parameter. The local authorization transaction expires after ten minutes, matching the existing protocol contract.
- Match and remove that authorization request once, using the initiating local session and state. Same-session new authorization replaces the previous request, preserving Spring's existing single-outstanding-request behavior. Invalid state does not consume the correct pending request.
- Callback `returnTo` cannot override stored state. Expose the request-local verified target only after Code/nonce/signature validation and successful session registration.
- Default success uses the verified target; absent target keeps the existing root redirect. Application-owned success code always runs and may explicitly read `LasSsoReturnTo.target(request)` or call `LasSsoReturnToSuccessHandler` as its final redirect step after business checks. No forced redirect bypasses that code.
- Default request consumption is synchronized on the Servlet Session object in one JVM. No shared-store schema, distributed lock or multi-instance guarantee is introduced.

## Alternatives

- Force redirect before application success: rejected because it may skip onboarding, local eligibility or other application-owned checks.
- Save one target in a separate Session attribute: not selected; it can be mixed with a newer authorization and lacks exact state/expiry/single-consumption binding.
- Read target on callback: rejected; the untrusted response must not choose the post-authentication destination.
- Accept arbitrary relative/absolute URLs: rejected for the initial implementation. Conservative local validation is sufficient for the existing specification; broadening allowed path syntax or adding allowlisted external routes needs a separate decision.

## Compatibility and consequences

No-target default and existing application success-handler behavior are preserved. Explicit targets now affect the default chain. Invalid targets are rejected rather than ignored. Pending requests created before this repository added its creation-time attribute fail validation and must start login again. Manually replacing the resolver/repository must retain the timestamp, state and target contract; replacing the final handler with an unwrapped redirect must not bypass callback registration or trust query values.

The target is navigation only, not permission, recent password evidence or a saved write operation. The destination enforces business authorization; dangerous writes are not replayed. Consumer/shared-store/multi-instance acceptance remains separate. Current use: [integration](../en/integration.md); original details: [historical contract](https://github.com/LazyAlienServer/LASSO/blob/8390243/docs/integration/login-return-target.md).
