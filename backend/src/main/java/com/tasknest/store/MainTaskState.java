package com.tasknest.store;

import java.time.Instant;

/**
 * 主任务在内存中的实时状态，由 {@link TaskProjection} 根据根任务事件推进。
 *
 * <p>主任务与子任务分开建模、分开结束：主任务只使用 {@link MainTaskStatus}，
 * 重试语义只属于子任务。字段在运行锁的保护下读写，快照时复制。</p>
 */
public class MainTaskState {

    private final String mainTaskId;
    private String rootTaskId;
    private String title;
    private String agentName;
    private String goal;
    private MainTaskStatus status = MainTaskStatus.PENDING;
    private String summary;
    private String error;
    private String selectedOptionId;
    private Instant startedAt;
    private Instant completedAt;

    public MainTaskState(String mainTaskId, String title, String goal) {
        this.mainTaskId = mainTaskId;
        this.title = title;
        this.goal = goal;
    }

    public String getMainTaskId() {
        return mainTaskId;
    }

    public String getRootTaskId() {
        return rootTaskId;
    }

    public void setRootTaskId(String rootTaskId) {
        this.rootTaskId = rootTaskId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public MainTaskStatus getStatus() {
        return status;
    }

    public void setStatus(MainTaskStatus status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getSelectedOptionId() {
        return selectedOptionId;
    }

    public void setSelectedOptionId(String selectedOptionId) {
        this.selectedOptionId = selectedOptionId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    /**
     * 主任务是否已进入终态（完成或失败）。
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }
}
