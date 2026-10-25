package com.forgeai.identity.application.port.in;

import com.forgeai.identity.api.dto.GitHubAppConfigResponse;
import com.forgeai.identity.api.dto.GitHubAuthStartResponse;
import com.forgeai.identity.api.dto.GitHubIdentityDto;
import com.forgeai.identity.api.dto.GitHubStatusResponse;
import com.forgeai.identity.api.dto.LoginResponse;

import java.util.UUID;

public interface GitHubAuthenticationUseCase {

    GitHubAppConfigResponse getConfig();

    GitHubAuthStartResponse startAuthorization(UUID userId, String redirectUri);

    LoginResponse handleCallback(String code, String state, String redirectUri);

    GitHubIdentityDto linkAccount(UUID userId, String code, String state, String redirectUri);

    void disconnectAccount(UUID userId);

    GitHubStatusResponse getStatus(UUID userId);
}
