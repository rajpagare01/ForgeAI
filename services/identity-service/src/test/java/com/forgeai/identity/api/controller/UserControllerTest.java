package com.forgeai.identity.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.api.dto.*;
import com.forgeai.identity.application.port.in.UserProfileUseCase;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.exception.UserAlreadyExistsException;
import com.forgeai.identity.infrastructure.security.JwtAuthenticationFilter;
import com.forgeai.identity.infrastructure.security.JwtAuthenticationToken;
import com.forgeai.identity.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.annotation.Import;
import com.forgeai.identity.infrastructure.security.SecurityConfig;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
public class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserProfileUseCase userProfileUseCase;

    @MockBean
    private JwtService jwtService;



    private UUID userId;
    private UserProfileResponse profileResponse;
    private final String VALID_TOKEN = "valid.jwt.token";

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        profileResponse = new UserProfileResponse(
                userId, "test@example.com", "testuser", "First", "Last",
                "ACTIVE", true, null, Instant.now(), Instant.now()
        );
        
        when(jwtService.validateToken(VALID_TOKEN)).thenReturn(true);
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn(userId);
    }

    @Test
    void getProfile_Authenticated_Returns200() throws Exception {
        when(userProfileUseCase.getProfile(userId)).thenReturn(profileResponse);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.username").value("testuser"));
    }

    @Test
    void getProfile_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateProfile_ValidRequest_Returns200() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest("NewFirst", "NewLast");
        UserProfileResponse updatedProfile = new UserProfileResponse(
                userId, "test@example.com", "testuser", "NewFirst", "NewLast",
                "ACTIVE", true, null, Instant.now(), Instant.now()
        );

        when(userProfileUseCase.updateProfile(userId, "NewFirst", "NewLast")).thenReturn(updatedProfile);

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("NewFirst"))
                .andExpect(jsonPath("$.lastName").value("NewLast"));
    }

    @Test
    void changeUsername_ValidRequest_Returns200() throws Exception {
        ChangeUsernameRequest request = new ChangeUsernameRequest("newuser");
        UserProfileResponse updatedProfile = new UserProfileResponse(
                userId, "test@example.com", "newuser", "First", "Last",
                "ACTIVE", true, null, Instant.now(), Instant.now()
        );

        when(userProfileUseCase.changeUsername(userId, "newuser")).thenReturn(updatedProfile);

        mockMvc.perform(patch("/api/v1/users/me/username")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newuser"));
    }

    @Test
    void changeUsername_DuplicateUsername_Returns409() throws Exception {
        ChangeUsernameRequest request = new ChangeUsernameRequest("taken");
        
        when(userProfileUseCase.changeUsername(userId, "taken"))
                .thenThrow(new UserAlreadyExistsException("Username already in use"));

        mockMvc.perform(patch("/api/v1/users/me/username")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void requestEmailChange_ValidRequest_Returns200() throws Exception {
        EmailChangeRequest request = new EmailChangeRequest("new@example.com");
        UserProfileResponse updatedProfile = new UserProfileResponse(
                userId, "test@example.com", "testuser", "First", "Last",
                "ACTIVE", true, "new@example.com", Instant.now(), Instant.now()
        );

        when(userProfileUseCase.requestEmailChange(userId, "new@example.com")).thenReturn(updatedProfile);

        mockMvc.perform(post("/api/v1/users/me/email-change")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingEmail").value("new@example.com"));
    }

    @Test
    void changePassword_ValidRequest_Returns200() throws Exception {
        PasswordChangeRequest request = new PasswordChangeRequest("CurrentP@ss1", "NewStr0ng!Pass");
        
        doNothing().when(userProfileUseCase).changePassword(userId, "CurrentP@ss1", "NewStr0ng!Pass");

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void changePassword_WrongCurrentPassword_Returns401() throws Exception {
        PasswordChangeRequest request = new PasswordChangeRequest("wrong", "NewStr0ng!Pass");
        
        doThrow(new InvalidCredentialsException("Current password is incorrect"))
                .when(userProfileUseCase).changePassword(userId, "wrong", "NewStr0ng!Pass");

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
    
    @Test
    void changePassword_WeakNewPassword_Returns400() throws Exception {
        PasswordChangeRequest request = new PasswordChangeRequest("CurrentP@ss1", "weak");
        
        doThrow(new IllegalArgumentException("Password must contain..."))
                .when(userProfileUseCase).changePassword(userId, "CurrentP@ss1", "weak");

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deactivateAccount_Returns200() throws Exception {
        doNothing().when(userProfileUseCase).deactivateAccount(userId);

        mockMvc.perform(post("/api/v1/users/me/deactivate")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }
}
