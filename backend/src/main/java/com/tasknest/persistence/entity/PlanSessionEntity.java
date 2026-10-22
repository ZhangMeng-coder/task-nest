package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code tn_plan_session} 创建主任务前的 SSE 规划会话实体。
 * <p>{@code PlanSessionStore} 维护会话生命周期；只有确认后 {@code RunService} 才创建主任务。
 * {@code plan_session_id} 同时作为规划 SSE 的 {@code runId}，{@code plan_json} 为 LONGTEXT
 * JSON，{@code status} 使用规划会话状态枚举名，{@code last_seq} 记录已产生的事件序号。</p>
 */
@TableName("tn_plan_session")
public class PlanSessionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String planSessionId;
    private String goal;
    private String workingDir;
    private String status;
    private String planJson;
    private Long lastSeq;
    private String error;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlanSessionId() {
        return planSessionId;
    }

    public void setPlanSessionId(String planSessionId) {
        this.planSessionId = planSessionId;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public Long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(Long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
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
