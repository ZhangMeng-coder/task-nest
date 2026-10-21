package com.tasknest.orchestrator;

import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;

import java.util.List;
import java.util.Map;

public record MockEvent(
        long delayMs,
        EventType type,
        String taskId,
        TaskRef task,
        EventPayload payload,
        String eventId,
        long dispatchDelayMs,
        boolean dropDispatch,
        Map<String, List<MockEvent>> branches
) {
}
