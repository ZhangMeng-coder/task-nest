package com.tasknest.service;

import com.tasknest.api.dto.AcceptedResponse;
import com.tasknest.api.dto.ConfirmPlanSessionResponse;
import com.tasknest.api.dto.CreatePlanSessionRequest;
import com.tasknest.api.dto.CreatePlanSessionResponse;
import com.tasknest.api.dto.PlanSessionView;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.orchestrator.GitService;
import com.tasknest.orchestrator.agent.AgentException;
import com.tasknest.orchestrator.agent.PlanDraft;
import com.tasknest.orchestrator.agent.PlanningAgent;
import com.tasknest.orchestrator.llm.LlmException;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 规划会话的生命周期编排：创建、提问 / 提交抉择、修订、确认、取消。
 *
 * <p>该服务位于 REST 层与规划 Agent / 运行创建之间，协调 {@link PlanSessionStore}
 * （内存状态与事件持久化）、{@link PlanSessionSseService}（SSE 投递）、
 * {@link PlanningAgent}（计划生成）、{@link GitService}（仓库前置校验）与
 * {@link WorkspaceLockService}（仓库级互斥锁）。</p>
 *
 * <p>关键约束：规划期间以 {@code plan:<planSessionId>} 作为持有者占用仓库锁，
 * 确认前不创建主任务；只有 {@code READY} 状态且已有计划的会话才能确认。
 * 确认成功后先释放锁并创建主任务，再把会话置为 {@code CONFIRMED}；
 * 创建失败则重新获取锁，保持会话可重试。取消 / 失败会释放锁并关闭规划 SSE。</p>
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
    private final ExecutorService orchestrationExecutor;
    private final Map<String, Path> workingRoots = new ConcurrentHashMap<>();

    public PlanningSessionService(PlanSessionStore store,
                                  PlanSessionSseService sseService,
                                  PlanningAgent planningAgent,
                                  GitService gitService,
                                  WorkspaceLockService lockService,
                                  RunService runService,
                                  @Qualifier("orchestrationExecutor")
                                  ExecutorService orchestrationExecutor) {
        this.store = store;
        this.sseService = sseService;
        this.planningAgent = planningAgent;
        this.gitService = gitService;
        this.lockService = lockService;
        this.runService = runService;
        this.orchestrationExecutor = orchestrationExecutor;
    }

    /**
     * 创建规划会话并占用仓库锁，随后异步提交规划 Agent。
     *
     * <p>初始化阶段发生任何异常都会释放已获取的锁并清理工作目录映射，
     * 避免仓库被永久占用。返回时会话通常仍处于 {@code RUNNING}，
     * 调用方随后通过 SSE 观察规划进展。</p>
     *
     * @throws BusinessException 目标为空、目录不是 Git 仓库或仓库已被占用时抛出
     */
    public CreatePlanSessionResponse create(CreatePlanSessionRequest request) {
        String goal = request.goal() == null ? "" : request.goal().trim();
        if (goal.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "目标不能为空");
        }
        Path workingDir = Path.of(request.workingDir().trim());
        GitService.RepoInfo repo = gitService.validate(workingDir);
        String planSessionId = generateId();
        String owner = owner(planSessionId);
        lockService.acquire(repo.root(), owner);
        try {
            PlanSessionState state = store.create(
                    planSessionId, goal, repo.root().toString());
            workingRoots.put(planSessionId, repo.root());
            submitPlanning(state, null);
            return new CreatePlanSessionResponse(
                    planSessionId, state.getStatus().name(), state.getLastSeq());
        } catch (RuntimeException exception) {
            lockService.release(repo.root(), owner);
            workingRoots.remove(planSessionId);
            throw exception;
        }
    }

    /**
     * 读取会话的只读视图。在会话锁内复制字段，避免与规划线程的写入交错。
     */
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

    /**
     * 向等待中的规划问题提交抉择。仅在 {@code WAITING_INPUT} 且 {@code taskId} 与当前
     * 问题匹配时接受；校验通过后清空等待态并唤醒规划线程的阻塞队列。
     *
     * @throws BusinessException 会话已结束、未在等待或抉择内容非法时抛出
     */
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

    /**
     * 对 {@code READY} 状态的计划发起一次提示词修订：递增版本号、记录修订说明，
     * 再把会话切回 {@code RUNNING} 异步重跑规划 Agent。非 {@code READY} 状态拒绝修订。
     */
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

    /**
     * 确认计划并创建主任务。确认前重新校验 Git 仓库，把当前分支作为原始分支交给
     * {@link RunService#createFromPlan}；成功后发布终止事件、关闭规划 SSE 并释放锁。
     *
     * <p>创建失败时会重新获取仓库锁并把异常抛给调用方，会话保持原状态以便重试；
     * 因此本方法对同一会话不能并发确认。</p>
     *
     * @throws BusinessException 会话未就绪、缺少计划或 Git 校验失败时抛出
     */
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
                    draft, revision, workingDir, repo.branch());
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

    /**
     * 取消规划会话：幂等处理终止态，发布 {@code RUN_COMPLETED} 后释放仓库锁并关闭 SSE。
     */
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

    /**
     * 把规划任务提交到共享编排线程池。线程池拒绝时立即将会话置为失败，
     * 避免调用方一直等待不会有结果的规划。
     */
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

    /**
     * 统一的失败收尾：仅在非终止态写入 {@code FAILED} 与错误信息，发布 {@code ERROR}
     * 事件后释放仓库锁并关闭 SSE。重复调用会被终止态检查短路。
     */
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

    /**
     * 按会话记录的工作目录根释放锁。只有确实持有过根路径时才执行释放，
     * 避免确认成功后再次调用误释放其他会话的锁。
     */
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
