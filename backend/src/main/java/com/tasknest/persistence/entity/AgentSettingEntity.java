package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 审核 Agent 全局设置。单例表，固定 id = 1。 */
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
