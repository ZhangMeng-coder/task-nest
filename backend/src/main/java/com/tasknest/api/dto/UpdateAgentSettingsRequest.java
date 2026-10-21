package com.tasknest.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateAgentSettingsRequest(
        @NotNull(message = "reviewEnabled 不能为空") Boolean reviewEnabled,
        @NotNull(message = "reviewMaxReworks 不能为空")
        @Min(value = 0, message = "reviewMaxReworks 最小为 0")
        @Max(value = 5, message = "reviewMaxReworks 最大为 5")
        Integer reviewMaxReworks
) {
}
