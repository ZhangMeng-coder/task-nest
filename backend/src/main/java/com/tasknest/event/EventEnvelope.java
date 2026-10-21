package com.tasknest.event;

import java.time.Instant;

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
