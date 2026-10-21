package com.tasknest.api.dto;

import com.tasknest.store.MainTaskStatus;

import java.time.Instant;

public record CreateMainTaskResponse(
        String mainTaskId,
        String runId,
        String mockScript,
        MainTaskStatus status,
        Instant createdAt,
        PlanDraftView plan
) {
}
