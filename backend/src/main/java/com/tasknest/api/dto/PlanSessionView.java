package com.tasknest.api.dto;

import com.tasknest.event.EventPayload;

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
