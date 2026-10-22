package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code tn_main_task} 主任务档案实体，一行对应一次运行。
 * <p>{@code RunPersistenceService} 创建 PENDING 记录，并随运行事件更新状态、摘要、
 * 错误与等待抉择；{@code status} 使用 {@link com.tasknest.store.MainTaskStatus} 枚举名，
 * {@code waiting_options} 为决策选项 JSON 文本。</p>
 */
@TableName("tn_main_task")
public class MainTaskEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String mainTaskId;
    private String runId;
    private String rootTaskId;
    private String title;
    private String goal;
    private String status;
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

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
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

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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
