package com.tasknest.orchestrator.review;

import com.tasknest.event.EventPayload;

import java.util.List;

/** 审核 Agent 的结构化输入。变更文件由编排器用 Git diff 汇总，不依赖子 Agent 自述。 */
public record ReviewContext(
        String goal,
        String workingDir,
        EventPayload.PlannedTask task,
        String subtaskSummary,
        List<String> changedFiles,
        String diffSummary,
        String previousIssues,
        int attempt
) {

    public ReviewContext {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        previousIssues = previousIssues == null ? "" : previousIssues;
    }

    public String testCommand() {
        return task == null ? null : task.testCommand();
    }

    public boolean hasChangedFiles() {
        return !changedFiles.isEmpty();
    }
}
