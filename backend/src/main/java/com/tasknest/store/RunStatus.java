package com.tasknest.store;

/**
 * 运行级状态。{@code COMPLETED} / {@code FAILED} 为终态，分别由
 * {@code run_completed} 与 {@code error} 事件触发，写入后不再回退。
 */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
