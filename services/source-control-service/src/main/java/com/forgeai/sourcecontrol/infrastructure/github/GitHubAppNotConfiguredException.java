package com.forgeai.sourcecontrol.infrastructure.github;

public class GitHubAppNotConfiguredException extends RuntimeException {
    public GitHubAppNotConfiguredException(String message) {
        super(message);
    }
}
