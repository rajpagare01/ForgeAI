package com.forgeai.identity.domain.valueobject;
import java.util.Objects;
import com.forgeai.identity.domain.exception.DomainException;
public final class PasswordHash {
    private final String hash;
    private PasswordHash(String hash) {
        if (hash == null || hash.isBlank()) throw new DomainException("Password hash cannot be empty");
        this.hash = hash;
    }
    public static PasswordHash of(String hash) { return new PasswordHash(hash); }
    public String value() { return hash; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PasswordHash that = (PasswordHash) o;
        return hash.equals(that.hash);
    }
    @Override public int hashCode() { return Objects.hash(hash); }
    // Intentionally omitting hash from toString for security
    @Override public String toString() { return "[PROTECTED]"; }
}
