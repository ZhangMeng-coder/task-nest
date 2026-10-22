package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 已保存计划表的修订请求，对应
 * {@code POST /api/main-tasks/{mainTaskId}/plan/revisions}。
 *
 * <p>{@code instruction} 必填且最多 500 字。修订只更新计划表，不增删已运行的子任务；
 * 主任务已完成或失败时计划只读。</p>
 */
public record ReviseStoredPlanRequest(
        @NotBlank(message = "先输入一条修改提示")
        @Size(max = 500, message = "修改提示最多 500 字")
        String instruction
) {
}
