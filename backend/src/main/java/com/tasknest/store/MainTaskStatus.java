package com.tasknest.store;

/**
 * 主任务状态与子任务状态分开建模：
 * 主任务只关心整体是否推进、是否等待人、是否完成；重试属于子任务。
 */
public enum MainTaskStatus {
    PENDING,
    RUNNING,
    WAITING_INPUT,
    COMPLETED,
    FAILED;

    /**
     * 是否为终态。主任务失败没有重试语义，直接进入 {@link #FAILED}。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
