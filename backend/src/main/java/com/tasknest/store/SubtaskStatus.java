package com.tasknest.store;

/**
 * 直属子任务状态。重试与审核只作用于子任务：
 * {@code RETRYING} 表示可重试失败或审核要求返工，{@code REVIEWING} 表示审核中；
 * {@code SUCCESS} / {@code FAILED} / {@code CANCELLED} 为终态。
 */
public enum SubtaskStatus {
    PENDING,
    RUNNING,
    WAITING_INPUT,
    RETRYING,
    REVIEWING,
    SUCCESS,
    FAILED,
    CANCELLED;

    /**
     * 是否为终态。非终态节点仍可被后续事件推进或标记取消。
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
