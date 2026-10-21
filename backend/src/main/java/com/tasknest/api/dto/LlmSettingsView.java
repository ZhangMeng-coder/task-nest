package com.tasknest.api.dto;

import com.tasknest.service.ApiKeyType;

import java.time.Instant;

/**
 * LLM 设置读取视图。literal 密钥永不返回明文，只返回掩码；ENV 模式返回变量名。
 */
public record LlmSettingsView(
        String baseUrl,
        String modelName,
        ApiKeyType apiKeyType,
        String apiKeyValue,
        String apiKeyPreview,
        boolean apiKeyConfigured,
        boolean envResolved,
        Instant updatedAt
) {
}
