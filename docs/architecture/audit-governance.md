# Audit & Governance Architecture

This document specifies the immutable audit logging, snapshot governance, and multi-tenant isolation model implemented in ForgeAI.

---

## 1. Audit Event Data Model

All governance-sensitive actions across ForgeAI are recorded in an append-only `audit_events` table:

```sql
CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    actor_user_id VARCHAR(64),
    event_type VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(64),
    trace_id VARCHAR(64),
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### Key Attributes
- **`organization_id`**: Strict multi-tenant isolation anchor. Every audit record belongs to a tenant.
- **`actor_user_id`**: Identifies the user, operator, or service identity performing the action.
- **`event_type`**: Semantic category (e.g., `github.installation.claimed`, `traceability.snapshot.created`).
- **`correlation_id` & `trace_id`**: W3C / edge correlation values tying the audit event to the originating HTTP request and distributed trace.
- **`outcome`**: `SUCCESS`, `DENIED`, or `FAILURE`.
- **`metadata`**: Non-sensitive JSON contextual payload.

---

## 2. Immutability Enforcement (Two-Layer Defense)

### Layer 1: PostgreSQL Trigger Enforcement
At the database layer, an immutable trigger prevents any modification or deletion:

```sql
CREATE OR REPLACE FUNCTION prevent_audit_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Audit events are immutable records and cannot be deleted. (id: %)', OLD.id
            USING ERRCODE = 'P0001';
    ELSE
        RAISE EXCEPTION 'Audit events are immutable records and cannot be updated. (id: %)', NEW.id
            USING ERRCODE = 'P0001';
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_audit_event_mutation
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW
EXECUTE FUNCTION prevent_audit_event_mutation();
```

Any attempt by application code or manual DB queries to execute `UPDATE audit_events` or `DELETE FROM audit_events` aborts with PostgreSQL error code `P0001`.

### Layer 2: Application Repository Enforcement
In Spring Data JPA repositories, all mutation methods override defaults to fail-fast:

```java
@Override
default void delete(AuditEventEntity entity) {
    throw new UnsupportedOperationException("Audit events are immutable records and cannot be deleted");
}

@Override
default void deleteById(UUID id) {
    throw new UnsupportedOperationException("Audit events are immutable records and cannot be deleted");
}

@Override
default void deleteAll() {
    throw new UnsupportedOperationException("Audit events are immutable records and cannot be deleted");
}
```

---

## 3. Governance-Sensitive Action Coverage

| Domain | Action / Event Type | Entity Type | Trigger |
| :--- | :--- | :--- | :--- |
| **Authentication** | `security.authentication.login_success` | `USER` | User login |
| **Authentication** | `security.authentication.login_failure` | `USER` | Bad credentials / Lockout |
| **Organization** | `organization.created` | `ORGANIZATION` | Org registration |
| **Organization** | `organization.member_role_changed` | `MEMBERSHIP` | Role promotion / demotion |
| **GitHub App** | `github.installation.claimed` | `INSTALLATION` | Org binds GitHub App |
| **GitHub App** | `github.installation.suspended` | `INSTALLATION` | Admin / Webhook suspension |
| **GitHub App** | `github.installation.deleted` | `INSTALLATION` | Webhook uninstall |
| **Traceability** | `TRACEABILITY_LINK_CREATED` | `TRACEABILITY_LINK` | Link established |
| **Traceability** | `TRACEABILITY_LINK_DELETED` | `TRACEABILITY_LINK` | Link removed |
| **Traceability** | `TRACEABILITY_SNAPSHOT_CREATED`| `SNAPSHOT` | Version snapshot minted |
| **Operations** | `OUTBOX_REPLAY_REQUESTED` | `OUTBOX_EVENT` | Poison-pill replay |
| **Operations** | `OUTBOX_REPLAY_DENIED` | `OUTBOX_EVENT` | Cross-tenant replay attempt |

---

## 4. Snapshot Governance & Immutability

Traceability snapshots represent verifiable, immutable point-in-time states of a project's compliance chain:
- **`created_by`**: String representation of actor UUID or system principal.
- **`schema_version`**: Version tag (`1.0`) ensuring long-term compatibility for historical audit reproduction.
- **`snapshot_data`**: Verbatim JSON payload of requirements, tasks, PRs, test results, and blast radius edges.
- **Audit Coupling**: Every snapshot creation writes a `TRACEABILITY_SNAPSHOT_CREATED` audit event within the same transaction.

---

## 5. Multi-Tenant Isolation Guarantee

1. Every audit query requires an active `organizationId`. Cross-tenant queries are blocked at the controller layer and return HTTP 403 Forbidden.
2. In dead-letter outbox replay, the target outbox event payload is inspected: if it contains an `organizationId` different from the requesting tenant, the action is rejected and an `OUTBOX_REPLAY_DENIED` security audit event is logged.
3. Administrative telemetry displays aggregate health metrics without leaking cross-tenant payload identifiers.

---

## 6. Cryptographic Hash Chaining Status Note

*Architecture Honesty Disclosure*:
ForgeAI provides tamper-evident audit logging enforced via database trigger constraints (`P0001`), write-only Spring Data repository abstractions, and append-only schema constraints. Cryptographic Merkle tree hash chaining is planned for a future milestone when an external verifiable timestamping authority or immutable ledger notary is integrated. We do not claim cryptographic blockchain/hash verification until that cryptographic engine is active.
