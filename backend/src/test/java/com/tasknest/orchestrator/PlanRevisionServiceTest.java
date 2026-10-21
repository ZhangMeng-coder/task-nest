package com.tasknest.orchestrator;

import com.tasknest.common.BusinessException;
import com.tasknest.event.EventPayload;
import com.tasknest.orchestrator.agent.PlanDraft;
import com.tasknest.store.SubtaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanRevisionServiceTest {

    private final PlanRevisionService service = new PlanRevisionService();

    private final EventPayload.PlannedTask root = task("t_root", null, "主任务", List.of());
    private final EventPayload.PlannedTask pending = task("t1", "t_root", "实现接口", List.of());
    private final EventPayload.PlannedTask done = task("t2", "t_root", "写测试", List.of("t1"));

    @Test
    void addsNewTasksAndKeepsStartedTasks() {
        EventPayload.Plan current = plan(3, root, pending, done);
        EventPayload.PlannedTask changedPending = task(
                "t1", "t_root", "实现接口并补文档", List.of());
        EventPayload.PlannedTask added = task(
                "t3", "t_root", "补充回归测试", List.of("t1"));

        EventPayload.Plan revised = service.revise(
                current,
                Map.of("t1", SubtaskStatus.PENDING, "t2", SubtaskStatus.SUCCESS),
                draft(root, changedPending, added, done),
                "调整剩余工作");

        assertThat(revised.revision()).isEqualTo(4);
        assertThat(revised.reason()).isEqualTo("调整剩余工作");
        assertThat(revised.changedTaskIds()).containsExactly("t1", "t3");
        assertThat(revised.tasks()).extracting(EventPayload.PlannedTask::taskId)
                .containsExactlyInAnyOrder("t_root", "t1", "t2", "t3");
        // 已完成的 t2 即使不在新草案里也保留原定义。
        assertThat(revised.tasks()).filteredOn(task -> task.taskId().equals("t2"))
                .extracting(EventPayload.PlannedTask::title)
                .containsExactly("写测试");
    }

    @Test
    void cancelsRemovedPendingTasks() {
        EventPayload.PlannedTask doneWithoutDependency = task(
                "t2", "t_root", "写测试", List.of());
        EventPayload.Plan current = plan(1, root, pending, doneWithoutDependency);
        EventPayload.PlannedTask added = task("t3", "t_root", "只保留新任务", List.of());

        EventPayload.Plan revised = service.revise(
                current,
                Map.of("t1", SubtaskStatus.PENDING, "t2", SubtaskStatus.RUNNING),
                draft(root, added, doneWithoutDependency),
                "范围收缩");

        assertThat(revised.changedTaskIds()).contains("t1", "t3");
        assertThat(revised.tasks()).extracting(EventPayload.PlannedTask::taskId)
                .containsExactlyInAnyOrder("t_root", "t2", "t3");
    }

    @Test
    void rejectsChangesToStartedTasks() {
        EventPayload.Plan current = plan(1, root, pending, done);
        EventPayload.PlannedTask renamedDone = task(
                "t2", "t_root", "写测试（改名）", List.of("t1"));

        assertThatThrownBy(() -> service.revise(
                current,
                Map.of("t1", SubtaskStatus.PENDING, "t2", SubtaskStatus.SUCCESS),
                draft(root, pending, renamedDone),
                "改已完成任务"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能修改");
    }

    @Test
    void rejectsDependencyOnCancelledTask() {
        EventPayload.PlannedTask doneWithoutDependency = task(
                "t2", "t_root", "写测试", List.of());
        EventPayload.Plan current = plan(1, root, pending, doneWithoutDependency);
        EventPayload.PlannedTask dependsOnRemoved = task(
                "t3", "t_root", "依赖被取消任务", List.of("t1"));

        assertThatThrownBy(() -> service.revise(
                current,
                Map.of("t1", SubtaskStatus.PENDING, "t2", SubtaskStatus.SUCCESS),
                draft(root, doneWithoutDependency, dependsOnRemoved),
                "删除依赖"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("被取消");
    }

    @Test
    void rejectsCyclicDependencies() {
        EventPayload.PlannedTask doneWithoutDependency = task(
                "t2", "t_root", "写测试", List.of());
        EventPayload.Plan current = plan(1, root, pending, doneWithoutDependency);
        EventPayload.PlannedTask first = task("t3", "t_root", "A", List.of("t4"));
        EventPayload.PlannedTask second = task("t4", "t_root", "B", List.of("t3"));

        assertThatThrownBy(() -> service.revise(
                current,
                Map.of("t1", SubtaskStatus.PENDING, "t2", SubtaskStatus.SUCCESS),
                draft(root, first, second),
                "制造环"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("环");
    }

    private EventPayload.Plan plan(int revision, EventPayload.PlannedTask... tasks) {
        return new EventPayload.Plan(List.of(tasks), revision, null, null);
    }

    private PlanDraft draft(EventPayload.PlannedTask... tasks) {
        return new PlanDraft("主任务", "目标", List.of(tasks));
    }

    private EventPayload.PlannedTask task(String id,
                                          String parentId,
                                          String title,
                                          List<String> dependsOn) {
        return new EventPayload.PlannedTask(
                id, parentId, title, "developer", dependsOn,
                title + " 的说明", List.of("验收 " + title), null, null);
    }
}
