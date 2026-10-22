package com.tasknest.api.dto;

/**
 * 规划会话确认结果，由 {@code POST /api/main-tasks/plan-sessions/{planSessionId}/confirm}
 * 返回。
 *
 * <p>{@code workingDir} 为确认时规范化的绝对路径；{@code planVersion} 是归档计划表的
 * 修订号（从 1 开始）。确认后前端应改用 {@code runId} 订阅运行 SSE。</p>
 */
public record ConfirmPlanSessionResponse(
        String mainTaskId,
        String runId,
        String workingDir,
        int planVersion
) {
}
