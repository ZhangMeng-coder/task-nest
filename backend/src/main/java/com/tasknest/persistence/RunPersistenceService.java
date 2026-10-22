package com.tasknest.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.event.EventEnvelope;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.persistence.entity.MainTaskEntity;
import com.tasknest.persistence.entity.MainTaskWorkspaceEntity;
import com.tasknest.persistence.entity.PromptEntity;
import com.tasknest.persistence.entity.RunEventEntity;
import com.tasknest.persistence.entity.SubtaskEntity;
import com.tasknest.persistence.entity.TaskArtifactEntity;
import com.tasknest.persistence.entity.TaskReviewEntity;
import com.tasknest.persistence.service.MainTaskEntityService;
import com.tasknest.persistence.service.MainTaskWorkspaceEntityService;
import com.tasknest.persistence.service.PromptEntityService;
import com.tasknest.persistence.service.RunEventEntityService;
import com.tasknest.persistence.service.SubtaskEntityService;
import com.tasknest.persistence.service.TaskArtifactEntityService;
import com.tasknest.persistence.service.TaskReviewEntityService;
import com.tasknest.store.MainTaskStatus;
import com.tasknest.store.MainTaskState;
import com.tasknest.store.RunState;
import com.tasknest.store.SubtaskStatus;
import com.tasknest.store.TaskNodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 运行事件持久化服务：把实时运行事件同步映射到 MySQL 领域表。
 * <p>每次 {@link #persist(RunState, EventEnvelope)} 在一个事务内先追加事件原文，
 * 再按事件类型更新主任务、直属子任务、提示词、中间结果或审核记录；任一写入失败都会
 * 回滚该事件的全部落库，但异常最终由 {@code EventPublisher} 捕获，保证不影响 SSE 展示。</p>
 * <p>这里只接受已落库的主任务和两层任务模型：主任务未知时跳过，计划中的非直属子任务
 * 只告警；运行终止事件会修复尚未到达终态的子任务，避免档案长期停留在执行中状态。</p>
 */
@Service
public class RunPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(RunPersistenceService.class);

    private final MainTaskEntityService mainTaskService;
    private final MainTaskWorkspaceEntityService workspaceService;
    private final SubtaskEntityService subtaskService;
    private final PromptEntityService promptService;
    private final TaskArtifactEntityService artifactService;
    private final RunEventEntityService eventService;
    private final TaskReviewEntityService reviewService;
    private final ObjectMapper objectMapper;

    public RunPersistenceService(MainTaskEntityService mainTaskService,
                                 MainTaskWorkspaceEntityService workspaceService,
                                 SubtaskEntityService subtaskService,
                                 PromptEntityService promptService,
                                 TaskArtifactEntityService artifactService,
                                 RunEventEntityService eventService,
                                 TaskReviewEntityService reviewService,
                                 ObjectMapper objectMapper) {
        this.mainTaskService = mainTaskService;
        this.workspaceService = workspaceService;
        this.subtaskService = subtaskService;
        this.promptService = promptService;
        this.artifactService = artifactService;
        this.eventService = eventService;
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建 PENDING 主任务档案，并在提供工作目录时写入一对一目录记录。
     * <p>主任务与目录写入处于同一事务；空标题使用占位值，随后由 {@code plan} 事件
     * 补全根任务标题。工作目录为空时不写 {@code tn_main_task_workspace}。</p>
     *
     * @return 已保存的主任务实体（含自增主键）
     */
    @Transactional
    public MainTaskEntity createPending(String mainTaskId,
                                        String runId,
                                        String title,
                                        String goal,
                                        String workingDir) {
        LocalDateTime now = LocalDateTime.now();
        MainTaskEntity entity = new MainTaskEntity();
        entity.setMainTaskId(mainTaskId);
        entity.setRunId(runId);
        entity.setTitle(title == null || title.isBlank() ? "待规划主任务" : title);
        entity.setGoal(goal);
        entity.setStatus(MainTaskStatus.PENDING.name());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mainTaskService.save(entity);
        if (workingDir != null && !workingDir.isBlank()) {
            MainTaskWorkspaceEntity workspace = new MainTaskWorkspaceEntity();
            workspace.setMainTaskId(mainTaskId);
            workspace.setWorkingDir(workingDir);
            workspace.setCreatedAt(now);
            workspace.setUpdatedAt(now);
            workspaceService.save(workspace);
        }
        return entity;
    }

    /**
     * 启动阶段失败时把主任务直接置为 FAILED，并记录错误与结束时间。
     * <p>只更新已存在的主任务；找不到记录时静默返回，避免启动失败路径二次抛错。</p>
     *
     * @param mainTaskId 主任务业务 ID
     * @param message 失败原因
     */
    @Transactional
    public void markStartFailed(String mainTaskId, String message) {
        MainTaskEntity main = findByMainTaskId(mainTaskId);
        if (main == null) {
            return;
        }
        main.setStatus(MainTaskStatus.FAILED.name());
        main.setError(message);
        main.setCompletedAt(LocalDateTime.now());
        main.setUpdatedAt(LocalDateTime.now());
        mainTaskService.updateById(main);
    }

    /**
     * 持久化一个事件原文及其对应的领域表副作用。
     *
     * <p>事务覆盖事件原文、主任务/子任务投影、提示词、中间结果与审核记录，任一写入抛错
     * 都会整体回滚，由调用方记录日志。主任务尚未落库时告警并跳过该事件。成功写入原文后
     * 按类型分派映射：</p>
     * <ul>
     *   <li>{@code RUN_STARTED}/{@code PLAN} 及任务生命周期事件更新主任务与直属子任务；</li>
     *   <li>{@code TASK_THOUGHT}/{@code PROMPT_ASSEMBLED}/{@code TOOL_*} 写入中间结果或提示词；</li>
     *   <li>{@code REVIEW_COMPLETED} 额外写入 {@code tn_task_review}；</li>
     *   <li>{@code RUN_COMPLETED}/{@code ERROR} 落终态并修复遗留子任务；</li>
     *   <li>{@code GIT_CHECKPOINT}/{@code HEARTBEAT} 只有事件原文，不更新领域表。</li>
     * </ul>
     *
     * @param run 内存运行状态，提供主任务与子任务投影
     * @param event 已分配序号的事件信封
     */
    @Transactional
    public void persist(RunState run, EventEnvelope event) {
        MainTaskEntity main = findByMainTaskId(run.getMainTaskId());
        if (main == null) {
            log.warn("主任务 {} 尚未落库，跳过事件 {} seq {}",
                    run.getMainTaskId(), event.eventId(), event.seq());
            return;
        }
        saveEvent(run, event);
        switch (event.type()) {
            case RUN_STARTED -> applyRunStarted(main, event);
            case PLAN -> applyPlan(run, main, event);
            case TASK_STARTED, WAITING_INPUT, INPUT_RECEIVED, TASK_COMPLETED, TASK_FAILED,
                    REVIEW_STARTED, REVIEW_COMPLETED -> {
                applyTaskStatus(run, main, event);
                if (event.type() == EventType.REVIEW_COMPLETED) {
                    saveReview(run, event);
                }
            }
            case TASK_THOUGHT -> saveThought(run, event);
            case PROMPT_ASSEMBLED -> savePrompt(run, event);
            case TOOL_CALL -> saveToolCall(run, event);
            case TOOL_RESULT -> saveToolResult(run, event);
            case GIT_CHECKPOINT -> {
                // Git 审计信息保存在事件原文中，领域表无需更新。
            }
            case RUN_COMPLETED -> applyRunCompleted(run, main, event);
            case ERROR -> applyError(run, main, event);
            case HEARTBEAT -> {
                // 事件原文已保存，供账本回放；没有领域字段需要更新。
            }
        }
    }

    /** 将 run_started 的目标写回主任务，并把更新时间推进到事件时间。 */
    private void applyRunStarted(MainTaskEntity main, EventEnvelope event) {
        if (event.payload() instanceof EventPayload.RunStarted started) {
            main.setGoal(started.goal());
        }
        touch(main, event.timestamp());
        mainTaskService.updateById(main);
    }

    /**
     * 根据 plan 事件建立或重排直属子任务。
     * <p>先从无 {@code parentTaskId} 的任务中确定根任务，再只为父任务等于根任务的行落
     * {@code tn_subtask}；非两层任务只告警。已有 PENDING 子任务可更新标题、Agent、顺序和依赖，
     * 不在新计划中的 PENDING 子任务标记 CANCELLED；已开始或终态子任务不参与重排。</p>
     */
    private void applyPlan(RunState run, MainTaskEntity main, EventEnvelope event) {
        if (!(event.payload() instanceof EventPayload.Plan plan)) {
            return;
        }
        TaskRef root = plan.tasks().stream()
                .filter(task -> task.parentTaskId() == null)
                .map(task -> new TaskRef(task.taskId(), null, task.title(), task.agentName()))
                .findFirst()
                .orElse(null);
        if (root == null) {
            return;
        }
        main.setRootTaskId(root.taskId());
        main.setTitle(root.title());
        if (main.getGoal() == null) {
            main.setGoal(run.getMainTask().getGoal());
        }
        touch(main, event.timestamp());
        mainTaskService.updateById(main);

        int order = 0;
        Set<String> plannedIds = new LinkedHashSet<>();
        for (EventPayload.PlannedTask planned : plan.tasks()) {
            if (planned.parentTaskId() == null) {
                continue;
            }
            if (!Objects.equals(planned.parentTaskId(), root.taskId())) {
                log.warn("忽略非两层任务 {}（parent={}）", planned.taskId(), planned.parentTaskId());
                continue;
            }
            plannedIds.add(planned.taskId());
            SubtaskEntity subtask = findSubtask(main.getMainTaskId(), planned.taskId());
            if (subtask == null) {
                subtask = new SubtaskEntity();
                subtask.setMainTaskId(main.getMainTaskId());
                subtask.setTaskId(planned.taskId());
                subtask.setStatus(SubtaskStatus.PENDING.name());
                subtask.setCreatedAt(toLocal(event.timestamp()));
                subtask.setSortOrder(order);
                subtask.setTitle(planned.title());
                subtask.setAgentName(planned.agentName());
                subtask.setDependsOn(writeJson(planned.dependsOn()));
                subtask.setUpdatedAt(toLocal(event.timestamp()));
                subtaskService.save(subtask);
            } else if (SubtaskStatus.PENDING.name().equals(subtask.getStatus())) {
                // 动态重排只允许修改尚未开始的任务。
                subtask.setTitle(planned.title());
                subtask.setAgentName(planned.agentName());
                subtask.setSortOrder(order);
                subtask.setDependsOn(writeJson(planned.dependsOn()));
                subtask.setUpdatedAt(toLocal(event.timestamp()));
                subtaskService.updateById(subtask);
            }
            order++;
        }
        for (SubtaskEntity existing : subtasksOf(main.getMainTaskId())) {
            if (plannedIds.contains(existing.getTaskId())
                    || !SubtaskStatus.PENDING.name().equals(existing.getStatus())) {
                continue;
            }
            existing.setStatus(SubtaskStatus.CANCELLED.name());
            existing.setError("计划已更新，该待办任务被取消");
            existing.setCompletedAt(toLocal(event.timestamp()));
            existing.setUpdatedAt(toLocal(event.timestamp()));
            subtaskService.updateById(existing);
        }
    }

    /** 按根任务 ID 分流：根任务事件更新主任务，其余事件创建或更新直属子任务。 */
    private void applyTaskStatus(RunState run, MainTaskEntity main, EventEnvelope event) {
        TaskRef task = event.task();
        if (task == null) {
            return;
        }
        boolean isMain = Objects.equals(task.taskId(), run.getMainTask().getRootTaskId());
        if (isMain) {
            applyMainStatus(main, run.getMainTask(), event);
            mainTaskService.updateById(main);
            return;
        }
        SubtaskEntity subtask = findSubtask(main.getMainTaskId(), task.taskId());
        if (subtask == null) {
            subtask = new SubtaskEntity();
            subtask.setMainTaskId(main.getMainTaskId());
            subtask.setTaskId(task.taskId());
            subtask.setTitle(task.title());
            subtask.setAgentName(task.agentName());
            subtask.setStatus(SubtaskStatus.PENDING.name());
            subtask.setSortOrder(9_999);
            subtask.setCreatedAt(toLocal(event.timestamp()));
        }
        TaskNodeState state = run.getSubtasks().get(task.taskId());
        applySubtaskStatus(subtask, state, event);
        if (subtask.getId() == null) {
            subtaskService.save(subtask);
        } else {
            subtaskService.updateById(subtask);
        }
    }

    /**
     * 把任务生命周期事件投射到主任务状态；已 COMPLETED/FAILED 的主任务不再回退。
     * 等待抉择的问题与选项随事件写入，收到输入后清空等待字段。
     */
    private void applyMainStatus(MainTaskEntity main, MainTaskState state, EventEnvelope event) {
        String current = main.getStatus();
        if (MainTaskStatus.COMPLETED.name().equals(current) || MainTaskStatus.FAILED.name().equals(current)) {
            return;
        }
        switch (event.type()) {
            case TASK_STARTED -> {
                main.setStatus(MainTaskStatus.RUNNING.name());
                if (main.getStartedAt() == null) {
                    main.setStartedAt(toLocal(event.timestamp()));
                }
            }
            case WAITING_INPUT -> {
                if (event.payload() instanceof EventPayload.WaitingInput waiting) {
                    main.setStatus(MainTaskStatus.WAITING_INPUT.name());
                    main.setWaitingQuestion(waiting.question());
                    main.setWaitingOptions(writeJson(waiting.options()));
                }
            }
            case INPUT_RECEIVED -> {
                if (event.payload() instanceof EventPayload.InputReceived input) {
                    main.setSelectedOptionId(input.selectedOptionId());
                }
                main.setStatus(MainTaskStatus.RUNNING.name());
                main.setWaitingQuestion(null);
                main.setWaitingOptions(null);
            }
            case TASK_COMPLETED -> {
                if (event.payload() instanceof EventPayload.TaskCompleted completed) {
                    main.setSummary(completed.summary());
                }
                main.setStatus(MainTaskStatus.COMPLETED.name());
                main.setError(null);
                main.setCompletedAt(toLocal(event.timestamp()));
            }
            case TASK_FAILED -> {
                if (event.payload() instanceof EventPayload.TaskFailed failed) {
                    main.setError(failed.error());
                }
                main.setStatus(MainTaskStatus.FAILED.name());
                main.setCompletedAt(toLocal(event.timestamp()));
            }
            default -> {
            }
        }
        if (state != null) {
            main.setSelectedOptionId(state.getSelectedOptionId());
        }
        touch(main, event.timestamp());
    }

    /**
     * 把任务生命周期事件投射到子任务状态；SUCCESS/FAILED/CANCELLED 为终态，不再变更。
     * {@code retryable=true} 的失败和 REWORK 审核都进入 RETRYING，等待下一轮执行。
     */
    private void applySubtaskStatus(SubtaskEntity subtask, TaskNodeState state, EventEnvelope event) {
        String current = subtask.getStatus();
        if (SubtaskStatus.SUCCESS.name().equals(current)
                || SubtaskStatus.FAILED.name().equals(current)
                || SubtaskStatus.CANCELLED.name().equals(current)) {
            return;
        }
        switch (event.type()) {
            case TASK_STARTED -> {
                subtask.setStatus(SubtaskStatus.RUNNING.name());
                if (subtask.getStartedAt() == null) {
                    subtask.setStartedAt(toLocal(event.timestamp()));
                }
            }
            case WAITING_INPUT -> {
                if (event.payload() instanceof EventPayload.WaitingInput waiting) {
                    subtask.setStatus(SubtaskStatus.WAITING_INPUT.name());
                    subtask.setWaitingQuestion(waiting.question());
                    subtask.setWaitingOptions(writeJson(waiting.options()));
                }
            }
            case INPUT_RECEIVED -> {
                if (event.payload() instanceof EventPayload.InputReceived input) {
                    subtask.setSelectedOptionId(input.selectedOptionId());
                }
                subtask.setStatus(SubtaskStatus.RUNNING.name());
                subtask.setWaitingQuestion(null);
                subtask.setWaitingOptions(null);
            }
            case TASK_COMPLETED -> {
                if (event.payload() instanceof EventPayload.TaskCompleted completed) {
                    subtask.setSummary(completed.summary());
                }
                subtask.setStatus(SubtaskStatus.SUCCESS.name());
                subtask.setError(null);
                subtask.setCompletedAt(toLocal(event.timestamp()));
            }
            case TASK_FAILED -> {
                if (event.payload() instanceof EventPayload.TaskFailed failed) {
                    subtask.setError(failed.error());
                    subtask.setStatus(failed.retryable()
                            ? SubtaskStatus.RETRYING.name()
                            : SubtaskStatus.FAILED.name());
                    if (!failed.retryable()) {
                        subtask.setCompletedAt(toLocal(event.timestamp()));
                    }
                }
            }
            case REVIEW_STARTED -> subtask.setStatus(SubtaskStatus.REVIEWING.name());
            case REVIEW_COMPLETED -> {
                if (event.payload() instanceof EventPayload.ReviewCompleted review
                        && "REWORK".equalsIgnoreCase(review.verdict())) {
                    subtask.setStatus(SubtaskStatus.RETRYING.name());
                }
            }
            default -> {
            }
        }
        if (state != null) {
            subtask.setSelectedOptionId(state.getSelectedOptionId());
        }
        subtask.setUpdatedAt(toLocal(event.timestamp()));
    }

    /** 仅保存直属子任务的 review_completed；根任务审核和缺失任务不写审核表。 */
    private void saveReview(RunState run, EventEnvelope event) {
        if (!(event.payload() instanceof EventPayload.ReviewCompleted review)
                || event.task() == null
                || Objects.equals(event.task().taskId(), run.getMainTask().getRootTaskId())) {
            return;
        }
        TaskReviewEntity entity = new TaskReviewEntity();
        entity.setMainTaskId(run.getMainTaskId());
        entity.setTaskId(event.task().taskId());
        entity.setAttempt(review.attempt());
        entity.setVerdict(review.verdict());
        entity.setSummary(review.summary());
        entity.setIssues(writeJson(review.issues()));
        entity.setTestStatus(review.testStatus());
        entity.setTestEvidence(writeJson(review.testEvidence()));
        entity.setModelName(review.modelName());
        entity.setDurationMs(review.durationMs());
        entity.setCreatedAt(toLocal(event.timestamp()));
        reviewService.save(entity);
    }

    /** 思考增量作为 THOUGHT 中间结果保存，content 为 JSON 字符串。 */
    private void saveThought(RunState run, EventEnvelope event) {
        if (!(event.payload() instanceof EventPayload.TaskThought thought) || event.task() == null) {
            return;
        }
        TaskArtifactEntity artifact = newArtifact(run, event, "THOUGHT");
        artifact.setContent(writeJson(thought.delta()));
        artifactService.save(artifact);
    }

    /** prompt_assembled 每出现一次写一行提示词，seq 记录对应事件序号。 */
    private void savePrompt(RunState run, EventEnvelope event) {
        if (!(event.payload() instanceof EventPayload.PromptAssembled prompt) || event.task() == null) {
            return;
        }
        PromptEntity entity = new PromptEntity();
        entity.setMainTaskId(run.getMainTaskId());
        entity.setTaskId(event.task().taskId());
        entity.setSeq(event.seq());
        entity.setSystemPrompt(prompt.systemPrompt());
        entity.setUserPrompt(prompt.userPrompt());
        entity.setContextSnippets(writeJson(prompt.contextSnippets()));
        entity.setFinalPrompt(prompt.finalPrompt());
        entity.setCreatedAt(toLocal(event.timestamp()));
        promptService.save(entity);
    }

    /** 工具调用先以 TOOL_CALL 行保存，状态为空表示尚未配对结果。 */
    private void saveToolCall(RunState run, EventEnvelope event) {
        if (!(event.payload() instanceof EventPayload.ToolCall call) || event.task() == null) {
            return;
        }
        TaskArtifactEntity artifact = newArtifact(run, event, "TOOL_CALL");
        artifact.setCallId(call.callId());
        artifact.setToolName(call.toolName());
        artifact.setContent(writeJson(call.arguments()));
        artifactService.save(artifact);
    }

    /**
     * 工具结果优先按 callId 回填未完成的调用；callId 缺失时按工具名匹配最近一条调用。
     * 找不到配对调用时单独保存 TOOL_RESULT 行，保留孤立结果供账本核对。
     */
    private void saveToolResult(RunState run, EventEnvelope event) {
        if (!(event.payload() instanceof EventPayload.ToolResult result) || event.task() == null) {
            return;
        }
        TaskArtifactEntity call = findOpenToolCall(
                run.getMainTaskId(), event.task().taskId(), result.callId(), result.toolName());
        if (call != null) {
            call.setStatus(result.status().wireName());
            call.setDurationMs(result.durationMs());
            call.setContent(writeJson(result.result()));
            artifactService.updateById(call);
            return;
        }
        TaskArtifactEntity artifact = newArtifact(run, event, "TOOL_RESULT");
        artifact.setCallId(result.callId());
        artifact.setToolName(result.toolName());
        artifact.setStatus(result.status().wireName());
        artifact.setDurationMs(result.durationMs());
        artifact.setContent(writeJson(result.result()));
        artifactService.save(artifact);
    }

    /** 用主任务、任务、事件序号和时间等公共字段构造一条中间结果。 */
    private TaskArtifactEntity newArtifact(RunState run, EventEnvelope event, String kind) {
        TaskArtifactEntity artifact = new TaskArtifactEntity();
        artifact.setMainTaskId(run.getMainTaskId());
        artifact.setTaskId(event.task().taskId());
        artifact.setSeq(event.seq());
        artifact.setKind(kind);
        artifact.setCreatedAt(toLocal(event.timestamp()));
        return artifact;
    }

    /**
     * 在同一主任务、同一子任务的未完成 TOOL_CALL 中配对：有 callId 时精确匹配，
     * 否则按事件序号倒序取最近一条同名调用；没有匹配返回 {@code null}。
     */
    private TaskArtifactEntity findOpenToolCall(String mainTaskId,
                                                String taskId,
                                                String callId,
                                                String toolName) {
        List<TaskArtifactEntity> candidates = artifactService.list(
                Wrappers.<TaskArtifactEntity>lambdaQuery()
                        .eq(TaskArtifactEntity::getMainTaskId, mainTaskId)
                        .eq(TaskArtifactEntity::getTaskId, taskId)
                        .eq(TaskArtifactEntity::getKind, "TOOL_CALL")
                        .isNull(TaskArtifactEntity::getStatus)
                        .orderByDesc(TaskArtifactEntity::getSeq));
        for (TaskArtifactEntity candidate : candidates) {
            if (callId != null && callId.equals(candidate.getCallId())) {
                return candidate;
            }
            if (callId == null && toolName.equals(candidate.getToolName())) {
                return candidate;
            }
        }
        return null;
    }

    /** 运行正常结束：非终态主任务置 COMPLETED，并修复仍未结束的子任务。 */
    private void applyRunCompleted(RunState run, MainTaskEntity main, EventEnvelope event) {
        if (!isTerminal(main.getStatus())) {
            if (event.payload() instanceof EventPayload.RunCompleted completed && main.getSummary() == null) {
                main.setSummary(completed.summary());
            }
            main.setStatus(MainTaskStatus.COMPLETED.name());
            main.setCompletedAt(toLocal(event.timestamp()));
            touch(main, event.timestamp());
            mainTaskService.updateById(main);
        }
        repairSubtasks(main,
                main.getError() == null ? "运行已完成但该子任务未完成" : main.getError(),
                event.timestamp());
    }

    /** 运行错误：非终态主任务置 FAILED，并修复仍未结束的子任务。 */
    private void applyError(RunState run, MainTaskEntity main, EventEnvelope event) {
        if (!isTerminal(main.getStatus())) {
            if (event.payload() instanceof EventPayload.Error error) {
                main.setError(error.message());
            }
            main.setStatus(MainTaskStatus.FAILED.name());
            main.setCompletedAt(toLocal(event.timestamp()));
            touch(main, event.timestamp());
            mainTaskService.updateById(main);
        }
        String message = event.payload() instanceof EventPayload.Error error
                ? error.message()
                : main.getError();
        repairSubtasks(main, message, event.timestamp());
    }

    /**
     * 终止修复：把未到 SUCCESS/FAILED/CANCELLED 的子任务统一标记 FAILED。
     * 已有的子任务错误保留，缺失时写入运行级错误原因。
     */
    private void repairSubtasks(MainTaskEntity main, String message, Instant timestamp) {
        List<SubtaskEntity> subtasks = subtaskService.list(
                Wrappers.<SubtaskEntity>lambdaQuery()
                        .eq(SubtaskEntity::getMainTaskId, main.getMainTaskId()));
        for (SubtaskEntity subtask : subtasks) {
            if (isTerminalSubtask(subtask.getStatus())) {
                continue;
            }
            subtask.setStatus(SubtaskStatus.FAILED.name());
            if (subtask.getError() == null) {
                subtask.setError(message);
            }
            subtask.setCompletedAt(toLocal(timestamp));
            subtask.setUpdatedAt(toLocal(timestamp));
            subtaskService.updateById(subtask);
        }
    }

    /** 把事件信封序列化为原文行；运行级事件的 task_id 保持为空。 */
    private void saveEvent(RunState run, EventEnvelope event) {
        RunEventEntity entity = new RunEventEntity();
        entity.setMainTaskId(run.getMainTaskId());
        entity.setRunId(run.getRunId());
        entity.setEventId(event.eventId());
        entity.setSeq(event.seq());
        entity.setType(event.type().wireName());
        entity.setTaskId(event.task() == null ? null : event.task().taskId());
        entity.setEventJson(writeJson(event));
        entity.setEventTime(toLocal(event.timestamp()));
        entity.setCreatedAt(toLocal(event.timestamp()));
        eventService.save(entity);
    }

    /** 按业务 ID 查主任务；未知返回 {@code null}，由调用方决定是否跳过。 */
    private MainTaskEntity findByMainTaskId(String mainTaskId) {
        return mainTaskService.getOne(
                Wrappers.<MainTaskEntity>lambdaQuery()
                        .eq(MainTaskEntity::getMainTaskId, mainTaskId));
    }

    /** 在指定主任务内按业务 ID 查直属子任务。 */
    private SubtaskEntity findSubtask(String mainTaskId, String taskId) {
        return subtaskService.getOne(
                Wrappers.<SubtaskEntity>lambdaQuery()
                        .eq(SubtaskEntity::getMainTaskId, mainTaskId)
                        .eq(SubtaskEntity::getTaskId, taskId));
    }

    /** 统一把主任务更新时间推进到事件时间。 */
    private void touch(MainTaskEntity main, Instant timestamp) {
        main.setUpdatedAt(toLocal(timestamp));
    }

    /** 按系统时区把事件时间转换为数据库使用的 {@link LocalDateTime}。 */
    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /** 主任务 COMPLETED/FAILED 为终态，不再被后续状态事件改写。 */
    private boolean isTerminal(String status) {
        return MainTaskStatus.COMPLETED.name().equals(status)
                || MainTaskStatus.FAILED.name().equals(status);
    }

    /** 子任务 SUCCESS/FAILED/CANCELLED 为终态，重排与状态投射都跳过。 */
    private boolean isTerminalSubtask(String status) {
        return SubtaskStatus.SUCCESS.name().equals(status)
                || SubtaskStatus.FAILED.name().equals(status)
                || SubtaskStatus.CANCELLED.name().equals(status);
    }

    /** 序列化 JSON 列；失败记录告警并返回 {@code null}，交由调用方/数据库约束处理。 */
    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.warn("无法序列化持久化内容", exception);
            return null;
        }
    }

    /**
     * 按计划顺序读取指定主任务的全部直属子任务，供动态重排比对。
     *
     * @param mainTaskId 主任务业务 ID
     * @return 按 {@code sort_order} 升序排列的子任务实体
     */
    public List<SubtaskEntity> subtasksOf(String mainTaskId) {
        return subtaskService.list(Wrappers.<SubtaskEntity>lambdaQuery()
                .eq(SubtaskEntity::getMainTaskId, mainTaskId)
                .orderByAsc(SubtaskEntity::getSortOrder));
    }
}
