package com.forgeai.identity.infrastructure.security;

import com.forgeai.identity.api.controller.AuthController;
import com.forgeai.identity.application.port.in.AuthenticationUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private AuthenticationUseCase authenticationUseCase;

    @Test
    void registerRouteIsPublic() throws Exception {
        // Will return 400 Bad Request because body is missing, proving it bypassed 401 Unauthorized
        mockMvc.perform(post("/api/v1/auth/register"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginRouteIsPublic() throws Exception {
        // Will return 400 Bad Request because body is missing, proving it bypassed 401 Unauthorized
        mockMvc.perform(post("/api/v1/auth/login"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void protectedRouteRejectsAnonymousAccess() throws Exception {
        mockMvc.perform(get("/api/v1/some-protected-route"))
                .andExpect(status().isUnauthorized()); // 401 Unauthorized
    }

    @Test
    void validJwtAuthenticatesRequest() throws Exception {
        String token = "valid.jwt.token";
        when(jwtService.validateToken(token)).thenReturn(true);
        when(jwtService.extractUserId(token)).thenReturn(UUID.randomUUID());

        // Will return 404 because the route doesn't exist, but it bypassed 401
        mockMvc.perform(get("/api/v1/some-protected-route")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedJwtIsRejected() throws Exception {
        String token = "malformed-token";
        when(jwtService.validateToken(token)).thenReturn(false);

        mockMvc.perform(get("/api/v1/some-protected-route")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingBearerPrefixIsRejected() throws Exception {
        String token = "valid.jwt.token";
        // Header without "Bearer "
        mockMvc.perform(get("/api/v1/some-protected-route")
                .header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }
}
