package com.tasknest.store;

import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventEnvelope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunStore {

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public RunState create(String runId,
                           String mainTaskId,
                           String mockScript,
                           String title,
                           String goal) {
        RunState run = new RunState(runId, mainTaskId, mockScript, title, goal);
        runs.put(runId, run);
        return run;
    }

    public boolean contains(String runId) {
        return runs.containsKey(runId);
    }

    public RunState require(String runId) {
        RunState run = runs.get(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        }
        return run;
    }

    public RunSnapshot snapshot(String runId) {
        RunState run = require(runId);
        synchronized (run.getLock()) {
            MainTaskState main = run.getMainTask();
            RunSnapshot.RunInfo info = new RunSnapshot.RunInfo(
                    run.getRunId(),
                    main.getMainTaskId(),
                    run.getMockScript(),
                    main.getGoal(),
                    run.getStatus(),
                    run.getCreatedAt(),
                    run.getUpdatedAt(),
                    run.getLastSeq());
            RunSnapshot.MainTaskSnapshot mainSnapshot = new RunSnapshot.MainTaskSnapshot(
                    main.getMainTaskId(),
                    main.getRootTaskId(),
                    main.getTitle(),
                    main.getAgentName(),
                    main.getStatus(),
                    main.getSummary(),
                    main.getError(),
                    main.getSelectedOptionId(),
                    waitingSnapshot(run, main.getRootTaskId()),
                    main.getStartedAt(),
                    main.getCompletedAt());
            List<RunSnapshot.SubtaskSnapshot> subtasks = run.getSubtasks().values().stream()
                    .sorted(Comparator.comparingInt(TaskNodeState::getOrder))
                    .map(node -> new RunSnapshot.SubtaskSnapshot(
                            node.getTaskId(),
                            node.getTitle(),
                            node.getAgentName(),
                            node.getStatus(),
                            node.getSummary(),
                            node.getError(),
                            List.copyOf(node.getDependsOn()),
                            node.getOrder(),
                            node.getSelectedOptionId(),
                            waitingSnapshot(run, node.getTaskId()),
                            node.getStartedAt(),
                            node.getCompletedAt()))
                    .toList();
            List<EventEnvelope> events = new ArrayList<>(run.getEventsCopy());
            return new RunSnapshot(info, mainSnapshot, subtasks, events);
        }
    }

    private RunSnapshot.WaitingInputSnapshot waitingSnapshot(RunState run, String taskId) {
        WaitingInputState waiting = run.getWaitingInput();
        if (waiting == null || taskId == null || !taskId.equals(waiting.taskId())) {
            return null;
        }
        return new RunSnapshot.WaitingInputSnapshot(waiting.question(), waiting.options());
    }
}
