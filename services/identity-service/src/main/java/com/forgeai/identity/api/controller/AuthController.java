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

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationUseCase authenticationUseCase;
  
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse response = authenticationUseCase.login(request.identifier(), request.password());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
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
}
