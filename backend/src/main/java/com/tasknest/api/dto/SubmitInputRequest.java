package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmitInputRequest(
        @NotBlank(message = "taskId 不能为空") String taskId,
        @NotBlank(message = "selectedOptionId 不能为空") String selectedOptionId
) {
}
