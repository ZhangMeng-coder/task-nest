package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GeneratePlanRequest(
        @NotBlank(message = "mockScript 不能为空") String mockScript,
        @NotBlank(message = "先用一句话描述目标")
        @Size(max = 2000, message = "goal 最多 2000 字")
        String goal
) {
}
