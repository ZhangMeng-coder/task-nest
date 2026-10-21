package com.tasknest.orchestrator.agent;

import com.tasknest.event.EventPayload;

import java.util.List;

/** 规划 Agent 产出的两层计划。 */
public record PlanDraft(
        String title,
        String goal,
        List<EventPayload.PlannedTask> tasks
) {
    public EventPayload.PlannedTask root() {
        return tasks.stream()
                .filter(task -> task.parentTaskId() == null)
                .findFirst()
                .orElseThrow();
    }

    public List<EventPayload.PlannedTask> children() {
        return tasks.stream()
                .filter(task -> task.parentTaskId() != null)
                .toList();
    }
}
