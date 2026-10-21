package com.tasknest.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.tasknest.event.EventPayload;
import com.tasknest.store.SubtaskStatus;

import java.time.Instant;
import java.util.List;

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
