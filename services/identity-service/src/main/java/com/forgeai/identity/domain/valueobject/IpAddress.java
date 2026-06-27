package com.forgeai.identity.domain.valueobject;
import java.util.Objects;
public final class IpAddress {
    private final String ip;
    private IpAddress(String ip) { this.ip = ip == null ? "Unknown" : ip; }
    public static IpAddress of(String ip) { return new IpAddress(ip); }
    public String value() { return ip; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IpAddress ipAddress = (IpAddress) o;
        return ip.equals(ipAddress.ip);
    }
    @Override public int hashCode() { return Objects.hash(ip); }
    @Override public String toString() { return ip; }
}
