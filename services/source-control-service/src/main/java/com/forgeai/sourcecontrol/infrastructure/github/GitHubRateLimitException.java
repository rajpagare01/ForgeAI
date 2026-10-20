package com.forgeai.sourcecontrol.infrastructure.github;

import lombok.Getter;

@Getter
public class GitHubRateLimitException extends GitHubApiException {
    private final Long retryAfterSeconds;
    private final Integer remainingQuota;
    private final Long resetTimestamp;

    public GitHubRateLimitException(String message, Long retryAfterSeconds, Integer remainingQuota, Long resetTimestamp) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.remainingQuota = remainingQuota;
        this.resetTimestamp = resetTimestamp;
    }
}
