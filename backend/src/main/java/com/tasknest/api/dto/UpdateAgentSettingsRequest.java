package com.tasknest.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 更新 Agent 审核设置的请求，对应 {@code PUT /api/settings/agent}。
 *
 * <p>{@code reviewEnabled} 与 {@code reviewMaxReworks} 均必填；后者范围为 0–5，
 * 表示审核失败时允许的最大重做次数。保存后立即热更新运行时配置。</p>
 */
public record UpdateAgentSettingsRequest(
        @NotNull(message = "reviewEnabled 不能为空") Boolean reviewEnabled,
        @NotNull(message = "reviewMaxReworks 不能为空")
        @Min(value = 0, message = "reviewMaxReworks 最小为 0")
        @Max(value = 5, message = "reviewMaxReworks 最大为 5")
        Integer reviewMaxReworks
) {
}
