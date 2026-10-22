package com.tasknest.api.dto;

import com.tasknest.event.EventPayload;

/**
 * 规划会话快照，由 {@code GET /api/main-tasks/plan-sessions/{planSessionId}} 返回。
 *
 * <p>{@code status} 取值为 {@code RUNNING}、{@code WAITING_INPUT}、{@code READY}、
 * {@code CONFIRMED}、{@code CANCELLED} 或 {@code FAILED}。{@code plan} 在会话就绪前为
 * {@code null}；{@code lastSeq} 用于规划 SSE 续传；{@code error} 仅在失败时有值。</p>
 */
public record PlanSessionView(
        String planSessionId,
        String status,
        String goal,
        String workingDir,
        EventPayload.Plan plan,
        long lastSeq,
        String error
) {
}
