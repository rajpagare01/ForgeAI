package com.forgeai.identity.domain.model;

public enum PermissionCode {
    ORGANIZATION_UPDATE("ORGANIZATION_UPDATE"),
    ORGANIZATION_DELETE("ORGANIZATION_DELETE"),
    MEMBER_INVITE("MEMBER_INVITE"),
    MEMBER_REMOVE("MEMBER_REMOVE"),
    ROLE_ASSIGN("ROLE_ASSIGN"),
    ROLE_ADMINISTER("ROLE_ADMINISTER"),
    TEAM_CREATE("TEAM_CREATE"),
    TEAM_UPDATE("TEAM_UPDATE"),
    TEAM_DELETE("TEAM_DELETE"),
    TEAM_MEMBER_ADD("TEAM_MEMBER_ADD"),
    TEAM_MEMBER_REMOVE("TEAM_MEMBER_REMOVE"),
    PROJECT_READ("PROJECT_READ"),
    PROJECT_CREATE("PROJECT_CREATE"),
    PROJECT_UPDATE("PROJECT_UPDATE"),
    PROJECT_DELETE("PROJECT_DELETE");

    private final String value;

    PermissionCode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
