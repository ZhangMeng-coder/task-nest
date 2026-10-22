package com.tasknest.event;

import java.time.Instant;

/**
 * 统一事件信封，运行与规划会话共用同一形状。
 *
 * <p>{@code seq} 从 1 严格递增，心跳也占号；{@code eventId} 通常由 seq 推导。
 * 规划会话事件的 {@code runId} 等于 {@code planSessionId}，因此前端可用同一套
 * 去重、缓冲与 {@code afterSeq} 续传逻辑处理两类流。{@code task} 标识事件归属，
 * 根任务事件更新主任务，直属子任务事件更新子任务。</p>
 */
public record EventEnvelope(
        String eventId,
        String runId,
        long seq,
        EventType type,
        Instant timestamp,
        TaskRef task,
        EventPayload payload
) {
}
