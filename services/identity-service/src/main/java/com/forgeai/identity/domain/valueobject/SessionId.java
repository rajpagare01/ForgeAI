package com.forgeai.identity.domain.valueobject;
import java.util.Objects;
import java.util.UUID;
public final class SessionId {
    private final UUID id;
    private SessionId(UUID id) { this.id = Objects.requireNonNull(id, "SessionId cannot be null"); }
    public static SessionId of(UUID id) { return new SessionId(id); }
    public static SessionId generate() { return new SessionId(UUID.randomUUID()); }
    public UUID value() { return id; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SessionId sessionId = (SessionId) o;
        return id.equals(sessionId.id);
    }
    @Override public int hashCode() { return Objects.hash(id); }
    @Override public String toString() { return id.toString(); }
}
