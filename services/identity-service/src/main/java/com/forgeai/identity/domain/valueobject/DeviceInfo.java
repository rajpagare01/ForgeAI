package com.forgeai.identity.domain.valueobject;
import java.util.Objects;
public final class DeviceInfo {
    private final String deviceName;
    private DeviceInfo(String deviceName) { this.deviceName = deviceName == null ? "Unknown" : deviceName; }
    public static DeviceInfo of(String deviceName) { return new DeviceInfo(deviceName); }
    public String value() { return deviceName; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceInfo that = (DeviceInfo) o;
        return deviceName.equals(that.deviceName);
    }
    @Override public int hashCode() { return Objects.hash(deviceName); }
    @Override public String toString() { return deviceName; }
}
