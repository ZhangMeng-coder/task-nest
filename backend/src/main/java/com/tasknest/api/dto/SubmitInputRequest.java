package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 选项与自由输入至少提交一个；具体校验在服务层按当前 waiting_input 契约执行。
 */
public record SubmitInputRequest(
        @NotBlank(message = "taskId 不能为空") String taskId,
        @Size(max = 64, message = "selectedOptionId 最多 64 字") String selectedOptionId,
        @Size(max = 4000, message = "customInput 最多 4000 字") String customInput
) {
}
