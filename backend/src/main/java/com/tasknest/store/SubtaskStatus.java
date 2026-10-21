package com.tasknest.store;

public enum SubtaskStatus {
    PENDING,
    RUNNING,
    WAITING_INPUT,
    RETRYING,
    REVIEWING,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
