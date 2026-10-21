package com.tasknest.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ArtifactView(
        String taskId,
        long seq,
        String kind,
        String callId,
        String toolName,
        String status,
        Long durationMs,
        JsonNode content,
        Instant createdAt
) {
}
