package com.tasknest.orchestrator;

import com.tasknest.event.EventPayload;

import java.util.List;

/**
 * 启动一次运行所需的输入。Mock 模式只需要 mockScript；P3 真实模式携带确认后的
 * 计划、工作目录与 Git 分支信息。
 */
public record RunRequest(
        String mockScript,
        String mainTaskId,
        String title,
        String goal,
        String workingDir,
        List<EventPayload.PlannedTask> planTasks,
        Integer planRevision,
        String originalBranch,
        String mainTaskBranch
) {

    public RunRequest(String mockScript, String mainTaskId, String title, String goal) {
        this(mockScript, mainTaskId, title, goal, null, null, null, null, null);
    }

    public boolean mockMode() {
        return mockScript != null && !mockScript.isBlank();
    }

    public boolean hasPlan() {
        return planTasks != null && !planTasks.isEmpty();
    }
}
