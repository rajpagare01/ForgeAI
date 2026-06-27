package com.forgeai.identity.domain.valueobject;
import java.util.Objects;
import java.util.regex.Pattern;
import com.forgeai.identity.domain.exception.DomainException;
public final class Email {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
    private final String address;
    private Email(String address) {
        if (address == null || address.isBlank()) throw new DomainException("Email cannot be empty");
        String normalized = address.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) throw new DomainException("Invalid email format");
        this.address = normalized;
    }
    public static Email of(String address) { return new Email(address); }
    public String value() { return address; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Email email = (Email) o;
        return address.equals(email.address);
    }
    @Override public int hashCode() { return Objects.hash(address); }
    @Override public String toString() { return address; }
}
