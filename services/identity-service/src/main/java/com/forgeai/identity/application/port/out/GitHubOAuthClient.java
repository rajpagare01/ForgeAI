package com.forgeai.identity.application.port.out;

import java.util.List;

public interface GitHubOAuthClient {

    record OAuthToken(
            String accessToken,
            String tokenType,
            String scope
    ) {}

    record UserProfile(
            Long id,
            String login,
            String name,
            String email,
            String avatarUrl,
            String htmlUrl
    ) {}

    record UserEmail(
            String email,
            boolean primary,
            boolean verified,
            String visibility
    ) {}

    OAuthToken exchangeCodeForToken(String code, String redirectUri);

    UserProfile fetchUserProfile(String accessToken);

    List<UserEmail> fetchUserEmails(String accessToken);
}
