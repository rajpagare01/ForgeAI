package com.forgeai.identity.presentation.response;

import java.util.UUID;

public record AuthenticationResponse(
    UserResponse user,
    TokenResponse tokens
) {}
