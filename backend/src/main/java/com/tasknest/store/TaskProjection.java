package com.tasknest.store;

import com.tasknest.event.EventEnvelope;
import com.tasknest.event.EventPayload;
import com.tasknest.event.TaskRef;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把事件投影为服务端的任务状态。
 *
 * <p>层级被固定为两层：plan 中唯一的根节点是主任务，其余节点必须直接挂在根节点下，
 * 作为子任务。主任务使用 {@link MainTaskStatus}，子任务使用 {@link SubtaskStatus}。
 */
@Component
public class TaskProjection {

    public void apply(RunState run, EventEnvelope event) {
        switch (event.type()) {
            case RUN_STARTED -> run.getMainTask().setGoal(((EventPayload.RunStarted) event.payload()).goal());
            case PLAN -> applyPlan(run, (EventPayload.Plan) event.payload());
            case TASK_STARTED -> applyTaskStarted(run, event);
            case TASK_THOUGHT, PROMPT_ASSEMBLED, TOOL_CALL, TOOL_RESULT -> ensureTask(run, event.task());
            case WAITING_INPUT -> applyWaiting(run, event);
            case INPUT_RECEIVED -> applyInputReceived(run, event);
            case TASK_COMPLETED -> applyTaskCompleted(run, event);
            case TASK_FAILED -> applyTaskFailed(run, event);
            case RUN_COMPLETED -> {
                run.setStatus(RunStatus.COMPLETED);
                run.setTerminal(true);
                MainTaskState main = run.getMainTask();
                if (!main.isTerminal()) {
                    main.setStatus(MainTaskStatus.COMPLETED);
                    if (main.getSummary() == null) {
                        main.setSummary(((EventPayload.RunCompleted) event.payload()).summary());
                    }
                    main.setCompletedAt(event.timestamp());
                }
                repairSubtasks(run, main.getError() == null
                        ? "运行已完成但该子任务未完成"
                        : main.getError());
            }
            case ERROR -> {
                EventPayload.Error error = (EventPayload.Error) event.payload();
                run.setStatus(RunStatus.FAILED);
                run.setTerminal(true);
                MainTaskState main = run.getMainTask();
                if (!main.isTerminal()) {
                    main.setStatus(MainTaskStatus.FAILED);
                    main.setError(error.message());
                    main.setCompletedAt(event.timestamp());
                }
                repairSubtasks(run, error.message());
            }
            case HEARTBEAT -> {
                // transport only
            }
        }
    }

    private void applyPlan(RunState run, EventPayload.Plan plan) {
        TaskRef root = null;
        for (EventPayload.PlannedTask planned : plan.tasks()) {
            if (planned.parentTaskId() == null) {
                if (root != null) {
                    throw new IllegalStateException("plan 只能有一个主任务");
                }
                root = new TaskRef(
                        planned.taskId(), null, planned.title(), planned.agentName());
            }
        }
        if (root == null) {
            throw new IllegalStateException("plan 缺少主任务");
        }

        MainTaskState main = run.getMainTask();
        main.setRootTaskId(root.taskId());
        main.setTitle(root.title());
        main.setAgentName(root.agentName());

        Map<String, TaskNodeState> next = new LinkedHashMap<>();
        int order = 0;
        for (EventPayload.PlannedTask planned : plan.tasks()) {
            if (planned.parentTaskId() == null) {
                continue;
            }
            if (!Objects.equals(planned.parentTaskId(), root.taskId())) {
                throw new IllegalStateException(
                        "任务只允许两层：子任务 " + planned.taskId() + " 必须直接挂在主任务下");
            }
            TaskNodeState node = run.getSubtasks().get(planned.taskId());
            if (node == null) {
                node = new TaskNodeState(
                        planned.taskId(),
                        root.taskId(),
                        planned.title(),
                        planned.agentName(),
                        order);
            } else {
                node.setParentTaskId(root.taskId());
                node.setTitle(planned.title());
                node.setAgentName(planned.agentName());
                node.setOrder(order);
            }
            node.setDependsOn(new java.util.ArrayList<>(planned.dependsOn()));
            next.put(node.getTaskId(), node);
            order++;
        }
        run.getSubtasks().clear();
        run.getSubtasks().putAll(next);
    }

    private void applyTaskStarted(RunState run, EventEnvelope event) {
        TaskRef ref = ensureTask(run, event.task());
        if (ref == null) {
            return;
        }
        if (isMain(run, ref)) {
            MainTaskState main = run.getMainTask();
            if (!main.isTerminal()) {
                main.setStatus(MainTaskStatus.RUNNING);
                if (main.getStartedAt() == null) {
                    main.setStartedAt(event.timestamp());
                }
            }
            return;
        }
        TaskNodeState node = run.getSubtasks().get(ref.taskId());
        if (node != null && !node.isTerminal()) {
            node.setStatus(SubtaskStatus.RUNNING);
            if (node.getStartedAt() == null) {
                node.setStartedAt(event.timestamp());
            }
        }
    }

