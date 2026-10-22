package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code tn_llm_setting} LLM 连接设置实体，单例表固定 {@code id = 1}。
 * <p>由 {@code LlmSettingsService} 加载与保存；{@code api_key_type} 区分
 * {@code LITERAL}/{@code ENV}，{@code api_key_value} 分别保存字面密钥或环境变量名。</p>
 */
@TableName("tn_llm_setting")
public class LlmSettingEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String baseUrl;
    private String apiKeyType;
    private String apiKeyValue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyType() {
        return apiKeyType;
    }

    public void setApiKeyType(String apiKeyType) {
        this.apiKeyType = apiKeyType;
    }

    public String getApiKeyValue() {
        return apiKeyValue;
    }

    public void setApiKeyValue(String apiKeyValue) {
        this.apiKeyValue = apiKeyValue;
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
