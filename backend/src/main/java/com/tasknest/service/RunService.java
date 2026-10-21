package com.tasknest.service;

import com.tasknest.api.dto.AcceptedResponse;
import com.tasknest.api.dto.CreateMainTaskResponse;
import com.tasknest.api.dto.CreateRunResponse;
import com.tasknest.api.dto.PlanDraftInput;
import com.tasknest.api.dto.PlanDraftView;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.orchestrator.MockScriptLoader;
import com.tasknest.orchestrator.RunRequest;
import com.tasknest.orchestrator.TaskOrchestrator;
import com.tasknest.persistence.RunPersistenceService;
import com.tasknest.store.MainTaskStatus;
import com.tasknest.store.RunSnapshot;
import com.tasknest.store.RunState;
import com.tasknest.store.RunStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class RunService {

    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private final TaskOrchestrator orchestrator;
    private final RunStore runStore;
    private final MockScriptLoader scriptLoader;
    private final RunPersistenceService persistence;
    private final PlanDraftService planDraftService;

    public RunService(TaskOrchestrator orchestrator,
                      RunStore runStore,
                      MockScriptLoader scriptLoader,
                      RunPersistenceService persistence,
                      PlanDraftService planDraftService) {
        this.orchestrator = orchestrator;
        this.runStore = runStore;
        this.scriptLoader = scriptLoader;
        this.persistence = persistence;
        this.planDraftService = planDraftService;
    }

    public CreateMainTaskResponse createMainTask(String mockScript, String goal) {
        return createMainTask(mockScript, goal, null);
    }

    /**
     * 创建主任务：先确定计划表（确认稿或按脚本生成的默认稿），再启动 Mock 编排。
     */
    public CreateMainTaskResponse createMainTask(String mockScript, String goal, PlanDraftInput planInput) {
        if (!scriptLoader.isAllowed(mockScript)) {
            throw new BusinessException(ErrorCode.INVALID_SCRIPT,
                    "mockScript 必须是 " + String.join(" / ", scriptLoader.allowedScripts()));
        }
        PlanDraftView plan = planInput == null
                ? planDraftService.generate(mockScript, goal)
                : planDraftService.fromInput(mockScript, goal, planInput);
        String mainTaskId = generateId("mt_");
        String runId = generateRunId();
        String title = plan.title();
        String effectiveGoal = plan.goal();

        persistence.createPending(mainTaskId, runId, title, effectiveGoal, mockScript);
        PlanDraftView savedPlan = planDraftService.save(mainTaskId, plan);
        try {
            orchestrator.start(runId, new RunRequest(mockScript, mainTaskId, title, effectiveGoal));
        } catch (RuntimeException exception) {
            persistence.markStartFailed(mainTaskId, "编排器启动失败: " + exception.getMessage());
            throw exception;
        }
        RunState run = runStore.require(runId);
        return new CreateMainTaskResponse(
                mainTaskId, runId, mockScript, MainTaskStatus.PENDING, run.getCreatedAt(), savedPlan);
    }

    /**
     * 兼容旧的运行创建入口：每次运行仍会生成一个可查询的主任务。
     */
    public CreateRunResponse createRun(String mockScript) {
        CreateMainTaskResponse created = createMainTask(mockScript, null);
        RunState run = runStore.require(created.runId());
        return new CreateRunResponse(
                run.getRunId(), run.getMainTaskId(), run.getMockScript(), run.getStatus(), run.getCreatedAt());
    }

    public AcceptedResponse submitInput(String runId, String taskId, String selectedOptionId) {
        orchestrator.submitInput(runId, taskId, selectedOptionId);
        return new AcceptedResponse(runId, taskId, selectedOptionId);
    }

    public RunSnapshot snapshot(String runId) {
        return runStore.snapshot(runId);
    }

    private String generateRunId() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "run_" + ID_TIME.format(Instant.now()) + "_" + suffix();
            if (!runStore.contains(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL, "无法生成唯一 runId");
    }

    private String generateId(String prefix) {
        return prefix + ID_TIME.format(Instant.now()) + "_" + suffix();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }
}
