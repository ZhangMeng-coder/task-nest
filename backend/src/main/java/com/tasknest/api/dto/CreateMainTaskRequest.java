package com.tasknest.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMainTaskRequest(
        @NotBlank(message = "mockScript 不能为空") String mockScript,
        @Size(max = 2000, message = "goal 最多 2000 字") String goal,
        @Size(max = 1024, message = "workingDir 最多 1024 字") String workingDir,
        @Valid PlanDraftInput plan
) {
}
