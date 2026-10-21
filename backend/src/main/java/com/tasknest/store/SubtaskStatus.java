package com.tasknest.store;

public enum SubtaskStatus {
    PENDING,
    RUNNING,
    WAITING_INPUT,
    RETRYING,
    SUCCESS,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
