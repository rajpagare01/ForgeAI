package com.forgeai.identity.presentation.controller;

import com.forgeai.identity.application.command.ChangePasswordCommand;
import com.forgeai.identity.application.dto.CurrentUserDto;
import com.forgeai.identity.application.query.GetCurrentUserQuery;
import com.forgeai.identity.application.usecase.ChangePasswordUseCase;
import com.forgeai.identity.application.usecase.GetCurrentUserUseCase;
import com.forgeai.identity.presentation.factory.ResponseEntityFactory;
import com.forgeai.identity.presentation.mapper.AccountPresentationMapper;
import com.forgeai.identity.presentation.request.ChangePasswordRequest;
import com.forgeai.identity.presentation.response.SuccessMessageResponse;
import com.forgeai.identity.presentation.response.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "Account", description = "Endpoints for managing the authenticated user's account")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final GetCurrentUserUseCase getCurrentUserUseCase;
    private final ChangePasswordUseCase changePasswordUseCase;
    private final AccountPresentationMapper mapper;

    public AccountController(
            GetCurrentUserUseCase getCurrentUserUseCase,
            ChangePasswordUseCase changePasswordUseCase,
            AccountPresentationMapper mapper) {
        this.getCurrentUserUseCase = getCurrentUserUseCase;
        this.changePasswordUseCase = changePasswordUseCase;
        this.mapper = mapper;
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user profile")
    public ResponseEntity<UserResponse> getCurrentUser(Principal principal) {
        UUID userId = extractUserId(principal);
        GetCurrentUserQuery query = new GetCurrentUserQuery(userId);
        CurrentUserDto currentUser = getCurrentUserUseCase.execute(query);
        return ResponseEntityFactory.ok(mapper.toResponse(currentUser));
    }

    @PutMapping("/password")
    @Operation(summary = "Change account password")
    public ResponseEntity<SuccessMessageResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Principal principal) {
        UUID userId = extractUserId(principal);
        ChangePasswordCommand command = mapper.toCommand(userId, request);
        changePasswordUseCase.execute(command);
        return ResponseEntityFactory.ok(new SuccessMessageResponse("Password changed successfully."));
    }

    @DeleteMapping
    @Operation(summary = "Delete account")
    public ResponseEntity<Void> deleteAccount(Principal principal) {
        UUID userId = extractUserId(principal);
        // Note: The use cases provided earlier (e.g., SuspendAccount, LockAccount) do not include a hard delete.
        // We throw UnsupportedOperationException to map to 501 / generic 500 until implemented in Application layer.
        throw new UnsupportedOperationException("Account deletion is not yet supported.");
    }

    private UUID extractUserId(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new IllegalArgumentException("User is not authenticated");
        }
        return UUID.fromString(principal.getName());
    }
}
