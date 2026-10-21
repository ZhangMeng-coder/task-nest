package com.tasknest.api.dto;

import com.tasknest.service.ApiKeyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * apiKeyValue 在 LITERAL 模式下是密钥本身（留空表示沿用已保存值），
 * 在 ENV 模式下是环境变量名。
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
