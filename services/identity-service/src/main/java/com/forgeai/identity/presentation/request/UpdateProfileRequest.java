package com.forgeai.identity.presentation.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @Size(max = 100, message = "First name cannot exceed 100 characters")
    @Pattern(regexp = "^[a-zA-Z\\- ]*$", message = "First name contains invalid characters")
    String firstName,

    @Size(max = 100, message = "Last name cannot exceed 100 characters")
    @Pattern(regexp = "^[a-zA-Z\\- ]*$", message = "Last name contains invalid characters")
    String lastName
) {}
