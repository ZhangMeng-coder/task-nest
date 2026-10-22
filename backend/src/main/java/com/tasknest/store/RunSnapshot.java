package com.tasknest.store;

import com.tasknest.event.EventEnvelope;
import com.tasknest.event.EventPayload;

import java.time.Instant;
import java.util.List;

/**
 * 运行快照：明确区分主任务与子任务，任务层级固定为两层，
 * {@code mainTask} 是根，{@code subtasks} 是它直属的阶段列表。
 *
 * <p>{@code events} 为完整事件列表，供客户端重建账本；{@code run.lastSeq}
 * 作为后续 {@code afterSeq} 续传游标。快照在运行锁内构建，字段一致性由调用方保证。</p>
 */
public record RunSnapshot(
        RunInfo run,
        MainTaskSnapshot mainTask,
        List<SubtaskSnapshot> subtasks,
        List<EventEnvelope> events
) {

    /**
     * 运行级信息。
     *
     * @param lastSeq 快照生成时的最后事件序号，客户端应以此为续传游标
     */
    public record RunInfo(
            String runId,
            String mainTaskId,
            String goal,
            RunStatus status,
            Instant createdAt,
            Instant updatedAt,
            long lastSeq
    ) {
    }

    /**
     * 主任务快照。
     *
     * @param taskId 计划根节点 ID；与 {@code mainTaskId} 不同，后者是档案主键
     * @param waitingInput 当前等待抉择的问题；不等待该任务时为 {@code null}
     */
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

    /**
     * 直属子任务快照。
     *
     * @param order 展示顺序，按计划行顺序递增；占位节点使用大序号排在末尾
     */
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
            List<EventPayload.DecisionOption> options,
            Boolean allowCustom,
            String customPlaceholder
    ) {
    }
}
