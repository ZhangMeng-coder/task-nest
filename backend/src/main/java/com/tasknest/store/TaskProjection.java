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
 * 投影在运行锁内由 {@code EventPublisher} 调用，改动的是内存状态；事件原文另行落库。</p>
 *
 * <p>终止事件会补全主任务终态，并把仍未结束的子任务统一修复为 {@code FAILED}，
 * 保证快照不会留下运行结束后仍处于进行中的节点。终态节点不会被后续事件覆盖。</p>
 */
@Component
public class TaskProjection {

    /**
     * 按事件类型推进运行状态。未知或纯传输事件（心跳）不改变状态；
     * 分支内部会忽略已终止节点的重复写入，保证投影幂等。
     */
    public void apply(RunState run, EventEnvelope event) {
        switch (event.type()) {
            case RUN_STARTED -> run.getMainTask().setGoal(((EventPayload.RunStarted) event.payload()).goal());
            case PLAN -> applyPlan(run, (EventPayload.Plan) event.payload());
            case TASK_STARTED -> applyTaskStarted(run, event);
            case TASK_THOUGHT, PROMPT_ASSEMBLED, TOOL_CALL, TOOL_RESULT, GIT_CHECKPOINT ->
                    ensureTask(run, event.task());
            case WAITING_INPUT -> applyWaiting(run, event);
            case INPUT_RECEIVED -> applyInputReceived(run, event);
            case TASK_COMPLETED -> applyTaskCompleted(run, event);
            case TASK_FAILED -> applyTaskFailed(run, event);
            case REVIEW_STARTED -> applyReviewStarted(run, event);
            case REVIEW_COMPLETED -> applyReviewCompleted(run, event);
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

    /**
     * 应用计划（首次或动态重排）。校验唯一的根节点、两层结构与任务 ID 唯一性；
     * 已有节点保持状态，只有仍为 {@code PENDING} 的节点接受标题 / 依赖更新，
     * 被移除的 {@code PENDING} 节点转为 {@code CANCELLED}。
     *
     * @throws IllegalStateException 计划缺少根、出现多层 / 重复 ID 时抛出；
     *                               异常会向外传播，该事件不会继续持久化或推送
     */
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

        // 动态重排：保留已有任务节点及其状态，只更新仍在新计划中的 PENDING 任务；
        // 被移除的 PENDING 任务转为 CANCELLED，已开始/已完成的节点保持原状。
        Map<String, TaskNodeState> current = new LinkedHashMap<>(run.getSubtasks());
        Map<String, TaskNodeState> next = new LinkedHashMap<>();
        int order = 0;
        java.util.Set<String> plannedIds = new java.util.LinkedHashSet<>();
        for (EventPayload.PlannedTask planned : plan.tasks()) {
            if (planned.parentTaskId() == null) {
                continue;
            }
            if (!Objects.equals(planned.parentTaskId(), root.taskId())) {
                throw new IllegalStateException(
                        "任务只允许两层：子任务 " + planned.taskId() + " 必须直接挂在主任务下");
            }
            if (!plannedIds.add(planned.taskId())) {
                throw new IllegalStateException("plan 中任务 ID 重复: " + planned.taskId());
            }
            TaskNodeState node = current.get(planned.taskId());
            if (node == null) {
                node = new TaskNodeState(
                        planned.taskId(),
                        root.taskId(),
                        planned.title(),
                        planned.agentName(),
                        order);
            } else {
                if (node.getStatus() == SubtaskStatus.PENDING) {
                    node.setParentTaskId(root.taskId());
                    node.setTitle(planned.title());
                    node.setAgentName(planned.agentName());
                    node.setOrder(order);
                    node.setDependsOn(new java.util.ArrayList<>(
                            planned.dependsOn() == null ? List.of() : planned.dependsOn()));
                }
            }
            if (node.getStatus() == SubtaskStatus.PENDING
                    && (node.getDependsOn() == null || node.getDependsOn().isEmpty())) {
                node.setDependsOn(new java.util.ArrayList<>(
                        planned.dependsOn() == null ? List.of() : planned.dependsOn()));
            }
            next.put(node.getTaskId(), node);
            order++;
        }
        for (TaskNodeState existing : current.values()) {
            if (next.containsKey(existing.getTaskId())) {
                continue;
            }
            if (existing.getStatus() == SubtaskStatus.PENDING) {
                existing.setStatus(SubtaskStatus.CANCELLED);
                existing.setError("计划已更新，该待办任务被取消");
            }
            next.put(existing.getTaskId(), existing);
        }
        run.getSubtasks().clear();
        run.getSubtasks().putAll(next);
    }

    private void applyReviewStarted(RunState run, EventEnvelope event) {
        TaskRef ref = ensureTask(run, event.task());
        if (ref == null || isMain(run, ref)) {
            return;
        }
        TaskNodeState node = run.getSubtasks().get(ref.taskId());
        if (node != null && !node.isTerminal()) {
            node.setStatus(SubtaskStatus.REVIEWING);
        }
    }

    /**
     * 审核完成只处理 {@code REWORK}：把子任务置为 {@code RETRYING}。
     * {@code PASS} 不在此处收尾，由后续 {@code task_completed} 置为成功。
     */
    private void applyReviewCompleted(RunState run, EventEnvelope event) {
        TaskRef ref = ensureTask(run, event.task());
        if (ref == null || isMain(run, ref)) {
            return;
        }
        TaskNodeState node = run.getSubtasks().get(ref.taskId());
        if (node == null || node.isTerminal()) {
            return;
        }
        if (event.payload() instanceof EventPayload.ReviewCompleted review
                && "REWORK".equalsIgnoreCase(review.verdict())) {
            node.setStatus(SubtaskStatus.RETRYING);
        }
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

    /**
     * 子任务失败：{@code retryable=true} 置为 {@code RETRYING} 且不写完成时间，
     * 表示后续还会重试；只有不可重试失败才进入终态 {@code FAILED}。
     * 主任务失败没有重试语义，直接置为 {@code FAILED}。
     */
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

    /**
     * 确保事件引用的任务在投影中存在：根任务补齐主任务字段；子任务缺失时创建
     * 占位节点（使用大排序号）。根任务尚未由 plan 建立时返回 {@code null}，
     * 调用方应忽略该事件，避免凭空制造更深层级。
     */
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

    /**
     * 运行终止后的兜底修复：把仍未结束的子任务标记为失败，已有错误信息保留。
     */
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
