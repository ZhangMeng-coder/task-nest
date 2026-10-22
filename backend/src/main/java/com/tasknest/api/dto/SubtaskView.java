package com.tasknest.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.tasknest.event.EventPayload;
import com.tasknest.store.SubtaskStatus;

import java.time.Instant;
import java.util.List;

/**
 * 子任务档案视图，随主任务详情返回，对应主任务直属的一层子任务。
 *
 * <p>{@code status} 使用 {@code SubtaskStatus}（可能为 {@code REVIEWING} /
 * {@code CANCELLED} / {@code RETRYING}）；{@code order} 为执行顺序，
 * {@code dependsOn} 为依赖的子任务 ID。等待抉择时 {@code waitingQuestion} /
 * {@code waitingOptions} 有值，{@code selectedOptionId} 记录已提交选项；
 * {@code startedAt}、{@code completedAt} 在未开始或未结束时为 {@code null}。</p>
 */
public record SubtaskView(
        String taskId,
        String title,
        String agentName,
        SubtaskStatus status,
        int order,
        List<String> dependsOn,
        String summary,
        String error,
        String waitingQuestion,
        List<EventPayload.DecisionOption> waitingOptions,
        String selectedOptionId,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
