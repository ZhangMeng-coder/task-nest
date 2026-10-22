package com.tasknest.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 直属子任务在内存中的实时状态，由 {@link TaskProjection} 根据事件推进。
 *
 * <p>{@code order} 决定界面展示顺序；事件早于 plan 抵达时由投影创建占位节点，
 * 使用较大的排序号排在正常计划行之后。字段在运行锁保护下读写。</p>
 */
public class TaskNodeState {

    private final String taskId;
    private String parentTaskId;
    private String title;
    private String agentName;
    private SubtaskStatus status = SubtaskStatus.PENDING;
    private List<String> dependsOn = new ArrayList<>();
    private int order;
    private String summary;
    private String error;
    private String selectedOptionId;
    private Instant startedAt;
    private Instant completedAt;

    public TaskNodeState(String taskId, String parentTaskId, String title, String agentName, int order) {
        this.taskId = taskId;
        this.parentTaskId = parentTaskId;
        this.title = title;
        this.agentName = agentName;
        this.order = order;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getParentTaskId() {
        return parentTaskId;
    }

    public void setParentTaskId(String parentTaskId) {
        this.parentTaskId = parentTaskId;
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

    public SubtaskStatus getStatus() {
        return status;
    }

    public void setStatus(SubtaskStatus status) {
        this.status = status;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
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
     * 子任务是否已进入终态。
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }
}
