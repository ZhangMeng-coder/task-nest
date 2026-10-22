package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code tn_subtask} 直属子任务档案实体，只保存两层任务模型中的子任务。
 * <p>{@code RunPersistenceService} 依据 {@code plan} 与 {@code task_*} 事件创建并更新；
 * {@code status} 使用 {@link com.tasknest.store.SubtaskStatus} 枚举名，{@code depends_on}
 * 与 {@code waiting_options} 为 JSON 文本，{@code (main_task_id, task_id)} 唯一。</p>
 */
@TableName("tn_subtask")
public class SubtaskEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String mainTaskId;
    private String taskId;
    private String title;
    private String agentName;
    private String status;
    private Integer sortOrder;
    private String dependsOn;
    private String summary;
    private String error;
    private String waitingQuestion;
    private String waitingOptions;
    private String selectedOptionId;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMainTaskId() {
        return mainTaskId;
    }

    public void setMainTaskId(String mainTaskId) {
        this.mainTaskId = mainTaskId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(String dependsOn) {
        this.dependsOn = dependsOn;
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

    public String getWaitingQuestion() {
        return waitingQuestion;
    }

    public void setWaitingQuestion(String waitingQuestion) {
        this.waitingQuestion = waitingQuestion;
    }

    public String getWaitingOptions() {
        return waitingOptions;
    }

    public void setWaitingOptions(String waitingOptions) {
        this.waitingOptions = waitingOptions;
    }

    public String getSelectedOptionId() {
        return selectedOptionId;
    }

    public void setSelectedOptionId(String selectedOptionId) {
        this.selectedOptionId = selectedOptionId;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
