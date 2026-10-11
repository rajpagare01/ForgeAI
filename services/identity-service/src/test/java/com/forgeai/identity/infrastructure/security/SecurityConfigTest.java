package com.forgeai.identity.infrastructure.security;

import com.forgeai.identity.api.controller.AuthController;
import com.forgeai.identity.api.controller.JwksController;
import com.forgeai.identity.api.controller.UserController;
import com.forgeai.identity.application.port.in.AuthenticationUseCase;
import com.forgeai.identity.application.port.in.UserProfileUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, JwksController.class, UserController.class})
@Import({SecurityConfig.class, RsaKeyPropertiesConfig.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthenticationUseCase authenticationUseCase;

    @MockBean
    private UserProfileUseCase userProfileUseCase;

    @Test
    void jwksEndpointIsPubliclyAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk());
    }

    @Test
    void registerRouteIsPublic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register"))
                .andExpect(status().isBadRequest()); // 400 Bad Request indicates bypass of 401
    }

    @Test
    void loginRouteIsPublic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login"))
                .andExpect(status().isBadRequest()); // 400 Bad Request indicates bypass of 401
    }

    @Test
    void protectedRouteRejectsAnonymousAccess() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized()); // 401 Unauthorized
    }

    @Test
    void validJwtAuthenticatesRequest() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk()); // 200 OK indicates authentication succeeded
    }
}

