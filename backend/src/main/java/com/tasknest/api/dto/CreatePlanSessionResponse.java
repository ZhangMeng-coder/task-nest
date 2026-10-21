package com.tasknest.api.dto;

public record CreatePlanSessionResponse(
        String planSessionId,
        String status,
        long lastSeq
) {
}
