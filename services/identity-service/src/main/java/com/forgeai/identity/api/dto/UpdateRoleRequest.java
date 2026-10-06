package com.forgeai.identity.api.dto;

import com.forgeai.identity.domain.model.RoleScope;
import lombok.Data;

@Data
public class UpdateRoleRequest {
    private String name;
    private String description;
    private RoleScope scope;
}
