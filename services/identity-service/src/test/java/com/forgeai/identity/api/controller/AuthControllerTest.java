package com.forgeai.identity.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.api.dto.LoginRequest;
import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.api.dto.RegisterRequest;
import com.forgeai.identity.application.port.in.AuthenticationUseCase;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

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

    @Test
    void login_Success() throws Exception {
        LoginResponse mockResponse = new LoginResponse("token123", UUID.randomUUID(), "testuser", "test@example.com");
        when(authenticationUseCase.login("test@example.com", "Password123!")).thenReturn(mockResponse);

        LoginRequest request = new LoginRequest("test@example.com", "Password123!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("token123"))
                .andExpect(jsonPath("$.username").value("testuser"));
    }

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
                .andExpect(jsonPath("$.password").doesNotExist()) // ensure password isn't leaked
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
}
