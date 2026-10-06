package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AssignPermissionRequest {
    @NotBlank(message = "Permission code cannot be empty")
    private String permissionCode;
}
