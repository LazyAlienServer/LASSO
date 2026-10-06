# SSO owns first-party service-access eligibility

Status: accepted. LAS SSO owns the authoritative service-access eligibility decision for an identity, while LAS LOG and other business systems retain ownership of roles, projects, server restrictions, and final business authorization. The initial integration uses an authenticated internal query with monotonic versions; versioned state notifications are deferred to a later phase. Back-channel Logout remains responsible only for removing established sessions, and SSO does not expose Minecraft or business-permission APIs.

## Considered Options

- Copy account status into each business system and let each system derive access: rejected because new SSO states would require synchronized interpretation and stale copies could admit access.
- Treat Back-channel Logout as the access-state channel: rejected because logout removes current sessions but does not answer whether a new session may be established.

## Consequences

The first integration phase exposes a no-store, explicitly denied-or-allowed query with a per-subject version monotonic within the current `stateEpoch`. On 2026-10-05 the user accepted [ADR-0007](0007-scope-service-access-versions-to-security-epoch.md): reuse global `security_epoch` to distinguish restored database generations; epochs are opaque and cannot be sorted. Query compensation, cache-generation/recovery barriers, expiry/restart and active-session refresh must be implemented/verified before any one-minute propagation guarantee. Independent `SERVICE_ACCESS_CHANGED` remains deferred as confirmed on 2026-10-02; later events do not change authority ownership. The identified development tuple/compatibility is recorded in the [delivery](../integration/development-delivery-2026-10-05.md).
