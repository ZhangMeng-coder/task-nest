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

/**
 * 进程内的运行注册表：按 {@code runId} 保存 {@link RunState}，并提供快照投影。
 *
 * <p>状态不持久化，进程重启后不可续传；但已写入 MySQL 的主任务档案仍可查询。
 * 快照在运行锁内构建，保证主任务、子任务与事件序号来自同一时刻。</p>
 */
@Component
public class RunStore {

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    /**
     * 创建并登记运行。调用方保证 {@code runId} 在进程内唯一。
     */
    public RunState create(String runId,
                           String mainTaskId,
                           String title,
                           String goal) {
        RunState run = new RunState(runId, mainTaskId, title, goal);
        runs.put(runId, run);
        return run;
    }

    /**
     * 判断运行是否仍在内存中；进程重启后的旧 runId 返回 {@code false}。
     */
    public boolean contains(String runId) {
        return runs.containsKey(runId);
    }

    /**
     * 取回运行；不存在时抛出 {@link ErrorCode#RUN_NOT_FOUND}。
     */
    public RunState require(String runId) {
        RunState run = runs.get(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        }
        return run;
    }

    /**
     * 在运行锁内生成一致快照：主任务、按计划顺序排列的子任务与完整事件列表。
     * 等待抉择信息只挂到对应的任务上。
     */
    public RunSnapshot snapshot(String runId) {
        RunState run = require(runId);
        synchronized (run.getLock()) {
            MainTaskState main = run.getMainTask();
            RunSnapshot.RunInfo info = new RunSnapshot.RunInfo(
                    run.getRunId(),
                    main.getMainTaskId(),
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
        return new RunSnapshot.WaitingInputSnapshot(
                waiting.question(), waiting.options(), waiting.allowCustom(), waiting.customPlaceholder());
    }
}
