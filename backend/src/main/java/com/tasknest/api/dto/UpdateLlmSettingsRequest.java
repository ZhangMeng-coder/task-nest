package com.tasknest.api.dto;

import com.tasknest.service.ApiKeyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新 LLM 连接设置的请求，对应 {@code PUT /api/settings/llm}。
 *
 * <p>{@code baseUrl}（OpenAI 兼容 Base URL）、{@code modelName} 与 {@code apiKeyType}
 * 必填。{@code apiKeyValue} 在 {@code LITERAL} 模式下是密钥本身（已有密钥时留空表示沿用
 * 已保存值，否则返回 {@code INVALID_PARAM}），在 {@code ENV} 模式下是环境变量名。
 * 保存后立即替换运行时配置，响应只返回掩码。</p>
 */
public record UpdateLlmSettingsRequest(
        @NotBlank(message = "baseUrl 不能为空")
        @Size(max = 500, message = "baseUrl 最多 500 字")
        String baseUrl,
        @NotBlank(message = "modelName 不能为空")
        @Size(max = 128, message = "modelName 最多 128 字")
        String modelName,
        @NotNull(message = "apiKeyType 不能为空") ApiKeyType apiKeyType,
        @Size(max = 2048, message = "apiKeyValue 最多 2048 字") String apiKeyValue
) {
}
