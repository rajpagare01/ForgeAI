package com.forgeai.testing.infrastructure.security;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_ORGANIZATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> CURRENT_PERMISSIONS = new ThreadLocal<>();

    private TenantContext() {}

    public static void setOrganizationId(UUID organizationId) {
        CURRENT_ORGANIZATION_ID.set(organizationId);
    }

    public static UUID getOrganizationId() {
        return CURRENT_ORGANIZATION_ID.get();
    }

    public static void setUserId(UUID userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static UUID getUserId() {
        return CURRENT_USER_ID.get();
    }

    public static void setPermissions(List<String> permissions) {
        CURRENT_PERMISSIONS.set(permissions);
    }

    public static List<String> getPermissions() {
        List<String> perms = CURRENT_PERMISSIONS.get();
        return perms != null ? perms : Collections.emptyList();
    }

    public static void clear() {
        CURRENT_ORGANIZATION_ID.remove();
        CURRENT_USER_ID.remove();
        CURRENT_PERMISSIONS.remove();
    }
}
