package com.tasknest.orchestrator.agent;

import com.tasknest.event.EventPayload;

import java.util.List;

/**
 * 规划 Agent 产出的两层计划。
 *
 * <p>由 {@link PlanParser} 校验后构造，或由 {@link MainAgent} 在重排窗口解析得到。
 * {@code tasks} 恰好包含一个根任务（parentTaskId 为 null）和至少一个直属子任务；
 * 该不变量由解析器保证，{@link #root()} 与 {@link #children()} 不再重复校验。
 */
public record PlanDraft(
        String title,
        String goal,
        List<EventPayload.PlannedTask> tasks
) {
    /** 返回唯一根任务；计划结构非法（无根）时抛 {@link java.util.NoSuchElementException}。 */
    public EventPayload.PlannedTask root() {
        return tasks.stream()
                .filter(task -> task.parentTaskId() == null)
                .findFirst()
                .orElseThrow();
    }

    /** 按计划顺序返回直属子任务，不包含根任务。 */
    public List<EventPayload.PlannedTask> children() {
        return tasks.stream()
                .filter(task -> task.parentTaskId() != null)
                .toList();
    }
}
