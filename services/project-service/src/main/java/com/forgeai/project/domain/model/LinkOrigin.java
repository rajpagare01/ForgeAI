package com.forgeai.project.domain.model;

public enum LinkOrigin {
    EXPLICIT_USER_LINK,
    GITHUB_REFERENCE,
    COMMIT_MESSAGE,
    PR_REFERENCE,
    AST,
    TEST_EVIDENCE,
    AI_ANALYSIS,
    CROSS_REPO,
    DERIVED
}
