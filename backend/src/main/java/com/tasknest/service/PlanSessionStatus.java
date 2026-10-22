package com.tasknest.service;

/**
 * 规划会话状态。正常流转为 {@code RUNNING → READY → CONFIRMED}；
 * 需要人判断时进入 {@code WAITING_INPUT}，提交后回到 {@code RUNNING}。
 * {@code CANCELLED} 与 {@code FAILED} 是异常终止，三者均为终止态。
 */
public enum PlanSessionStatus {
    RUNNING,
    WAITING_INPUT,
    READY,
    CONFIRMED,
    CANCELLED,
    FAILED;

    /**
     * 是否为终止态；终止后不再接受修订、抉择或确认。
     */
    public boolean terminal() {
        return this == CONFIRMED || this == CANCELLED || this == FAILED;
    }
}
