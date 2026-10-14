package com.forgeai.sourcecontrol.infrastructure.github;

public class GitHubAccessDeniedException extends RuntimeException {
    public GitHubAccessDeniedException(String message) {
        super(message);
    }
}
