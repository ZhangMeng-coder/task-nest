package com.tasknest.api.dto;

import com.tasknest.service.ApiKeyType;

import java.time.Instant;

/**
 * LLM 设置视图，用于 {@code GET /api/settings/llm} 与 {@code PUT /api/settings/llm}。
 *
 * <p>{@code baseUrl} 为 OpenAI 兼容 Base URL，{@code modelName} 为模型名称。
 * {@code apiKeyType} 区分 {@code LITERAL} / {@code ENV}：literal 密钥永不返回明文，
 * {@code apiKeyValue} 为 {@code null}，仅由 {@code apiKeyPreview} 返回脱敏掩码；
 * ENV 模式下 {@code apiKeyValue} 返回变量名，{@code apiKeyPreview} 为 {@code null}。
 * {@code apiKeyConfigured} 表示是否已配置，{@code envResolved} 表示 ENV 变量当前能否解析到值。</p>
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
