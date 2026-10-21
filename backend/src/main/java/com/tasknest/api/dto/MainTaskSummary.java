package com.tasknest.api.dto;

import com.tasknest.store.MainTaskStatus;

import java.time.Instant;

public record MainTaskSummary(
        String mainTaskId,
        String runId,
        String rootTaskId,
        String title,
        String goal,
        String mockScript,
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
