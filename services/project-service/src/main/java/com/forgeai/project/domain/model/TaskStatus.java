package com.forgeai.project.domain.model;

import java.util.Set;

public enum TaskStatus {
    TODO,
    IN_PROGRESS,
    IN_REVIEW,
    DONE,
    BLOCKED;

    public boolean canTransitionTo(TaskStatus next) {
        if (this == next) {
            return true;
        }
        return switch (this) {
            case TODO -> next == IN_PROGRESS || next == BLOCKED;
            case IN_PROGRESS -> next == IN_REVIEW || next == BLOCKED || next == TODO;
            case IN_REVIEW -> next == DONE || next == IN_PROGRESS || next == BLOCKED;
            case DONE -> next == IN_PROGRESS || next == TODO; // reopening
            case BLOCKED -> next == TODO || next == IN_PROGRESS || next == IN_REVIEW;
        };
    }
}
