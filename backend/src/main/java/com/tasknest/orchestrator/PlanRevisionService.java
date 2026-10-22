package com.tasknest.orchestrator;

import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventPayload;
import com.tasknest.orchestrator.agent.PlanDraft;
import com.tasknest.store.SubtaskStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 执行期重排：只允许新增、修改或取消 PENDING 任务；已开始/已结束任务保持原定义，
 * 被移除的 PENDING 任务转为 CANCELLED。返回带递增 revision 与 changedTaskIds 的计划事件。
 */
@Service
public class PlanRevisionService {

    /**
     * 基于当前计划与子任务状态快照生成重排后的计划。
     *
     * <p>只有 PENDING（或尚未建立投影）的任务允许新增或修改；已开始/已结束的任务必须保持
     * 原定义，否则抛 {@code PLAN_REVISION_INVALID}。草稿中缺失的 PENDING 任务转为
     * CANCELLED（并入 changedTaskIds 通知前端），非 PENDING 的缺失任务则原样保留。
     * 合并后校验依赖存在且未指向已取消任务，并做拓扑排序判环；revision 在旧值上递增。</p>
     *
     * @param statuses 当前 taskId → 子任务状态快照
     * @param draft    主 Agent 提出的重排草案，仅包含直属子任务，根任务沿用当前计划
     * @param reason   重排原因，写入新的 plan 事件
     */
    public EventPayload.Plan revise(EventPayload.Plan current,
                                    Map<String, SubtaskStatus> statuses,
                                    PlanDraft draft,
                                    String reason) {
        if (current == null || current.tasks() == null || current.tasks().isEmpty()) {
            throw invalid("当前计划为空，不能重排");
        }
        Map<String, EventPayload.PlannedTask> currentById = new LinkedHashMap<>();
        EventPayload.PlannedTask currentRoot = null;
        for (EventPayload.PlannedTask task : current.tasks()) {
            if (task.parentTaskId() == null) {
                currentRoot = task;
            } else {
                currentById.put(task.taskId(), task);
            }
        }
        if (currentRoot == null) {
            throw invalid("当前计划缺少主任务");
        }
        Map<String, EventPayload.PlannedTask> draftById = new LinkedHashMap<>();
        for (EventPayload.PlannedTask task : draft.children()) {
            draftById.put(task.taskId(), task);
        }

        Set<String> changed = new LinkedHashSet<>();
        List<EventPayload.PlannedTask> merged = new ArrayList<>();
        for (EventPayload.PlannedTask planned : draft.children()) {
            SubtaskStatus status = statuses.get(planned.taskId());
            EventPayload.PlannedTask existing = currentById.get(planned.taskId());
            if (status != null && status != SubtaskStatus.PENDING) {
                if (existing == null) {
                    throw invalid("任务 ID 已被已结束或已取消的任务占用: " + planned.taskId());
                }
                if (!sameDefinition(existing, planned)) {
                    throw invalid("任务 " + planned.taskId() + " 已开始或已结束，不能修改");
                }
                merged.add(existing);
                continue;
            }
            if (existing == null) {
                changed.add(planned.taskId());
            } else if (!sameDefinition(existing, planned)) {
                changed.add(planned.taskId());
            }
            merged.add(planned);
        }

        Set<String> cancelled = new LinkedHashSet<>();
        for (EventPayload.PlannedTask existing : currentById.values()) {
            if (draftById.containsKey(existing.taskId())) {
                continue;
            }
            SubtaskStatus status = statuses.get(existing.taskId());
            if (status == null || status == SubtaskStatus.PENDING) {
                cancelled.add(existing.taskId());
                changed.add(existing.taskId());
            } else {
                merged.add(existing);
            }
        }

        Set<String> mergedIds = new LinkedHashSet<>();
        mergedIds.add(currentRoot.taskId());
        for (EventPayload.PlannedTask task : merged) {
            mergedIds.add(task.taskId());
        }
        for (EventPayload.PlannedTask task : merged) {
            for (String dependency : safe(task.dependsOn())) {
                if (cancelled.contains(dependency)) {
                    throw invalid("任务 " + task.taskId() + " 依赖了被取消的任务: " + dependency);
                }
                if (!mergedIds.contains(dependency)) {
                    throw invalid("任务 " + task.taskId() + " 依赖了不存在的任务: " + dependency);
                }
            }
        }
        ensureAcyclic(merged);

        List<EventPayload.PlannedTask> planTasks = new ArrayList<>();
        planTasks.add(currentRoot);
        planTasks.addAll(merged);
        int revision = (current.revision() == null ? 1 : current.revision()) + 1;
        return new EventPayload.Plan(
                List.copyOf(planTasks), revision, reason, List.copyOf(changed));
    }

    /** 比较任务的可变定义字段；taskId 与 parentTaskId 不参与比较。 */
    public boolean sameDefinition(EventPayload.PlannedTask left,
                                  EventPayload.PlannedTask right) {
        return Objects.equals(left.title(), right.title())
                && Objects.equals(left.agentName(), right.agentName())
                && Objects.equals(left.description(), right.description())
                && Objects.equals(safe(left.dependsOn()), safe(right.dependsOn()))
                && Objects.equals(safe(left.acceptanceCriteria()), safe(right.acceptanceCriteria()))
                && Objects.equals(safe(left.allowedTools()), safe(right.allowedTools()))
                && Objects.equals(left.testCommand(), right.testCommand());
    }

    /** 用 Kahn 拓扑排序校验依赖无环，存在环时抛 {@code PLAN_REVISION_INVALID}。 */
    private void ensureAcyclic(List<EventPayload.PlannedTask> tasks) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (EventPayload.PlannedTask task : tasks) {
            indegree.put(task.taskId(), 0);
            outgoing.put(task.taskId(), new ArrayList<>());
        }
        for (EventPayload.PlannedTask task : tasks) {
            for (String dependency : safe(task.dependsOn())) {
                if (!indegree.containsKey(dependency)) {
                    continue;
                }
                indegree.merge(task.taskId(), 1, Integer::sum);
                outgoing.get(dependency).add(task.taskId());
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, count) -> {
            if (count == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            visited++;
            for (String next : outgoing.getOrDefault(id, List.of())) {
                int count = indegree.merge(next, -1, Integer::sum);
                if (count == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited != tasks.size()) {
            throw invalid("重排后的任务依赖存在环");
        }
    }

    private List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PLAN_REVISION_INVALID, message);
    }
}
