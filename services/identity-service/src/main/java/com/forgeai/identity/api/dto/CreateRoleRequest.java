package com.forgeai.identity.api.dto;

import com.forgeai.identity.domain.model.RoleScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateRoleRequest {
    @NotBlank(message = "Name cannot be empty")
    private String name;
    
    private String description;
    
    @NotNull(message = "Scope is required")
    private RoleScope scope;
}
