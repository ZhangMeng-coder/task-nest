package com.tasknest.service;

import com.tasknest.api.dto.AcceptedResponse;
import com.tasknest.api.dto.ConfirmPlanSessionResponse;
import com.tasknest.api.dto.CreateMainTaskResponse;
import com.tasknest.api.dto.CreateRunResponse;
import com.tasknest.api.dto.PlanDraftInput;
import com.tasknest.api.dto.PlanDraftView;
import com.tasknest.api.dto.PlanRowView;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventPayload;
import com.tasknest.orchestrator.MockScriptLoader;
import com.tasknest.orchestrator.RunRequest;
import com.tasknest.orchestrator.TaskOrchestrator;
import com.tasknest.orchestrator.agent.PlanDraft;
import com.tasknest.persistence.RunPersistenceService;
import com.tasknest.store.MainTaskStatus;
import com.tasknest.store.RunSnapshot;
import com.tasknest.store.RunState;
import com.tasknest.store.RunStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
        return createMainTask(mockScript, goal, (String) null, null);
    }

    public CreateMainTaskResponse createMainTask(String mockScript, String goal, PlanDraftInput planInput) {
        return createMainTask(mockScript, goal, null, planInput);
    }

    /**
     * 创建主任务：先确定计划表（确认稿或按脚本生成的默认稿），再启动 Mock 编排。
     * 工作目录只作为主任务档案属性保存，Mock 阶段不会访问该目录。
     */
    public CreateMainTaskResponse createMainTask(String mockScript,
                                                 String goal,
                                                 String workingDir,
                                                 PlanDraftInput planInput) {
        if (!scriptLoader.isAllowed(mockScript)) {
            throw new BusinessException(ErrorCode.INVALID_SCRIPT,
                    "mockScript 必须是 " + String.join(" / ", scriptLoader.allowedScripts()));
        }
        String normalizedWorkingDir = normalizeWorkingDir(workingDir);
        PlanDraftView plan = planInput == null
                ? planDraftService.generate(mockScript, goal)
                : planDraftService.fromInput(mockScript, goal, planInput);
        String mainTaskId = generateId("mt_");
        String runId = generateRunId();
        String title = plan.title();
        String effectiveGoal = plan.goal();

        persistence.createPending(
                mainTaskId, runId, title, effectiveGoal, mockScript, normalizedWorkingDir);
        PlanDraftView savedPlan = planDraftService.save(mainTaskId, plan);
        try {
            orchestrator.start(runId, new RunRequest(
                    mockScript, mainTaskId, title, effectiveGoal, normalizedWorkingDir,
                    null, null, null, null));
        } catch (RuntimeException exception) {
            persistence.markStartFailed(mainTaskId, "编排器启动失败: " + exception.getMessage());
            throw exception;
        }
        RunState run = runStore.require(runId);
        return new CreateMainTaskResponse(
                mainTaskId, runId, mockScript, normalizedWorkingDir,
                MainTaskStatus.PENDING, run.getCreatedAt(), savedPlan);
    }

    /**
     * 由已确认的规划会话创建主任务并启动执行。调用方负责 Git 前置校验与会话锁释放。
     */
    public ConfirmPlanSessionResponse createFromPlan(PlanDraft draft,
                                                     int revision,
                                                     String mockScript,
                                                     String workingDir,
                                                     String originalBranch) {
        String mainTaskId = generateId("mt_");
        String runId = generateRunId();
        String mainTaskBranch = "tasknest/" + mainTaskId;
        List<PlanRowView> rows = rowsOf(draft);
        PlanDraftView plan = planDraftService.fromConfirmed(
                mockScript, draft.goal(), draft.title(), rows, List.of(), revision);

        persistence.createPending(mainTaskId, runId, plan.title(), draft.goal(),
                mockScript, workingDir);
        PlanDraftView savedPlan;
        try {
            savedPlan = planDraftService.save(mainTaskId, plan);
        } catch (RuntimeException exception) {
            persistence.markStartFailed(mainTaskId, "保存计划失败: " + exception.getMessage());
            throw exception;
        }
        try {
            orchestrator.start(runId, new RunRequest(
                    mockScript,
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
     * 兼容旧的运行创建入口：每次运行仍会生成一个可查询的主任务。
     */
    public CreateRunResponse createRun(String mockScript) {
        CreateMainTaskResponse created = createMainTask(mockScript, null);
        RunState run = runStore.require(created.runId());
        return new CreateRunResponse(
                run.getRunId(), run.getMainTaskId(), run.getMockScript(), run.getStatus(), run.getCreatedAt());
    }

    public AcceptedResponse submitInput(String runId,
                                         String taskId,
                                         String selectedOptionId,
                                         String customInput) {
        orchestrator.submitInput(runId, taskId, selectedOptionId, customInput);
        return new AcceptedResponse(runId, taskId, selectedOptionId, customInput);
    }

    public RunSnapshot snapshot(String runId) {
        return runStore.snapshot(runId);
    }

    private String normalizeWorkingDir(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) {
            return null;
        }
        String trimmed = workingDir.trim();
        if (trimmed.length() > 1024) {
            throw new BusinessException(ErrorCode.INVALID_WORKING_DIR, "工作目录路径最多 1024 字");
        }
        try {
            Path path = Path.of(trimmed).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new BusinessException(ErrorCode.INVALID_WORKING_DIR,
                        "工作目录不存在或不是文件夹: " + trimmed);
            }
            return path.toRealPath().toString();
        } catch (InvalidPathException exception) {
            throw new BusinessException(ErrorCode.INVALID_WORKING_DIR,
                    "工作目录路径格式非法: " + trimmed);
        } catch (IOException | SecurityException exception) {
            throw new BusinessException(ErrorCode.INVALID_WORKING_DIR,
                    "工作目录不可访问: " + trimmed);
        }
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
