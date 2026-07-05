package com.forgeai.identity.presentation.controller;

import com.forgeai.identity.application.dto.CurrentUserDto;
import com.forgeai.identity.application.query.GetCurrentUserQuery;
import com.forgeai.identity.application.usecase.GetCurrentUserUseCase;
import com.forgeai.identity.presentation.factory.ResponseEntityFactory;
import com.forgeai.identity.presentation.mapper.ProfilePresentationMapper;
import com.forgeai.identity.presentation.request.UpdateProfileRequest;
import com.forgeai.identity.presentation.response.ProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/profile")
@Tag(name = "Profile", description = "Endpoints for managing user profile details")
@SecurityRequirement(name = "bearerAuth")
public class ProfileController {

    private final GetCurrentUserUseCase getCurrentUserUseCase;
    private final ProfilePresentationMapper mapper;

    public ProfileController(
            GetCurrentUserUseCase getCurrentUserUseCase,
            ProfilePresentationMapper mapper) {
        this.getCurrentUserUseCase = getCurrentUserUseCase;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Get user profile")
    public ResponseEntity<ProfileResponse> getProfile(Principal principal) {
        UUID userId = extractUserId(principal);
        GetCurrentUserQuery query = new GetCurrentUserQuery(userId);
        CurrentUserDto currentUser = getCurrentUserUseCase.execute(query);
        return ResponseEntityFactory.ok(mapper.toResponse(currentUser));
    }

    @PutMapping
    @Operation(summary = "Update user profile")
    public ResponseEntity<ProfileResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            Principal principal) {
        // Since there is no UpdateProfileCommand in the Application Layer, we throw UnsupportedOperationException.
        // This ensures we strictly adhere to Clean Architecture without modifying the Application Layer.
        throw new UnsupportedOperationException("Updating profile is not yet supported by the application layer.");
    }

    private UUID extractUserId(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new IllegalArgumentException("User is not authenticated");
        }
        return UUID.fromString(principal.getName());
    }
}
