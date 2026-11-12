package com.forgeai.project.infrastructure.security;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_ORG_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> CURRENT_PERMISSIONS = new ThreadLocal<>();
    private static final ThreadLocal<UUID> CURRENT_WORKSPACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_WORKSPACE_SCOPE = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID organizationId, UUID userId, List<String> permissions) {
        CURRENT_ORG_ID.set(organizationId);
        CURRENT_USER_ID.set(userId);
        CURRENT_PERMISSIONS.set(permissions != null ? permissions : Collections.emptyList());
    }

    public static void setWorkspaceId(UUID workspaceId) {
        CURRENT_WORKSPACE_ID.set(workspaceId);
    }

    public static UUID getWorkspaceId() {
        return CURRENT_WORKSPACE_ID.get();
    }

    public static void setWorkspaceScope(String scope) {
        CURRENT_WORKSPACE_SCOPE.set(scope);
    }

    public static String getWorkspaceScope() {
        return CURRENT_WORKSPACE_SCOPE.get();
    }

    public static UUID getOrganizationId() {
        return CURRENT_ORG_ID.get();
    }

    public static UUID getUserId() {
        return CURRENT_USER_ID.get();
    }

    public static List<String> getPermissions() {
        List<String> perms = CURRENT_PERMISSIONS.get();
        return perms != null ? perms : Collections.emptyList();
    }

    public static boolean hasPermission(String permission) {
        List<String> perms = getPermissions();
        return perms.contains(permission);
    }

    public static void clear() {
        CURRENT_ORG_ID.remove();
        CURRENT_USER_ID.remove();
        CURRENT_PERMISSIONS.remove();
        CURRENT_WORKSPACE_ID.remove();
        CURRENT_WORKSPACE_SCOPE.remove();
    }
}
