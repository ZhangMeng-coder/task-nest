package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 计划表中的一行，只描述主任务直属的一层子任务，与执行时的 {@code plan} 事件一一对应。
 *
 * <p>{@code title} 与 {@code agent} 必填；{@code dependency}、{@code output} 为展示与
 * 调度辅助信息。P3 新增字段 {@code taskId}、{@code description}、
 * {@code acceptanceCriteria}、{@code allowedTools}、{@code testCommand} 均为可选，
 * 旧前端与旧档案可以省略。{@code testCommand} 为 {@code none} 时审核阶段自动探测测试命令。</p>
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
        String output,
        @Size(max = 64, message = "taskId 最多 64 字")
        String taskId,
        @Size(max = 4000, message = "任务描述最多 4000 字")
        String description,
        @Size(max = 20, message = "验收标准最多 20 条")
        List<@Size(max = 500, message = "单条验收标准最多 500 字") String> acceptanceCriteria,
        @Size(max = 10, message = "允许工具最多 10 个")
        List<@Size(max = 64, message = "工具名最多 64 字") String> allowedTools,
        @Size(max = 500, message = "测试命令最多 500 字")
        String testCommand
) {

    public PlanRowView(String title, String agent, String dependency, String output) {
        this(title, agent, dependency, output, null, null, null, null, null);
    }
}
