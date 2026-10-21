package com.tasknest.store;

import com.tasknest.event.EventEnvelope;
import com.tasknest.event.EventPayload;

import java.time.Instant;
import java.util.List;

/**
 * 快照明确区分主任务与子任务，任务层级固定为两层：
 * mainTask 是根，subtasks 是它直属的阶段列表。
 */
public record RunSnapshot(
        RunInfo run,
        MainTaskSnapshot mainTask,
        List<SubtaskSnapshot> subtasks,
        List<EventEnvelope> events
) {

    public record RunInfo(
            String runId,
            String mainTaskId,
            String mockScript,
            String goal,
            RunStatus status,
            Instant createdAt,
            Instant updatedAt,
            long lastSeq
    ) {
    }

    public record MainTaskSnapshot(
            String mainTaskId,
            String taskId,
            String title,
            String agentName,
            MainTaskStatus status,
            String summary,
            String error,
            String selectedOptionId,
            WaitingInputSnapshot waitingInput,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    public record SubtaskSnapshot(
            String taskId,
            String title,
            String agentName,
            SubtaskStatus status,
            String summary,
            String error,
            List<String> dependsOn,
            int order,
            String selectedOptionId,
            WaitingInputSnapshot waitingInput,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    public record WaitingInputSnapshot(
            String question,
            List<EventPayload.DecisionOption> options
    ) {
    }
}
