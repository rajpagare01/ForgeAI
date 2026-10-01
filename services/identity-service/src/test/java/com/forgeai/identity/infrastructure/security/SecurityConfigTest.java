package com.forgeai.identity.infrastructure.security;

import com.forgeai.identity.api.controller.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test
    void publicRoutesAccessible() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login"))
                .andExpect(status().isNotImplemented()); // Returns 501 currently
    }

    @Test
    void protectedRoutesRejectAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/some-protected-route"))
                .andExpect(status().isUnauthorized()); // 401 Unauthorized
    }
}
