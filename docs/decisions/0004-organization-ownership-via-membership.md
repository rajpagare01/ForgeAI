# ADR 0004: Organization Ownership via Membership Role

## Status
Accepted

## Context
Organizations need at least one owner at all times. The naive approach is to put an `owner_id` UUID column on the `organizations` table. ForgeAI must support multiple owners per organization (e.g., two co-founders, a backup admin).

## Decision
**Do not add `owner_id` to `organizations`.** Ownership is expressed exclusively through:

```
OrganizationMembership(role = OWNER, status = ACTIVE)
```

The invariant "every active organization must have at least one active OWNER membership" is enforced by application-layer logic in a dedicated `OrganizationOwnershipPolicy` domain service.

## Rationale

- **Multiple owners:** A single `owner_id` cannot represent co-ownership without additional columns or tables.
- **Flexibility:** Ownership transfer is a simple role change on a membership record rather than a column update with complex transactional semantics.
- **DB constraint limitation:** Enforcing "at least one owner" via a database deferred constraint (e.g., a trigger or deferred CHECK) is fragile: it fires after every membership row change, making batch imports and migrations error-prone.
- **Application-layer control:** The `OrganizationOwnershipPolicy` service performs a pre-check before any membership mutation that could reduce owners (role change, status deactivation, membership removal). This is testable, explicit, and easy to evolve.

## Invariants Enforced

1. When removing a member: check remaining ACTIVE OWNER count > 1 before proceeding.
2. When downgrading an OWNER role: same check.
3. When suspending/deactivating an organization: document that the org status change does not require ownership enforcement (ownership is about the org itself remaining viable, not its status).

## Consequences
- No single-point `owner_id` on `organizations` — queries for "who owns this org?" require joining `organization_memberships`.
- `OrganizationOwnershipPolicy` must be called consistently from all membership mutation use cases.
- Integration tests should cover the invariant edge cases explicitly.
