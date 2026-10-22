package com.tasknest.api.dto;

import com.tasknest.store.MainTaskStatus;

import java.time.Instant;

/**
 * 主任务档案摘要，用于 {@code GET /api/main-tasks} 列表及详情中的 {@code mainTask} 字段。
 *
 * <p>{@code planVersion} 为已保存计划表的版本（0 表示无计划）；{@code status} 使用
 * {@code MainTaskStatus}；{@code subtaskCount} / {@code completedSubtaskCount} 用于展示
 * 进度。{@code completedAt} 与 {@code error} 在未完成或未失败时为 {@code null}。</p>
 */
public record MainTaskSummary(
        String mainTaskId,
        String runId,
        String rootTaskId,
        String title,
        String goal,
        String workingDir,
        int planVersion,
        MainTaskStatus status,
        String summary,
        String error,
        long subtaskCount,
        long completedSubtaskCount,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
