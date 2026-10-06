package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateTeamRequest {
    @NotBlank(message = "Name cannot be empty")
    private String name;
    
    private String description;
}
