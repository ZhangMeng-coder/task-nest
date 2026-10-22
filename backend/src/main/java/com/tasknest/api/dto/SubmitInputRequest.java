package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 抉择提交请求，同时用于 {@code POST /api/runs/{runId}/inputs} 与
 * {@code POST /api/main-tasks/plan-sessions/{planSessionId}/inputs}。
 *
 * <p>{@code taskId} 必填，必须对应当前处于 {@code WAITING_INPUT} 的任务；
 * {@code selectedOptionId} 与 {@code customInput} 至少提交一个，具体校验在服务层按当前
 * waiting_input 契约执行（选项须属于该任务，自由输入最多 4000 字）。</p>
 */
public record SubmitInputRequest(
        @NotBlank(message = "taskId 不能为空") String taskId,
        @Size(max = 64, message = "selectedOptionId 最多 64 字") String selectedOptionId,
        @Size(max = 4000, message = "customInput 最多 4000 字") String customInput
) {
}
