package com.forgeai.identity.presentation.response;

public record VersionResponse(
    String service,
    String version,
    String build,
    String commit,
    String javaVersion,
    String springBootVersion,
    String timestamp
) {}
