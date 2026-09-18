package com.forgeai.identity.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    // Dependencies will be injected here (e.g., AuthenticationUseCase)

    @PostMapping("/login")
    public ResponseEntity<Void> login() {
        // Placeholder for login implementation
        return ResponseEntity.status(501).build(); // 501 Not Implemented
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register() {
        // Placeholder for registration implementation
        return ResponseEntity.status(501).build(); // 501 Not Implemented
    }
}
