# LAS SSO Identity Context

This context defines the identity facts and service-entry decisions owned by LAS SSO. Business systems may consume these decisions, but they do not become owners of SSO account lifecycle or credentials.

## Identity

**Identity Subject**:
The stable UUID-backed principal identified by an SSO issuer and subject pair. A subject is not a username, email address, or business account.
_Avoid_: Username, local account, business user

**Account Lifecycle**:
The SSO-owned progression of an identity through active, frozen, deletion, and deleted states.
_Avoid_: Service permission, role state

**Service Access Eligibility**:
SSO's authoritative decision about whether a subject may enter a first-party business system, taking account lifecycle, deletion restrictions, and credential restrictions into account.
_Avoid_: Business authorization, role, whitelist, server ban

## Integration Boundary

**Business Authorization**:
A consuming system's decision about which projects, servers, resources, or operations an eligible subject may use.
_Avoid_: Account lifecycle, SSO service-access eligibility

**Session Logout**:
The action that removes an already established session; it does not by itself describe whether the subject may establish a future business session.
_Avoid_: Access eligibility, account deletion
