package com.forgeai.identity.api.controller;

import com.forgeai.identity.api.dto.LoginRequest;
import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.api.dto.RegisterRequest;
import com.forgeai.identity.api.dto.RegisterResponse;
import com.forgeai.identity.application.port.in.AuthenticationUseCase;
import com.forgeai.identity.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationUseCase authenticationUseCase;
  
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authenticationUseCase.login(request.identifier(), request.password());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = authenticationUseCase.register(
                request.email(),
                request.username(),
                request.password(),
                request.firstName(),
                request.lastName()
        );
        
        RegisterResponse response = new RegisterResponse(
                user.getId(),
                user.getEmail().value(),
                user.getUsername().value(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus().name()
        );
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<com.forgeai.identity.api.dto.RefreshTokenResponse> refresh(@Valid @RequestBody com.forgeai.identity.api.dto.RefreshTokenRequest request) {
        var response = authenticationUseCase.refresh(request.refreshToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<com.forgeai.identity.api.dto.LogoutResponse> logout(
            @RequestBody(required = false) com.forgeai.identity.api.dto.RefreshTokenRequest request,
            org.springframework.security.core.Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof java.util.UUID userId) {
            String refreshToken = request != null ? request.refreshToken() : null;
            authenticationUseCase.logout(userId, refreshToken);
        }
        return ResponseEntity.ok(new com.forgeai.identity.api.dto.LogoutResponse("Successfully logged out"));
    }

    @PostMapping("/logout-all")
    public ResponseEntity<com.forgeai.identity.api.dto.LogoutResponse> logoutAll(
            org.springframework.security.core.Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof java.util.UUID userId) {
            authenticationUseCase.logoutAll(userId);
        }
        return ResponseEntity.ok(new com.forgeai.identity.api.dto.LogoutResponse("Successfully logged out from all sessions"));
    }
}
