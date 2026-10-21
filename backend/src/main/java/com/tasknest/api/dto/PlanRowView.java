package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 计划表中的一行。只描述主任务直属的一层子任务，与执行时的 plan 事件一一对应。
 */
public record PlanRowView(
        @NotBlank(message = "计划步骤标题不能为空")
        @Size(max = 120, message = "计划步骤标题最多 120 字")
        String title,
        @NotBlank(message = "执行 Agent 不能为空")
        @Size(max = 64, message = "执行 Agent 最多 64 字")
        String agent,
        @Size(max = 120, message = "依赖描述最多 120 字")
        String dependency,
        @Size(max = 120, message = "预期产出最多 120 字")
        String output
) {
}
