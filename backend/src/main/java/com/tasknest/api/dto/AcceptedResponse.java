package com.tasknest.api.dto;

/**
 * 抉择提交的受理结果，供 {@code POST /api/runs/{runId}/inputs} 与
 * {@code POST /api/main-tasks/plan-sessions/{planSessionId}/inputs} 返回（HTTP 202）。
 *
 * <p>字段原样回显本次提交：运行模式下 {@code runId} 是运行标识，规划会话模式下复用为
 * {@code planSessionId}；{@code selectedOptionId} 与 {@code customInput} 至少一个有效，
 * 未提交的字段为 {@code null}。受理只表示输入已入队，任务仍在后台继续。</p>
 */
public record AcceptedResponse(
        String runId,
        String taskId,
        String selectedOptionId,
        String customInput
) {
    public AcceptedResponse(String runId, String taskId, String selectedOptionId) {
        this(runId, taskId, selectedOptionId, null);
    }
}
