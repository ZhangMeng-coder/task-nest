package com.tasknest.orchestrator.review;

import com.tasknest.event.EventPayload;

import java.util.List;

/**
 * 审核 Agent 的结构化输入。变更文件由编排器用 Git diff 汇总，不依赖子 Agent 自述。
 *
 * <p>由编排器在子任务提交后构造。{@code attempt} 表示第几次审核，{@code previousIssues}
 * 是本轮需要复查的上一轮问题。紧凑构造器把 null 列表 / 字符串归一化，
 * 避免提示词拼接处出现空指针。
 */
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

    /** 计划中为该子任务声明的测试命令；未声明时返回 null。 */
    public String testCommand() {
        return task == null ? null : task.testCommand();
    }

    /** 子任务分支相对基线是否存在文件变更。 */
    public boolean hasChangedFiles() {
        return !changedFiles.isEmpty();
    }
}
