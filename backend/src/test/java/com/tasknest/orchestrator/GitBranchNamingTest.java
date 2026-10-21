package com.tasknest.orchestrator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitBranchNamingTest {

    @Test
    void subtaskBranchDoesNotNestUnderMainTaskBranch() {
        String mainTaskId = "mt_20261021_141831_1359a9";
        String mainBranch = "tasknest/" + mainTaskId;
        String subtaskBranch = CodingTaskOrchestrator.subtaskBranchName(
                mainTaskId, "t_1", 1);

        assertThat(subtaskBranch)
                .isEqualTo("tasknest/subtasks/" + mainTaskId + "/t_1-a1");
        // Git ref 不能同时是文件与目录：子任务分支不得以主任务分支为前缀。
        assertThat(subtaskBranch).doesNotStartWith(mainBranch + "/");
        assertThat(subtaskBranch).isNotEqualTo(mainBranch);
        // GitService.discardWorkingTree 只允许在 tasknest/ 临时分支上清理工作区。
        assertThat(subtaskBranch).startsWith("tasknest/");
    }
}
