package com.tasknest.api.dto;

import com.tasknest.store.RunStatus;

import java.time.Instant;

public record CreateRunResponse(
        String runId,
        String mainTaskId,
        String mockScript,
        RunStatus status,
        Instant createdAt
) {
}
