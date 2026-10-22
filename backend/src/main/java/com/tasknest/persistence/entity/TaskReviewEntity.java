package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code tn_task_review} 每次子任务审核一份记录。
 * <p>{@code RunPersistenceService#saveReview} 在 {@code review_completed} 且任务为直属
 * 子任务时写入；{@code verdict} 为 PASS/REWORK，{@code issues} 与 {@code test_evidence}
 * 以 LONGTEXT JSON 保存。</p>
 */
@TableName("tn_task_review")
public class TaskReviewEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String mainTaskId;
    private String taskId;
    private Integer attempt;
    private String verdict;
    private String summary;
    private String issues;
    private String testStatus;
    private String testEvidence;
    private String modelName;
    private Long durationMs;
    private LocalDateTime createdAt;

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

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getIssues() {
        return issues;
    }

    public void setIssues(String issues) {
        this.issues = issues;
    }

    public String getTestStatus() {
        return testStatus;
    }

    public void setTestStatus(String testStatus) {
        this.testStatus = testStatus;
    }

    public String getTestEvidence() {
        return testEvidence;
    }

    public void setTestEvidence(String testEvidence) {
        this.testEvidence = testEvidence;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
