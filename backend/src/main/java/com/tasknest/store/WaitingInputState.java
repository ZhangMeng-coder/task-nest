package com.tasknest.store;

import com.tasknest.event.EventPayload;
import com.tasknest.event.TaskRef;

import java.util.List;
import java.util.concurrent.BlockingQueue;

public record WaitingInputState(
        String taskId,
        TaskRef task,
        String question,
        List<EventPayload.DecisionOption> options,
        Boolean allowCustom,
        String customPlaceholder,
        BlockingQueue<UserInput> queue
) {
}
