package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Invalid email format")
    String email,
    
    @NotBlank(message = "Username cannot be blank")
    @Size(min = 3, max = 39, message = "Username must be between 3 and 39 characters")
    String username,
    
    @NotBlank(message = "Password cannot be blank")
    String password,
    
    String firstName,
    String lastName
) {}
