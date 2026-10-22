package com.tasknest.orchestrator;

import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventPublisher;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.orchestrator.agent.AgentException;
import com.tasknest.orchestrator.agent.AgentOutcome;
import com.tasknest.orchestrator.agent.MainAgent;
import com.tasknest.orchestrator.agent.ReplanDecision;
import com.tasknest.orchestrator.agent.RunAgentSession;
import com.tasknest.orchestrator.agent.SubAgent;
import com.tasknest.orchestrator.llm.LlmException;
import com.tasknest.orchestrator.review.ReviewAgent;
import com.tasknest.orchestrator.review.ReviewContext;
import com.tasknest.orchestrator.review.ReviewResult;
import com.tasknest.service.AgentSettingsService;
import com.tasknest.service.InputValidation;
import com.tasknest.service.PlanDraftService;
import com.tasknest.service.WorkspaceLockService;
import com.tasknest.store.RunState;
import com.tasknest.store.RunStore;
import com.tasknest.store.SubtaskStatus;
import com.tasknest.store.TaskNodeState;
import com.tasknest.store.UserInput;
import com.tasknest.store.WaitingInputState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 真实编码编排：主任务分支 + 串行子任务分支、独立审核、动态重排与最终合并。
 *
 * <p>本类是 {@link TaskOrchestrator} 的唯一实现：{@link #start} 要求确认后的计划、
 * 工作目录与原始分支。</p>
 *
 * <p>{@link #execute} 的阶段顺序：</p>
 * <ol>
 *   <li>发布 run_started 与首个 plan 事件，按计划根任务构造事件引用；</li>
 *   <li>从原始分支创建并检出主任务分支，记录 branch_created 检查点；</li>
 *   <li>循环调用 {@link #nextRunnable}，按 dependsOn 只有依赖全部 SUCCESS 的子任务才可执行；</li>
 *   <li>每个子任务交给 {@link #runSubtask}（建分支 → 执行 → 提交 → 可选审核 → 合并 → 删分支）；</li>
 *   <li>仍有 PENDING 任务时调用 {@link #maybeReplan}，由主 Agent 决定是否发布新 revision；</li>
 *   <li>全部完成后汇总，把主任务分支 {@code --no-ff} 合并回原始分支并删除主任务分支；</li>
 *   <li>异常按类型分流：普通失败 {@link #rollbackAndFail} 回滚主分支，
 *       合并冲突 {@link #keepBranchesAfterConflict} 保留双方分支并以 GIT_MERGE_CONFLICT 终止。</li>
 * </ol>
 *
 * <p>每次尝试都从主任务分支切出同级临时分支
 * {@code tasknest/subtasks/<mainTaskId>/<taskId>-a<n>}，成功合并后删除，失败或审核退回时
 * 丢弃后重试。审核开启时尝试上限为 {@code 1 + maxReworks}，关闭时固定为 2（保留一次临时
 * 错误重试）。整个运行持有仓库工作区锁；60 分钟截止时间只在每次子任务循环开头检查，
 * 单次子任务 / 汇总 / 最终合并可能让实际耗时超过该值。</p>
 */
@Component
public class CodingTaskOrchestrator implements TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CodingTaskOrchestrator.class);
    private static final long RUN_TIMEOUT_MINUTES = 60;

    private final RunStore runStore;
    private final EventPublisher eventPublisher;
    private final GitService gitService;
    private final WorkspaceLockService lockService;
    private final AgentSettingsService agentSettings;
    private final SubAgent subAgent;
    private final ReviewAgent reviewAgent;
    private final MainAgent mainAgent;
    private final PlanRevisionService planRevisionService;
    private final PlanDraftService planDraftService;
    private final ExecutorService orchestrationExecutor;

    public CodingTaskOrchestrator(RunStore runStore,
                                  EventPublisher eventPublisher,
                                  GitService gitService,
                                  WorkspaceLockService lockService,
                                  AgentSettingsService agentSettings,
                                  SubAgent subAgent,
                                  ReviewAgent reviewAgent,
                                  MainAgent mainAgent,
                                  PlanRevisionService planRevisionService,
                                  PlanDraftService planDraftService,
                                  @Qualifier("orchestrationExecutor")
                                  ExecutorService orchestrationExecutor) {
        this.runStore = runStore;
        this.eventPublisher = eventPublisher;
        this.gitService = gitService;
        this.lockService = lockService;
        this.agentSettings = agentSettings;
        this.subAgent = subAgent;
        this.reviewAgent = reviewAgent;
        this.mainAgent = mainAgent;
        this.planRevisionService = planRevisionService;
        this.planDraftService = planDraftService;
        this.orchestrationExecutor = orchestrationExecutor;
    }

    /**
     * 校验并异步启动运行：要求计划、工作目录与原分支，并重新校验当前分支未漂移
     * （{@code GIT_BRANCH_DRIFT}）。成功时获取仓库工作区锁并把 {@link #execute}
     * 提交到编排线程池；运行创建失败或线程池拒绝时释放锁，避免工作区被永久占用。
     */
    @Override
    public String start(String runId, RunRequest request) {
        if (!request.hasPlan() || request.workingDir() == null
                || request.originalBranch() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "执行需要确认后的计划、工作目录与原分支");
        }
        GitService.RepoInfo repo = gitService.validate(Path.of(request.workingDir()));
        if (!repo.branch().equals(request.originalBranch())) {
            throw new BusinessException(ErrorCode.GIT_BRANCH_DRIFT,
                    "开始执行时原分支已变化，期望 " + request.originalBranch()
                            + "，当前 " + repo.branch());
        }
        String owner = owner(runId);
        lockService.acquire(repo.root(), owner);
        RunState run;
        try {
            run = runStore.create(runId, request.mainTaskId(), request.title(), request.goal());
        } catch (RuntimeException exception) {
            lockService.release(repo.root(), owner);
            throw exception;
        }
        try {
            orchestrationExecutor.submit(() -> execute(run, request, repo, owner));
        } catch (RejectedExecutionException exception) {
            eventPublisher.publish(run, EventType.ERROR, null,
                    new EventPayload.Error("ORCHESTRATOR_REJECTED", "编排线程池拒绝执行"));
            lockService.release(repo.root(), owner);
            throw new BusinessException(ErrorCode.INTERNAL, "无法启动编排线程");
        }
        return runId;
    }

    /**
     * 在运行锁内校验任务归属、运行状态与当前等待项，把用户抉择放入该等待项的队列，
     * 从而唤醒阻塞中的编排线程。非等待状态抛 {@code NOT_WAITING_INPUT}，运行已结束抛
     * {@code RUN_ENDED}。
     */
    @Override
    public void submitInput(String runId, String taskId, String optionId, String customInput) {
        RunState run = runStore.require(runId);
        synchronized (run.getLock()) {
            boolean mainTask = taskId.equals(run.getMainTask().getRootTaskId());
            if (!mainTask && !run.getSubtasks().containsKey(taskId)) {
                throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
            }
            if (run.isTerminal()) {
                throw new BusinessException(ErrorCode.RUN_ENDED);
            }
            WaitingInputState waiting = run.getWaitingInput();
            if (waiting == null || !waiting.taskId().equals(taskId)) {
                throw new BusinessException(ErrorCode.NOT_WAITING_INPUT);
            }
            UserInput input = InputValidation.validate(waiting, optionId, customInput);
            run.setWaitingInput(null);
            waiting.queue().offer(input);
        }
    }

    /**
     * 编排主循环（运行在 orchestrationExecutor 线程中）：发布开始事件、建立主任务分支、
     * 串行消费可运行子任务、触发动态重排并最终合并收尾。所有异常在此统一转换为 error
     * 事件并完成回滚或保留分支，finally 中释放工作区锁。
     */
    private void execute(RunState run, RunRequest request,
                         GitService.RepoInfo repo, String owner) {
        Path root = repo.root();
        String originalBranch = request.originalBranch();
        String mainBranch = request.mainTaskBranch();
        TaskRef rootRef = rootRef(request.planTasks());
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(RUN_TIMEOUT_MINUTES);
        try {
            eventPublisher.publish(run, EventType.RUN_STARTED, null,
                    new EventPayload.RunStarted(request.goal()));
            EventPayload.Plan currentPlan = new EventPayload.Plan(
                    request.planTasks(),
                    request.planRevision() == null ? 1 : request.planRevision(),
                    null, null);
            eventPublisher.publish(run, EventType.PLAN, rootRef, currentPlan);

            gitService.createAndCheckout(root, mainBranch, originalBranch);
            publishCheckpoint(run, rootRef, "branch_created", mainBranch, null,
                    gitService.headSha(root, mainBranch), null, false, "创建主任务分支");
            eventPublisher.publish(run, EventType.TASK_STARTED, rootRef, new EventPayload.Empty());

            RunAgentSession session = new RunAgentSession(run, eventPublisher);
            AgentSettingsService.CurrentAgentSettings settings = agentSettings.current();
            StringBuilder facts = new StringBuilder();
            // 审核开启时，审核返工与模型/网络临时错误共用该上限；
            // 审核关闭时仍保留一次临时错误重试，避免单次请求失败直接终止运行。
            int attemptBudget = settings.reviewEnabled()
                    ? 1 + settings.reviewMaxReworks() : 2;

            while (true) {
                if (run.isTerminal()) {
                    return;
                }
                if (System.nanoTime() > deadline) {
                    throw new TaskFailure("AGENT_TIMEOUT",
                            "运行超过 " + RUN_TIMEOUT_MINUTES + " 分钟上限");
                }
                EventPayload.PlannedTask next = nextRunnable(run, currentPlan);
                if (next == null) {
                    break;
                }
                SubtaskOutcome outcome = runSubtask(run, session, rootRef, root,
                        request, currentPlan, next, mainBranch,
                        settings.reviewEnabled(), attemptBudget, facts);
                if (outcome.cancelled()) {
                    return;
                }
                if (!outcome.success()) {
                    throw new TaskFailure(outcome.errorCode(), outcome.errorMessage());
                }
                facts.append("- 子任务《").append(next.title()).append("》完成：")
                        .append(outcome.summary()).append('\n');
                if (!pendingTasks(run, currentPlan).isEmpty()) {
                    currentPlan = maybeReplan(run, session, rootRef, root, request,
                            currentPlan, outcome.summary(), facts);
                }
            }
            List<EventPayload.PlannedTask> unresolved = pendingTasks(run, currentPlan);
            if (!unresolved.isEmpty()) {
                throw new TaskFailure("PLAN_REVISION_INVALID",
                        "仍有任务依赖未满足: " + titles(unresolved));
            }

            String summary = mainAgent.summarize(request.goal(), currentPlan, facts.toString());
            gitService.ensureBranch(root, mainBranch);
            gitService.mergeNoFf(root, originalBranch, mainBranch);
            String mergedHead = gitService.headSha(root, originalBranch);
            publishCheckpoint(run, rootRef, "merged", mainBranch, originalBranch,
                    mergedHead, null, false, "合并主任务分支到原分支");
            gitService.deleteBranch(root, mainBranch, false);
            publishCheckpoint(run, rootRef, "branch_deleted", mainBranch, null,
                    mergedHead, null, false, "删除主任务分支");

            eventPublisher.publish(run, EventType.TASK_COMPLETED, rootRef,
                    new EventPayload.TaskCompleted(summary));
            eventPublisher.publish(run, EventType.RUN_COMPLETED, null,
                    new EventPayload.RunCompleted(summary));
        } catch (TaskFailure failure) {
            rollbackAndFail(run, root, originalBranch, mainBranch, rootRef,
                    failure.code, failure.getMessage());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.GIT_MERGE_CONFLICT) {
                keepBranchesAfterConflict(run, root, originalBranch, mainBranch, exception);
            } else {
                rollbackAndFail(run, root, originalBranch, mainBranch, rootRef,
                        exception.getErrorCode().name(), exception.getMessage());
            }
        } catch (AgentException exception) {
            rollbackAndFail(run, root, originalBranch, mainBranch, rootRef,
                    exception.code(), exception.getMessage());
        } catch (LlmException exception) {
            rollbackAndFail(run, root, originalBranch, mainBranch, rootRef,
                    exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("真实编排失败 run={}", run.getRunId(), exception);
            rollbackAndFail(run, root, originalBranch, mainBranch, rootRef,
                    "ORCHESTRATOR_ERROR", "编排执行异常: " + exception.getMessage());
        } finally {
            lockService.release(root, owner);
        }
    }

    /**
     * 执行单个子任务，最多 attemptBudget 次尝试。每次尝试从主任务分支切出
     * {@code tasknest/subtasks/<mainTaskId>/<taskId>-a<n>}，执行成功后提交，并在（可选）
     * 审核通过后 {@code --no-ff} 合并回主任务分支、删除子分支。
     *
     * <p>模型/网络临时错误与审核 REWORK 都会丢弃当前分支后重试，并把上次摘要与审核意见
     * 带入下一次尝试；预算耗尽时返回 failed，由主循环统一回滚整个运行。用户取消或运行
     * 已终止时返回 cancelled，调用方应立即退出。</p>
     */
    private SubtaskOutcome runSubtask(RunState run,
                                      RunAgentSession session,
                                      TaskRef rootRef,
                                      Path root,
                                      RunRequest request,
                                      EventPayload.Plan plan,
                                      EventPayload.PlannedTask planned,
                                      String mainBranch,
                                      boolean reviewEnabled,
                                      int attemptBudget,
                                      StringBuilder facts) {
        TaskRef ref = new TaskRef(
                planned.taskId(), rootRef.taskId(), planned.title(), planned.agentName());
        String previousSummary = null;
        String reviewFeedback = null;

        for (int attempt = 1; attempt <= attemptBudget; attempt++) {
            if (run.isTerminal()) {
                return SubtaskOutcome.abort();
            }
            String branch = subtaskBranch(request, planned, attempt);
            gitService.ensureBranch(root, mainBranch);
            gitService.createAndCheckout(root, branch, mainBranch);
            publishCheckpoint(run, ref, "branch_created", branch, mainBranch,
                    gitService.headSha(root, branch), attempt, false,
                    "创建子任务分支（第 " + attempt + " 次尝试）");
            eventPublisher.publish(run, EventType.TASK_STARTED, ref, new EventPayload.Empty());

            AgentOutcome outcome;
            try {
                outcome = subAgent.run(session, ref, root, request.goal(), rootRef.title(),
                        planned, previousSummary, reviewFeedback);
            } catch (AgentException | LlmException exception) {
                discardBranch(run, ref, root, mainBranch, branch, attempt, exception.getMessage());
                if (attempt < attemptBudget) {
                    eventPublisher.publish(run, EventType.TASK_FAILED, ref,
                            new EventPayload.TaskFailed(exception.getMessage(), true));
                    previousSummary = exception.getMessage();
                    reviewFeedback = null;
                    continue;
                }
                eventPublisher.publish(run, EventType.TASK_FAILED, ref,
                        new EventPayload.TaskFailed(exception.getMessage(), false));
                return SubtaskOutcome.failed("SUBAGENT_FAILED", exception.getMessage());
            }
            if (outcome.cancelled()) {
                discardQuietly(root, mainBranch, branch);
                return SubtaskOutcome.abort();
            }
            String summary = outcome.content() == null ? "" : outcome.content().trim();

            String commit;
            try {
                gitService.ensureBranch(root, branch);
                commit = gitService.commitAll(root,
                        "tasknest: " + planned.taskId() + " attempt " + attempt);
            } catch (RuntimeException exception) {
                discardBranch(run, ref, root, mainBranch, branch, attempt,
                        exception.getMessage());
                throw exception;
            }
            publishCheckpoint(run, ref, "committed", branch, null, commit, attempt,
                    commit == null, commit == null ? "无文件变化，未创建空提交" : "提交子任务改动");

            if (reviewEnabled) {
                eventPublisher.publish(run, EventType.REVIEW_STARTED, ref,
                        new EventPayload.ReviewStarted(attempt));
                ReviewResult review;
                try {
                    review = reviewAgent.review(session, ref, root,
                            new ReviewContext(
                                    request.goal(), root.toString(), planned, summary,
                                    gitService.changedFiles(root, mainBranch, branch),
                                    gitService.diffStat(root, mainBranch, branch),
                                    reviewFeedback, attempt));
                } catch (AgentException | LlmException exception) {
                    discardBranch(run, ref, root, mainBranch, branch, attempt,
                            exception.getMessage());
                    throw exception;
                }
                eventPublisher.publish(run, EventType.REVIEW_COMPLETED, ref,
                        new EventPayload.ReviewCompleted(
                                attempt,
                                review.verdict(),
                                review.summary(),
                                review.issues(),
                                review.testStatus(),
                                review.testEvidence(),
                                review.modelName(),
                                review.durationMs()));
                if (review.cancelled()) {
                    discardQuietly(root, mainBranch, branch);
                    return SubtaskOutcome.abort();
                }
                if (review.rework()) {
                    discardBranch(run, ref, root, mainBranch, branch, attempt,
                            review.summary());
                    String feedback = review.feedback();
                    facts.append("  - 第 ").append(attempt).append(" 次审核未通过：")
                            .append(summaryOf(review)).append('\n');
                    if (attempt < attemptBudget) {
                        eventPublisher.publish(run, EventType.TASK_FAILED, ref,
                                new EventPayload.TaskFailed(
                                        "审核退回：" + summaryOf(review), true));
                        previousSummary = summary;
                        reviewFeedback = feedback;
                        continue;
                    }
                    eventPublisher.publish(run, EventType.TASK_FAILED, ref,
                            new EventPayload.TaskFailed(
                                    "审核重做次数已用尽：" + summaryOf(review), false));
                    return SubtaskOutcome.failed("REVIEW_REJECTED",
                            "审核重做次数已用尽：" + summaryOf(review));
                }
                facts.append("  - 审核通过（attempt ").append(attempt).append("，测试 ")
                        .append(review.testStatus()).append("）\n");
            }

            String branchHead = gitService.headSha(root, branch);
            gitService.mergeNoFf(root, mainBranch, branch);
            publishCheckpoint(run, ref, "merged", branch, mainBranch,
                    gitService.headSha(root, mainBranch), attempt, false,
                    "合并子任务分支");
            gitService.deleteBranch(root, branch, false);
            publishCheckpoint(run, ref, "branch_deleted", branch, null,
                    branchHead, attempt, false, "删除已合并的子任务分支");
            eventPublisher.publish(run, EventType.TASK_COMPLETED, ref,
                    new EventPayload.TaskCompleted(summary));
            return SubtaskOutcome.success(summary);
        }
        return SubtaskOutcome.failed("SUBAGENT_FAILED", "子任务没有产生结果");
    }

    /**
     * 询问主 Agent 是否需要重排：仅当仍有 PENDING 任务时尝试。决策取消、校验不通过或
     * 调用异常都只记录日志并沿用当前计划，不让重排失败终止运行；成功时发布新的 plan
     * 事件，并以尽力而为的方式刷新 MySQL 中的计划档案。
     */
    private EventPayload.Plan maybeReplan(RunState run,
                                          RunAgentSession session,
                                          TaskRef rootRef,
                                          Path root,
                                          RunRequest request,
                                          EventPayload.Plan currentPlan,
                                          String lastResult,
                                          StringBuilder facts) {
        List<EventPayload.PlannedTask> pending = pendingTasks(run, currentPlan);
        if (pending.isEmpty() || run.isTerminal()) {
            return currentPlan;
        }
        try {
            ReplanDecision decision = mainAgent.considerReplan(
                    session, rootRef, root, request.goal(), currentPlan,
                    lastResult, describe(pending));
            if (decision.cancelled() || !decision.replan()) {
                return currentPlan;
            }
            EventPayload.Plan revised = planRevisionService.revise(
                    currentPlan, statusSnapshot(run), decision.draft(), decision.reason());
            eventPublisher.publish(run, EventType.PLAN, rootRef, revised);
            try {
                planDraftService.applyRuntimePlan(
                        request.mainTaskId(), revised, decision.reason());
            } catch (RuntimeException exception) {
                log.warn("重排后更新计划档案失败 mainTask={}", request.mainTaskId(), exception);
            }
            facts.append("- 计划重排至 revision ").append(revised.revision())
                    .append("：").append(decision.reason()).append('\n');
            return revised;
        } catch (RuntimeException exception) {
            log.warn("动态重排失败，沿用当前计划: {}", exception.getMessage());
            return currentPlan;
        }
    }

    /**
     * 按计划顺序返回第一个 PENDING 且 dependsOn 全部 SUCCESS 的子任务；根任务与非 PENDING
     * 任务跳过，没有可运行任务时返回 null，表示进入收尾或依赖无法满足。
     */
    private EventPayload.PlannedTask nextRunnable(RunState run, EventPayload.Plan plan) {
        Map<String, TaskNodeState> nodes = snapshotSubtasks(run);
        for (EventPayload.PlannedTask planned : plan.tasks()) {
            if (planned.parentTaskId() == null) {
                continue;
            }
            TaskNodeState node = nodes.get(planned.taskId());
            if (node != null && node.getStatus() != SubtaskStatus.PENDING) {
                continue;
            }
            boolean ready = true;
            for (String dependency : safe(planned.dependsOn())) {
                TaskNodeState dependencyNode = nodes.get(dependency);
                if (dependencyNode == null
                        || dependencyNode.getStatus() != SubtaskStatus.SUCCESS) {
                    ready = false;
                    break;
                }
            }
            if (ready) {
                return planned;
            }
        }
        return null;
    }

    /** 返回计划中仍处于 PENDING（或尚未建立投影）的子任务，用于重排与收尾校验。 */
    private List<EventPayload.PlannedTask> pendingTasks(RunState run, EventPayload.Plan plan) {
        Map<String, TaskNodeState> nodes = snapshotSubtasks(run);
        List<EventPayload.PlannedTask> pending = new ArrayList<>();
        for (EventPayload.PlannedTask planned : plan.tasks()) {
            if (planned.parentTaskId() == null) {
                continue;
            }
            TaskNodeState node = nodes.get(planned.taskId());
            if (node == null || node.getStatus() == SubtaskStatus.PENDING) {
                pending.add(planned);
            }
        }
        return pending;
    }

    /** 复制 taskId → 当前子任务状态的快照，供 {@link PlanRevisionService} 判定哪些任务可改。 */
    private Map<String, SubtaskStatus> statusSnapshot(RunState run) {
        Map<String, SubtaskStatus> statuses = new LinkedHashMap<>();
        for (Map.Entry<String, TaskNodeState> entry : snapshotSubtasks(run).entrySet()) {
            statuses.put(entry.getKey(), entry.getValue().getStatus());
        }
        return statuses;
    }

    /** 在运行锁内复制子任务投影，避免与处理事件的线程并发读写状态。 */
    private Map<String, TaskNodeState> snapshotSubtasks(RunState run) {
        synchronized (run.getLock()) {
            return new LinkedHashMap<>(run.getSubtasks());
        }
    }

    /**
     * 丢弃一次失败尝试的临时分支：若仍在子任务分支上则清理工作区并切回主任务分支，
     * 然后强制删除该分支并记录 rolled_back / branch_deleted 检查点。分支已被外部切换时
     * 不自动 reset，交给后续的漂移校验终止运行。
     */
    private void discardBranch(RunState run, TaskRef ref, Path root,
                               String mainBranch, String branch,
                               int attempt, String message) {
        String sha = gitService.headSha(root, branch);
        String current = gitService.currentBranch(root);
        if (branch.equals(current)) {
            gitService.discardWorkingTree(root, branch);
            gitService.checkout(root, mainBranch);
        } else if (!mainBranch.equals(current)) {
            // 分支被外部切换：不自动 reset，按漂移终止。
            gitService.ensureBranch(root, branch);
        }
        publishCheckpoint(run, ref, "rolled_back", branch, mainBranch, sha, attempt,
                false, message == null ? "回滚子任务分支" : "回滚子任务分支: " + message);
        if (sha != null) {
            gitService.deleteBranch(root, branch, true);
        }
        publishCheckpoint(run, ref, "branch_deleted", branch, null, sha, attempt,
                false, "删除未合并的子任务分支");
    }

    /** 尽力清理临时分支，不发布检查点；用于用户取消导致的中止路径。 */
    private void discardQuietly(Path root, String mainBranch, String branch) {
        try {
            String current = gitService.currentBranch(root);
            if (branch.equals(current)) {
                gitService.discardWorkingTree(root, branch);
                gitService.checkout(root, mainBranch);
            }
            if (gitService.headSha(root, branch) != null) {
                gitService.deleteBranch(root, branch, true);
            }
        } catch (RuntimeException exception) {
            log.warn("清理子任务分支失败 branch={}", branch, exception);
        }
    }

    /**
     * 统一失败收尾：先清理 tasknest/ 临时分支上的未提交改动，切回原始分支，再强制删除
     * 主任务分支并发布 rolled_back / branch_deleted 检查点，最后发送 error 事件。
     * 回滚自身失败时转为 {@code GIT_ROLLBACK_FAILED} 并在消息中保留原始错误。
     * 运行已终态时直接返回，避免重复收尾。
     */
    private void rollbackAndFail(RunState run, Path root, String originalBranch,
                                 String mainBranch, TaskRef rootRef,
                                 String code, String message) {
        if (run.isTerminal()) {
            return;
        }
        try {
            String current = gitService.currentBranch(root);
            if (current != null && current.startsWith("tasknest/")
                    && !current.equals(mainBranch)) {
                gitService.discardWorkingTree(root, current);
            }
            gitService.checkout(root, originalBranch);
            String mainHead = gitService.headSha(root, mainBranch);
            if (mainHead != null) {
                publishCheckpoint(run, rootRef, "rolled_back", mainBranch, originalBranch,
                        mainHead, null, false, "回滚主任务分支: " + message);
                gitService.deleteBranch(root, mainBranch, true);
                publishCheckpoint(run, rootRef, "branch_deleted", mainBranch, null,
                        mainHead, null, false, "删除主任务分支");
            }
        } catch (RuntimeException rollbackError) {
            log.error("回滚失败 run={} branch={}", run.getRunId(), mainBranch, rollbackError);
            eventPublisher.publish(run, EventType.ERROR, null,
                    new EventPayload.Error("GIT_ROLLBACK_FAILED",
                            "回滚失败: " + rollbackError.getMessage() + "；原始错误: " + message));
            return;
        }
        eventPublisher.publish(run, EventType.ERROR, null,
                new EventPayload.Error(code, message));
    }

    /**
     * 合并冲突收尾：不删除任何分支，尽量切回原始分支后发送 GIT_MERGE_CONFLICT。
     * 冲突工作区已由 {@code git merge --abort} 清理，保留下来的是双方分支与提交，
     * 供人工检查或重新合并。
     */
    private void keepBranchesAfterConflict(RunState run, Path root,
                                           String originalBranch, String mainBranch,
                                           BusinessException exception) {
        try {
            gitService.checkout(root, originalBranch);
        } catch (RuntimeException checkoutError) {
            log.warn("合并冲突后切回原分支失败", checkoutError);
        }
        eventPublisher.publish(run, EventType.ERROR, null,
                new EventPayload.Error("GIT_MERGE_CONFLICT",
                        exception.getMessage() + "（已保留分支 " + mainBranch + "）"));
    }

    /** 发布 git_checkpoint 事件；它只用于时间线展示，不改变任何任务状态。 */
    private void publishCheckpoint(RunState run, TaskRef task, String action,
                                   String branch, String targetBranch, String commit,
                                   Integer attempt, boolean noChanges, String message) {
        eventPublisher.publish(run, EventType.GIT_CHECKPOINT, task,
                new EventPayload.GitCheckpoint(
                        action, branch, targetBranch, commit, attempt, noChanges, message));
    }

    /** 从计划中找出 parentTaskId == null 的根任务作为事件引用，缺失时视为非法计划。 */
    private TaskRef rootRef(List<EventPayload.PlannedTask> tasks) {
        return tasks.stream()
                .filter(task -> task.parentTaskId() == null)
                .map(task -> new TaskRef(
                        task.taskId(), null, task.title(), task.agentName()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_PARAM, "计划缺少主任务"));
    }

    private String subtaskBranch(RunRequest request,
                                 EventPayload.PlannedTask planned, int attempt) {
        return subtaskBranchName(request.mainTaskId(), planned.taskId(), attempt);
    }

    /**
     * 子任务分支不能挂在主任务分支名下：Git 的 ref 不能同时存在
     * `refs/heads/tasknest/<mainTaskId>` 与 `refs/heads/tasknest/<mainTaskId>/...`。
     * 因此使用同级命名空间 `tasknest/subtasks/<mainTaskId>/...`。
     */
    static String subtaskBranchName(String mainTaskId, String taskId, int attempt) {
        return "tasknest/subtasks/" + mainTaskId + "/" + taskId + "-a" + attempt;
    }

    private String describe(List<EventPayload.PlannedTask> tasks) {
        StringBuilder builder = new StringBuilder();
        for (EventPayload.PlannedTask task : tasks) {
            builder.append("- ").append(task.taskId()).append(" | ")
                    .append(task.title()).append(" | dependsOn=")
                    .append(safe(task.dependsOn())).append('\n');
        }
        return builder.toString();
    }

    private String titles(List<EventPayload.PlannedTask> tasks) {
        Set<String> titles = new LinkedHashSet<>();
        for (EventPayload.PlannedTask task : tasks) {
            titles.add(task.title());
        }
        return String.join("、", titles);
    }

    private String summaryOf(ReviewResult review) {
        String summary = review.summary();
        return summary == null || summary.isBlank() ? "审核未通过" : summary;
    }

    private List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String owner(String runId) {
        return "run:" + runId;
    }

    /** 子任务一次执行循环的结果；cancelled=true 表示运行已被终止，应立即退出主循环。 */
    private record SubtaskOutcome(boolean success, boolean cancelled,
                                  String summary, String errorCode, String errorMessage) {
        static SubtaskOutcome success(String summary) {
            return new SubtaskOutcome(true, false, summary, null, null);
        }

        static SubtaskOutcome failed(String code, String message) {
            return new SubtaskOutcome(false, false, null, code, message);
        }

        static SubtaskOutcome abort() {
            return new SubtaskOutcome(false, true, null, null, null);
        }
    }

    /** 子任务最终失败时携带错误码，交由执行主循环统一回滚。 */
    private static final class TaskFailure extends RuntimeException {
        private final String code;

        private TaskFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
