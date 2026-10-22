package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code tn_agent_setting} 审核 Agent 全局设置实体，单例表固定 {@code id = 1}。
 * <p>由 {@code AgentSettingsService} 启动加载、保存后热更新；{@code review_enabled}
 * 控制独立审核阶段，{@code review_max_reworks} 限制 0–5 次返工。</p>
 */
@TableName("tn_agent_setting")
public class AgentSettingEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private Boolean reviewEnabled;
    private Integer reviewMaxReworks;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Boolean getReviewEnabled() {
        return reviewEnabled;
    }

    public void setReviewEnabled(Boolean reviewEnabled) {
        this.reviewEnabled = reviewEnabled;
    }

    public Integer getReviewMaxReworks() {
        return reviewMaxReworks;
    }

    public void setReviewMaxReworks(Integer reviewMaxReworks) {
        this.reviewMaxReworks = reviewMaxReworks;
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
