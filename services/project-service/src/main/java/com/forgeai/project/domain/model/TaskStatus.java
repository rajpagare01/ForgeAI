package com.forgeai.project.domain.model;

public enum TaskStatus {
    TODO,
    IN_PROGRESS,
    IN_REVIEW,
    DONE,
    BLOCKED;

    public boolean canTransitionTo(TaskStatus next) {
        if (next == null) {
            return false;
        }
        if (this == next) {
            return true;
        }
        return switch (this) {
            case TODO -> next == IN_PROGRESS || next == IN_REVIEW || next == DONE || next == BLOCKED;
            case IN_PROGRESS -> next == IN_REVIEW || next == DONE || next == BLOCKED || next == TODO;
            case IN_REVIEW -> next == DONE || next == IN_PROGRESS || next == BLOCKED || next == TODO;
            case DONE -> next == IN_PROGRESS || next == IN_REVIEW || next == TODO;
            case BLOCKED -> next == TODO || next == IN_PROGRESS || next == IN_REVIEW || next == DONE;
        };
    }
}
