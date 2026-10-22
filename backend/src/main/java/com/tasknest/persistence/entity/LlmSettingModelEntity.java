package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code tn_llm_setting_model} LLM 模型名称实体，单例表固定 {@code id = 1}。
 * <p>作为 {@code tn_llm_setting} 的配套表，由 {@code LlmSettingsService} 与连接设置一并
 * 读写，默认模型为 {@code gpt-4o}；单独建表让既有数据库无需对旧表补列。</p>
 */
@TableName("tn_llm_setting_model")
public class LlmSettingModelEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String modelName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
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
