package com.tasknest.service;

import com.tasknest.api.dto.AcceptedResponse;
import com.tasknest.api.dto.ConfirmPlanSessionResponse;
import com.tasknest.api.dto.CreatePlanSessionRequest;
import com.tasknest.api.dto.CreatePlanSessionResponse;
import com.tasknest.api.dto.PlanDraftView;
import com.tasknest.api.dto.PlanRowView;
import com.tasknest.api.dto.PlanSessionView;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.orchestrator.GitService;
import com.tasknest.orchestrator.MockScriptLoader;
import com.tasknest.orchestrator.agent.AgentException;
import com.tasknest.orchestrator.agent.PlanDraft;
import com.tasknest.orchestrator.agent.PlanningAgent;
import com.tasknest.orchestrator.llm.LlmException;
import com.tasknest.orchestrator.tool.ToolRegistry;
import com.tasknest.sse.PlanSessionSseService;
import com.tasknest.store.UserInput;
import com.tasknest.store.WaitingInputState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 规划会话生命周期：创建、提问、修订、确认、取消。
 * 规划阶段持有工作区锁；确认前不创建主任务。
 */
@Service
public class PlanningSessionService {

    private static final Logger log = LoggerFactory.getLogger(PlanningSessionService.class);
    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private final PlanSessionStore store;
    private final PlanSessionSseService sseService;
    private final PlanningAgent planningAgent;
    private final GitService gitService;
    private final WorkspaceLockService lockService;
    private final RunService runService;
    private final MockScriptLoader scriptLoader;
    private final PlanDraftService planDraftService;
    private final ExecutorService orchestrationExecutor;
    private final Map<String, Path> workingRoots = new ConcurrentHashMap<>();

    public PlanningSessionService(PlanSessionStore store,
                                  PlanSessionSseService sseService,
                                  PlanningAgent planningAgent,
                                  GitService gitService,
                                  WorkspaceLockService lockService,
                                  RunService runService,
                                  MockScriptLoader scriptLoader,
                                  PlanDraftService planDraftService,
                                  @Qualifier("orchestrationExecutor")
                                  ExecutorService orchestrationExecutor) {
        this.store = store;
        this.sseService = sseService;
        this.planningAgent = planningAgent;
        this.gitService = gitService;
        this.lockService = lockService;
        this.runService = runService;
        this.scriptLoader = scriptLoader;
        this.planDraftService = planDraftService;
        this.orchestrationExecutor = orchestrationExecutor;
    }

