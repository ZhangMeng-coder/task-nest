package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviseStoredPlanRequest(
        @NotBlank(message = "先输入一条修改提示")
        @Size(max = 500, message = "修改提示最多 500 字")
        String instruction
) {
}
