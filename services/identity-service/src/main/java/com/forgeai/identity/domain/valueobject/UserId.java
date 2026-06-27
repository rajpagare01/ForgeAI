package com.forgeai.identity.domain.valueobject;
import java.util.Objects;
import java.util.UUID;
public final class UserId {
    private final UUID id;
    private UserId(UUID id) { this.id = Objects.requireNonNull(id, "UserId cannot be null"); }
    public static UserId of(UUID id) { return new UserId(id); }
    public static UserId generate() { return new UserId(UUID.randomUUID()); }
    public UUID value() { return id; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserId userId = (UserId) o;
        return id.equals(userId.id);
    }
    @Override public int hashCode() { return Objects.hash(id); }
    @Override public String toString() { return id.toString(); }
}
