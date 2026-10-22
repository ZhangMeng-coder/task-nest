package com.tasknest.api.dto;

/**
 * 创建规划会话的响应，对应 {@code POST /api/main-tasks/plan-sessions}。
 *
 * <p>{@code planSessionId} 同时作为规划 SSE 的 {@code runId}；{@code status} 是创建时的
 * 会话状态创建后通常为 {@code RUNNING}；
 * {@code lastSeq} 是创建时已产生的事件序号，前端可用它作为续传起点。</p>
 */
public record CreatePlanSessionResponse(
        String planSessionId,
        String status,
        long lastSeq
) {
}
