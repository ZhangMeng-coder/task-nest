package com.tasknest.service;

import com.tasknest.api.dto.AcceptedResponse;
import com.tasknest.api.dto.ConfirmPlanSessionResponse;
import com.tasknest.api.dto.PlanDraftView;
import com.tasknest.api.dto.PlanRowView;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventPayload;
import com.tasknest.orchestrator.RunRequest;
import com.tasknest.orchestrator.TaskOrchestrator;
import com.tasknest.orchestrator.agent.PlanDraft;
import com.tasknest.persistence.RunPersistenceService;
import com.tasknest.store.RunSnapshot;
import com.tasknest.store.RunStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 运行创建用例的入口：生成 {@code mainTaskId} / {@code runId}，先落主任务与计划档案，
 * 再交给 {@link TaskOrchestrator} 启动。
 *
 * <p>唯一入口是 {@link PlanningSessionService#confirm(String)} 调用的
 * {@link #createFromPlan}；计划来自真实规划 Agent 的确认稿。本类不直接操作 Git，
 * Git 前置校验与仓库锁释放由调用方负责。</p>
 */
@Service
public class RunService {

    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private final TaskOrchestrator orchestrator;
    private final RunStore runStore;
    private final RunPersistenceService persistence;
    private final PlanDraftService planDraftService;

    public RunService(TaskOrchestrator orchestrator,
                      RunStore runStore,
                      RunPersistenceService persistence,
                      PlanDraftService planDraftService) {
        this.orchestrator = orchestrator;
        this.runStore = runStore;
        this.persistence = persistence;
        this.planDraftService = planDraftService;
    }

    /**
     * 由已确认的规划会话创建主任务并启动执行。调用方负责 Git 前置校验与会话锁释放。
     */
    public ConfirmPlanSessionResponse createFromPlan(PlanDraft draft,
                                                     int revision,
                                                     String workingDir,
                                                     String originalBranch) {
        String mainTaskId = generateId("mt_");
        String runId = generateRunId();
        // 分支只在此处命名；真正的创建、提交与合并由 CodingTaskOrchestrator 执行。
        String mainTaskBranch = "tasknest/" + mainTaskId;
        List<PlanRowView> rows = rowsOf(draft);
        PlanDraftView plan = planDraftService.fromConfirmed(
                draft.goal(), draft.title(), rows, List.of(), revision);

        persistence.createPending(mainTaskId, runId, plan.title(), draft.goal(), workingDir);
        PlanDraftView savedPlan;
        try {
            savedPlan = planDraftService.save(mainTaskId, plan);
        } catch (RuntimeException exception) {
            persistence.markStartFailed(mainTaskId, "保存计划失败: " + exception.getMessage());
            throw exception;
        }
        try {
            orchestrator.start(runId, new RunRequest(
                    mainTaskId,
                    plan.title(),
                    draft.goal(),
                    workingDir,
                    draft.tasks(),
                    revision,
                    originalBranch,
                    mainTaskBranch));
        } catch (RuntimeException exception) {
            persistence.markStartFailed(mainTaskId, "编排器启动失败: " + exception.getMessage());
            throw exception;
        }
        return new ConfirmPlanSessionResponse(
                mainTaskId, runId, workingDir, savedPlan.revision());
    }

    /**
     * 把规划器产出的子任务转换为计划表行。依赖为空时展示为破折号，
     * 预期产出取验收标准的第一条，缺省时回退为“可复核结果”。
     */
    private List<PlanRowView> rowsOf(PlanDraft draft) {
        List<PlanRowView> rows = new ArrayList<>();
        for (EventPayload.PlannedTask task : draft.children()) {
            String dependency = task.dependsOn() == null || task.dependsOn().isEmpty()
                    ? "—"
                    : String.join("、", task.dependsOn());
            String output = task.acceptanceCriteria() == null
                    || task.acceptanceCriteria().isEmpty()
                    ? "可复核结果"
                    : task.acceptanceCriteria().get(0);
            rows.add(new PlanRowView(
                    task.title(),
                    task.agentName(),
                    dependency,
                    output,
                    task.taskId(),
                    task.description(),
                    task.acceptanceCriteria(),
                    task.allowedTools(),
                    task.testCommand()));
        }
        return rows;
    }

    /**
     * 把用户抉择转交给编排器；校验与唤醒等待队列由编排器完成。
     */
    public AcceptedResponse submitInput(String runId,
                                         String taskId,
                                         String selectedOptionId,
                                         String customInput) {
        orchestrator.submitInput(runId, taskId, selectedOptionId, customInput);
        return new AcceptedResponse(runId, taskId, selectedOptionId, customInput);
    }

    /**
     * 读取内存中的运行快照；不查档案表，因此进程重启后不保证可读。
     */
    public RunSnapshot snapshot(String runId) {
        return runStore.snapshot(runId);
    }

    private String generateRunId() {
        // runId 还要在内存 RunStore 中唯一；时间戳相同则换随机后缀重试。
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "run_" + ID_TIME.format(Instant.now()) + "_" + suffix();
            if (!runStore.contains(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL, "无法生成唯一 runId");
    }

    /**
     * 生成带 UTC 时间戳与 6 位随机后缀的业务 ID；调用方负责保证语义上的唯一性。
     */
    private String generateId(String prefix) {
        return prefix + ID_TIME.format(Instant.now()) + "_" + suffix();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }
}
