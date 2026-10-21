package com.tasknest.service;

public enum PlanSessionStatus {
    RUNNING,
    WAITING_INPUT,
    READY,
    CONFIRMED,
    CANCELLED,
    FAILED;

    public boolean terminal() {
        return this == CONFIRMED || this == CANCELLED || this == FAILED;
    }
}
