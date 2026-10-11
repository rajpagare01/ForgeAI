package com.forgeai.identity.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.api.dto.*;
import com.forgeai.identity.application.service.OrganizationService;
import com.forgeai.identity.domain.model.Organization;
import com.forgeai.identity.domain.model.OrganizationStatus;
import com.forgeai.identity.domain.valueobject.OrganizationSlug;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import org.springframework.context.annotation.Import;
import com.forgeai.identity.infrastructure.security.SecurityConfig;
import com.forgeai.identity.infrastructure.security.RsaKeyPropertiesConfig;

@WebMvcTest(OrganizationController.class)
@Import({SecurityConfig.class, RsaKeyPropertiesConfig.class})
@AutoConfigureMockMvc
class OrganizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrganizationService organizationService;

    @MockBean
    private com.forgeai.identity.infrastructure.security.JwtService jwtService;



    private UUID userId;
    private UsernamePasswordAuthenticationToken auth;
    private Organization activeOrg;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
        
        activeOrg = new Organization();
        activeOrg.setId(UUID.randomUUID());
        activeOrg.setName("Test Org");
        activeOrg.setSlug(new OrganizationSlug("test-org"));
        activeOrg.setDescription("Desc");
        activeOrg.setStatus(OrganizationStatus.ACTIVE);
        activeOrg.setCreatedAt(Instant.now());
        activeOrg.setUpdatedAt(Instant.now());
    }

    @Test
    void createOrganization() throws Exception {
        CreateOrganizationRequest request = new CreateOrganizationRequest("Test Org", "test-org", "Desc");
        
        when(organizationService.createOrganization(eq("Test Org"), eq("test-org"), eq("Desc"), eq(userId)))
            .thenReturn(activeOrg);

        mockMvc.perform(post("/api/v1/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .with(authentication(auth)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Org"))
                .andExpect(jsonPath("$.slug").value("test-org"));
    }

    @Test
    void getOrganizations() throws Exception {
        when(organizationService.getUserOrganizations(userId)).thenReturn(List.of(activeOrg));

        mockMvc.perform(get("/api/v1/organizations")
                .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Test Org"));
    }

    @Test
    void getOrganization() throws Exception {
        when(organizationService.getOrganization(activeOrg.getId(), userId)).thenReturn(activeOrg);

        mockMvc.perform(get("/api/v1/organizations/" + activeOrg.getId())
                .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Org"));
    }
}
