package com.forgeai.testing.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_testing_audit_org_created", columnList = "organization_id, created_at"),
        @Index(name = "idx_testing_audit_entity", columnList = "organization_id, entity_type, entity_id")
})
@Getter
@Setter
public class AuditEventEntity {

    public AuditEventEntity() {
    }

    public AuditEventEntity(UUID id, UUID organizationId, UUID workspaceId, String actorUserId,
                            String eventType, String entityType, String entityId, String action,
                            String outcome, String correlationId, String traceId, String metadata,
                            Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.workspaceId = workspaceId;
        this.actorUserId = actorUserId;
        this.eventType = eventType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.outcome = outcome;
        this.correlationId = correlationId;
        this.traceId = traceId;
        this.metadata = metadata;
        this.createdAt = createdAt;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;

    @Column(name = "actor_user_id", length = 64, updatable = false)
    private String actorUserId;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    @Column(name = "entity_type", nullable = false, length = 64, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 255, updatable = false)
    private String entityId;

    @Column(name = "action", nullable = false, length = 64, updatable = false)
    private String action;

    @Column(name = "outcome", nullable = false, length = 32, updatable = false)
    private String outcome;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Column(name = "trace_id", length = 64, updatable = false)
    private String traceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", updatable = false)
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private UUID organizationId;
        private UUID workspaceId;
        private String actorUserId;
        private String eventType;
        private String entityType;
        private String entityId;
        private String action;
        private String outcome;
        private String correlationId;
        private String traceId;
        private String metadata;
        private Instant createdAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder organizationId(UUID organizationId) { this.organizationId = organizationId; return this; }
        public Builder workspaceId(UUID workspaceId) { this.workspaceId = workspaceId; return this; }
        public Builder actorUserId(String actorUserId) { this.actorUserId = actorUserId; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder entityType(String entityType) { this.entityType = entityType; return this; }
        public Builder entityId(String entityId) { this.entityId = entityId; return this; }
        public Builder action(String action) { this.action = action; return this; }
        public Builder outcome(String outcome) { this.outcome = outcome; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder metadata(String metadata) { this.metadata = metadata; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public AuditEventEntity build() {
            return new AuditEventEntity(id, organizationId, workspaceId, actorUserId, eventType, entityType, entityId, action, outcome, correlationId, traceId, metadata, createdAt);
        }
    }
}
