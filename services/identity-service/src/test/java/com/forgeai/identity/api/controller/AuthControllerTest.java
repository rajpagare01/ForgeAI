package com.forgeai.identity.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.api.dto.LoginRequest;
import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.api.dto.RegisterRequest;
import com.forgeai.identity.application.port.in.AuthenticationUseCase;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.exception.InvalidUserStateException;
import com.forgeai.identity.domain.exception.UserAlreadyExistsException;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.infrastructure.security.JwtAuthenticationFilter;
import com.forgeai.identity.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthenticationUseCase authenticationUseCase;

    @MockBean
    private com.forgeai.identity.infrastructure.security.JwtService jwtService;

    // --- Login Tests ---

    @Test
    void login_Success() throws Exception {
        LoginResponse mockResponse = new LoginResponse(
            "token123", 
            "refresh123", 
            "Bearer", 
            900, 
            new LoginResponse.UserDto(UUID.randomUUID(), "testuser", "test@example.com", "Test", "User")
        );
        when(authenticationUseCase.login("test@example.com", "Password123!")).thenReturn(mockResponse);

        LoginRequest request = new LoginRequest("test@example.com", "Password123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("token123"))
                .andExpect(jsonPath("$.refreshToken").value("refresh123"))
                .andExpect(jsonPath("$.user.username").value("testuser"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist());
    }

    @Test
    void login_WrongPassword() throws Exception {
        when(authenticationUseCase.login("test@example.com", "wrongpass")).thenThrow(new InvalidCredentialsException("Invalid credentials"));

        LoginRequest request = new LoginRequest("test@example.com", "wrongpass");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void login_UnknownIdentifier() throws Exception {
        when(authenticationUseCase.login("unknown@example.com", "Password123!")).thenThrow(new InvalidCredentialsException("Invalid credentials"));

        LoginRequest request = new LoginRequest("unknown@example.com", "Password123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void login_BlockedAccount() throws Exception {
        when(authenticationUseCase.login("test@example.com", "Password123!")).thenThrow(new InvalidUserStateException("User account is not active"));

        LoginRequest request = new LoginRequest("test@example.com", "Password123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- Register Tests ---

    @Test
    void register_Success() throws Exception {
        User mockUser = new User();
        mockUser.setId(UUID.randomUUID());
        mockUser.setEmail(new Email("test@example.com"));
        mockUser.setUsername(new Username("testuser"));
        mockUser.setFirstName("Test");
        mockUser.setLastName("User");
        mockUser.setStatus(UserStatus.ACTIVE);

        when(authenticationUseCase.register("test@example.com", "testuser", "Password123!", "Test", "User")).thenReturn(mockUser);

        RegisterRequest request = new RegisterRequest("test@example.com", "testuser", "Password123!", "Test", "User");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void register_MalformedEmail() throws Exception {
        RegisterRequest request = new RegisterRequest("invalidemail", "testuser", "Password123!", "Test", "User");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void register_MissingRequiredField() throws Exception {
        RegisterRequest request = new RegisterRequest("test@example.com", "", "Password123!", "Test", "User"); // Blank username

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void register_WeakPassword() throws Exception {
        // Validation is technically pushed to domain layer in this architecture, but we simulate the throw
        when(authenticationUseCase.register(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Password must contain at least one uppercase letter..."));
        
        RegisterRequest request = new RegisterRequest("test@example.com", "testuser", "weak", "Test", "User");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void register_DuplicateIdentifier_Conflict() throws Exception {
        when(authenticationUseCase.register("test@example.com", "testuser", "Password123!", "Test", "User"))
                .thenThrow(new UserAlreadyExistsException("Email already in use"));

        RegisterRequest request = new RegisterRequest("test@example.com", "testuser", "Password123!", "Test", "User");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void register_DuplicateIdentifier_DataIntegrity() throws Exception {
        when(authenticationUseCase.register("test@example.com", "testuser", "Password123!", "Test", "User"))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        RegisterRequest request = new RegisterRequest("test@example.com", "testuser", "Password123!", "Test", "User");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("A record with this identifier already exists"));
    }

    // --- Refresh Tests ---
    @Test
    void refresh_Success() throws Exception {
        com.forgeai.identity.api.dto.RefreshTokenResponse mockResponse = new com.forgeai.identity.api.dto.RefreshTokenResponse(
            "new_access", "new_refresh", "Bearer", 900
        );
        when(authenticationUseCase.refresh("raw_token")).thenReturn(mockResponse);

        com.forgeai.identity.api.dto.RefreshTokenRequest request = new com.forgeai.identity.api.dto.RefreshTokenRequest("raw_token");

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new_access"))
                .andExpect(jsonPath("$.refreshToken").value("new_refresh"));
    }

    // --- Logout Tests ---
    @Test
    void logout_Success() throws Exception {
        com.forgeai.identity.api.dto.RefreshTokenRequest request = new com.forgeai.identity.api.dto.RefreshTokenRequest("raw_token");
        // We mock a user for the SecurityContext
        
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully logged out"));
    }

    @Test
    void logoutAll_Success() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully logged out from all sessions"));
    }
}