    private void applyWaiting(RunState run, EventEnvelope event) {
        TaskRef ref = ensureTask(run, event.task());
        if (ref == null) {
            return;
        }
        if (isMain(run, ref)) {
            if (!run.getMainTask().isTerminal()) {
                run.getMainTask().setStatus(MainTaskStatus.WAITING_INPUT);
            }
            return;
        }
        TaskNodeState node = run.getSubtasks().get(ref.taskId());
        if (node != null && !node.isTerminal()) {
            node.setStatus(SubtaskStatus.WAITING_INPUT);
        }
    }

    private void applyInputReceived(RunState run, EventEnvelope event) {
        TaskRef ref = ensureTask(run, event.task());
        if (ref == null) {
            return;
        }
        String optionId = ((EventPayload.InputReceived) event.payload()).selectedOptionId();
        if (isMain(run, ref)) {
            MainTaskState main = run.getMainTask();
            main.setSelectedOptionId(optionId);
            if (main.getStatus() == MainTaskStatus.WAITING_INPUT) {
                main.setStatus(MainTaskStatus.RUNNING);
            }
            return;
        }
        TaskNodeState node = run.getSubtasks().get(ref.taskId());
        if (node != null) {
            node.setSelectedOptionId(optionId);
            if (node.getStatus() == SubtaskStatus.WAITING_INPUT) {
                node.setStatus(SubtaskStatus.RUNNING);
            }
        }
    }

    private void applyTaskCompleted(RunState run, EventEnvelope event) {
        TaskRef ref = ensureTask(run, event.task());
        if (ref == null) {
            return;
        }
        String summary = ((EventPayload.TaskCompleted) event.payload()).summary();
        if (isMain(run, ref)) {
            MainTaskState main = run.getMainTask();
            if (!main.isTerminal()) {
                main.setStatus(MainTaskStatus.COMPLETED);
                main.setSummary(summary);
                main.setError(null);
                main.setCompletedAt(event.timestamp());
            }
            return;
        }
        TaskNodeState node = run.getSubtasks().get(ref.taskId());
        if (node != null && !node.isTerminal()) {
            node.setStatus(SubtaskStatus.SUCCESS);
            node.setSummary(summary);
            node.setError(null);
            node.setCompletedAt(event.timestamp());
        }
    }

    private void applyTaskFailed(RunState run, EventEnvelope event) {
        TaskRef ref = ensureTask(run, event.task());
        if (ref == null) {
            return;
        }
        EventPayload.TaskFailed failed = (EventPayload.TaskFailed) event.payload();
        if (isMain(run, ref)) {
            MainTaskState main = run.getMainTask();
            if (!main.isTerminal()) {
                main.setStatus(MainTaskStatus.FAILED);
                main.setError(failed.error());
                main.setCompletedAt(event.timestamp());
            }
            return;
        }
        TaskNodeState node = run.getSubtasks().get(ref.taskId());
        if (node != null && !node.isTerminal()) {
            node.setError(failed.error());
            node.setStatus(failed.retryable() ? SubtaskStatus.RETRYING : SubtaskStatus.FAILED);
            if (!failed.retryable()) {
                node.setCompletedAt(event.timestamp());
            }
        }
    }

    private TaskRef ensureTask(RunState run, TaskRef ref) {
        if (ref == null) {
            return null;
        }
        if (isMain(run, ref)) {
            MainTaskState main = run.getMainTask();
            if (main.getTitle() == null || main.getTitle().isBlank()) {
                main.setTitle(ref.title());
            }
            if (main.getAgentName() == null) {
                main.setAgentName(ref.agentName());
            }
            return ref;
        }
        if (run.getMainTask().getRootTaskId() == null) {
            // 事件先于 plan 抵达属于异常流；不凭空制造第三个层级，忽略即可。
            return null;
        }
        TaskNodeState node = run.getSubtasks().get(ref.taskId());
        if (node == null) {
            node = new TaskNodeState(
                    ref.taskId(),
                    run.getMainTask().getRootTaskId(),
                    ref.title(),
                    ref.agentName(),
                    run.nextPlaceholderOrder());
            run.getSubtasks().put(ref.taskId(), node);
        } else {
            node.setTitle(ref.title());
            node.setAgentName(ref.agentName());
        }
        return ref;
    }

    private boolean isMain(RunState run, TaskRef ref) {
        return ref != null && Objects.equals(ref.taskId(), run.getMainTask().getRootTaskId());
    }

    private void repairSubtasks(RunState run, String message) {
        for (TaskNodeState node : run.getSubtasks().values()) {
            if (!node.isTerminal()) {
                node.setStatus(SubtaskStatus.FAILED);
                if (node.getError() == null) {
                    node.setError(message);
                }
            }
        }
    }
}
