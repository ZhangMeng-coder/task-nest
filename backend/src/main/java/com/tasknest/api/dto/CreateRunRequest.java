package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateRunRequest(@NotBlank(message = "mockScript 不能为空") String mockScript) {
}