    public CreatePlanSessionResponse create(CreatePlanSessionRequest request) {
        String goal = request.goal() == null ? "" : request.goal().trim();
        if (goal.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "目标不能为空");
        }
        String mockScript = request.mockScript() == null || request.mockScript().isBlank()
                ? null : request.mockScript().trim();
        if (mockScript != null && !scriptLoader.isAllowed(mockScript)) {
            throw new BusinessException(ErrorCode.INVALID_SCRIPT,
                    "mockScript 必须是 " + String.join(" / ", scriptLoader.allowedScripts()));
        }
        Path workingDir = Path.of(request.workingDir().trim());
        GitService.RepoInfo repo = gitService.validate(workingDir);
        String planSessionId = generateId();
        String owner = owner(planSessionId);
        lockService.acquire(repo.root(), owner);
        try {
            PlanSessionState state = store.create(
                    planSessionId, goal, repo.root().toString(), mockScript);
            workingRoots.put(planSessionId, repo.root());
            if (mockScript != null) {
                publishMockPlan(state);
            } else {
                submitPlanning(state, null);
            }
            return new CreatePlanSessionResponse(
                    planSessionId, state.getStatus().name(), state.getLastSeq());
        } catch (RuntimeException exception) {
            lockService.release(repo.root(), owner);
            workingRoots.remove(planSessionId);
            throw exception;
        }
    }

    public PlanSessionView view(String planSessionId) {
        PlanSessionState state = store.require(planSessionId);
        synchronized (state.getLock()) {
            return new PlanSessionView(
                    state.getPlanSessionId(),
                    state.getStatus().name(),
                    state.getGoal(),
                    state.getWorkingDir(),
                    state.getPlan(),
                    state.getLastSeq(),
                    state.getError());
        }
    }

    public AcceptedResponse submitInput(String planSessionId,
                                        String taskId,
                                        String selectedOptionId,
                                        String customInput) {
        PlanSessionState state = store.require(planSessionId);
        synchronized (state.getLock()) {
            if (state.isTerminal()) {
                throw new BusinessException(ErrorCode.RUN_ENDED);
            }
            if (state.getStatus() != PlanSessionStatus.WAITING_INPUT) {
                throw new BusinessException(ErrorCode.NOT_WAITING_INPUT);
            }
            WaitingInputState waiting = state.getWaitingInput();
            if (waiting == null || !waiting.taskId().equals(taskId)) {
                throw new BusinessException(ErrorCode.NOT_WAITING_INPUT);
            }
            UserInput input = InputValidation.validate(waiting, selectedOptionId, customInput);
            state.setWaitingInput(null);
            waiting.queue().offer(input);
        }
        return new AcceptedResponse(planSessionId, taskId, selectedOptionId, customInput);
    }

    public void revise(String planSessionId, String instruction) {
        PlanSessionState state = store.require(planSessionId);
        String note = instruction == null ? "" : instruction.trim();
        if (note.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "修订提示不能为空");
        }
        synchronized (state.getLock()) {
            if (state.getStatus() != PlanSessionStatus.READY) {
                throw new BusinessException(ErrorCode.PLAN_SESSION_BUSY,
                        "只有 READY 状态的规划会话可以修订");
            }
            state.setRevision(state.getRevision() + 1);
            state.addRevision("revision " + state.getRevision() + "：" + note);
            state.setStatus(PlanSessionStatus.RUNNING);
        }
        submitPlanning(state, note);
    }

    public ConfirmPlanSessionResponse confirm(String planSessionId) {
        PlanSessionState state = store.require(planSessionId);
        EventPayload.Plan plan;
        String workingDir;
        int revision;
        synchronized (state.getLock()) {
            if (state.getStatus() != PlanSessionStatus.READY || state.getPlan() == null) {
                throw new BusinessException(ErrorCode.PLAN_SESSION_NOT_READY,
                        "规划会话尚未就绪，不能确认");
            }
            plan = state.getPlan();
            workingDir = state.getWorkingDir();
            revision = state.getRevision();
        }
        GitService.RepoInfo repo = gitService.validate(Path.of(workingDir));
        PlanDraft draft = new PlanDraft(
                plan.tasks().stream()
                        .filter(task -> task.parentTaskId() == null)
                        .map(EventPayload.PlannedTask::title)
                        .findFirst()
                        .orElse("软件开发任务"),
                state.getGoal(),
                plan.tasks());
        Path root = workingRoots.getOrDefault(planSessionId, repo.root());
        String owner = owner(planSessionId);
        lockService.release(root, owner);
        try {
            ConfirmPlanSessionResponse response = runService.createFromPlan(
                    draft, revision, state.getMockScript(), workingDir, repo.branch());
            synchronized (state.getLock()) {
                state.setStatus(PlanSessionStatus.CONFIRMED);
                store.publish(state, EventType.RUN_COMPLETED,
                        PlanningAgent.PLAN_ROOT_TASK,
                        new EventPayload.RunCompleted("计划已确认，主任务开始执行"));
            }
            sseService.close(planSessionId);
            workingRoots.remove(planSessionId);
            return response;
        } catch (RuntimeException exception) {
            lockService.acquire(root, owner);
            throw exception;
        }
    }

    public void cancel(String planSessionId) {
        PlanSessionState state = store.require(planSessionId);
        synchronized (state.getLock()) {
            if (state.isTerminal()) {
                return;
            }
            state.setStatus(PlanSessionStatus.CANCELLED);
            store.publish(state, EventType.RUN_COMPLETED,
                    PlanningAgent.PLAN_ROOT_TASK,
                    new EventPayload.RunCompleted("规划会话已取消"));
        }
        releaseLock(planSessionId);
        sseService.close(planSessionId);
    }

    private void submitPlanning(PlanSessionState state, String instruction) {
        try {
            orchestrationExecutor.submit(() -> runPlanning(state, instruction));
        } catch (RejectedExecutionException exception) {
            fail(state, "PLAN_AGENT_FAILED", "编排线程池拒绝执行");
        }
    }

    private void runPlanning(PlanSessionState state, String instruction) {
        try {
            PlanDraft draft = planningAgent.generate(state, instruction);
            if (draft == null) {
                return;
            }
            EventPayload.Plan plan = new EventPayload.Plan(
                    draft.tasks(), state.getRevision(), instruction, null);
            store.publish(state, EventType.PLAN,
                    PlanningAgent.PLAN_ROOT_TASK, plan);
            log.info("规划会话 {} 就绪，revision={}", state.getPlanSessionId(),
                    state.getRevision());
        } catch (AgentException exception) {
            fail(state, "PLAN_AGENT_FAILED", exception.getMessage());
        } catch (LlmException exception) {
            fail(state, "PLAN_AGENT_UNAVAILABLE", exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("规划会话失败 session={}", state.getPlanSessionId(), exception);
            fail(state, "PLAN_AGENT_FAILED", "规划执行异常: " + exception.getMessage());
        }
    }

    private void fail(PlanSessionState state, String code, String message) {
        synchronized (state.getLock()) {
            if (state.isTerminal()) {
                return;
            }
            state.setStatus(PlanSessionStatus.FAILED);
            state.setError(message);
            store.publish(state, EventType.ERROR, PlanningAgent.PLAN_ROOT_TASK,
                    new EventPayload.Error(code, message));
        }
        releaseLock(state.getPlanSessionId());
        sseService.close(state.getPlanSessionId());
    }

    private void publishMockPlan(PlanSessionState state) {
        PlanDraftView draftView = planDraftService.generate(state.getMockScript(), state.getGoal());
        List<EventPayload.PlannedTask> tasks = new ArrayList<>();
        tasks.add(new EventPayload.PlannedTask(
                PlanningAgent.PLAN_ROOT_ID, null, draftView.title(),
                "coordinator", List.of(), draftView.goal(),
                List.of("按计划完成 Mock 演练"), null, null));
        int order = 0;
        for (PlanRowView row : draftView.rows()) {
            order++;
            String taskId = row.taskId() == null || row.taskId().isBlank()
                    ? "t_" + order : row.taskId();
            String output = row.output() == null || row.output().isBlank()
                    ? "可复核结果" : row.output();
            tasks.add(new EventPayload.PlannedTask(
                    taskId,
                    PlanningAgent.PLAN_ROOT_ID,
                    row.title(),
                    row.agent(),
                    List.of(),
                    row.description() == null || row.description().isBlank()
                            ? row.title() : row.description(),
                    List.of(output),
                    row.allowedTools() == null || row.allowedTools().isEmpty()
                            ? List.copyOf(ToolRegistry.EXECUTION_TOOLS)
                            : row.allowedTools(),
                    row.testCommand()));
        }
        store.publish(state, EventType.PLAN, PlanningAgent.PLAN_ROOT_TASK,
                new EventPayload.Plan(tasks, 1, "Mock 计划", null));
    }

    private void releaseLock(String planSessionId) {
        Path root = workingRoots.remove(planSessionId);
        if (root != null) {
            lockService.release(root, owner(planSessionId));
        }
    }

    private String owner(String planSessionId) {
        return "plan:" + planSessionId;
    }

    private String generateId() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "ps_" + ID_TIME.format(Instant.now()) + "_" + suffix();
            if (!store.contains(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL, "无法生成唯一规划会话 ID");
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }
}
