package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePlanSessionRequest(
        @NotBlank(message = "目标不能为空")
        @Size(max = 4000, message = "目标最多 4000 字")
        String goal,
        @NotBlank(message = "必须选择工作目录")
        @Size(max = 1024, message = "工作目录最多 1024 字")
        String workingDir,
        @Size(max = 64, message = "mockScript 最多 64 字")
        String mockScript
) {
}
