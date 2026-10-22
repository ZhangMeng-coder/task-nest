package com.tasknest.orchestrator;

import com.tasknest.event.EventPayload;

import java.util.List;

/**
 * 启动一次运行所需的输入：确认后的两层计划、工作目录与 Git 分支信息。
 *
 * @param planTasks      两层计划，根任务 parentTaskId 为 null，其余直接挂在根任务下
 * @param planRevision   计划版本号，为 null 时按 1 处理
 * @param originalBranch 确认规划时所在分支，运行结束后合并回该分支
 * @param mainTaskBranch 本次运行创建的主任务分支名
 */
public record RunRequest(
        String mainTaskId,
        String title,
        String goal,
        String workingDir,
        List<EventPayload.PlannedTask> planTasks,
        Integer planRevision,
        String originalBranch,
        String mainTaskBranch
) {

    public boolean hasPlan() {
        return planTasks != null && !planTasks.isEmpty();
    }
}
