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
 * 把实时事件落到 MySQL。实体 CRUD 使用 MyBatis-Plus 的 Service / Mapper 基础方法，
 * 这里只负责“事件 -> 主任务 / 子任务 / 提示词 / 中间结果 / 事件原文”的映射。
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

    @Transactional
    public MainTaskEntity createPending(String mainTaskId,
                                        String runId,
                                        String title,
                                        String goal,
                                        String mockScript,
                                        String workingDir) {
        LocalDateTime now = LocalDateTime.now();
        MainTaskEntity entity = new MainTaskEntity();
        entity.setMainTaskId(mainTaskId);
        entity.setRunId(runId);
        entity.setTitle(title == null || title.isBlank() ? "待规划主任务" : title);
        entity.setGoal(goal);
        entity.setMockScript(mockScript);
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
     * 事务失败会回滚整条事件的所有落库；调用方（EventPublisher）负责记录日志，
     * 保证持久化故障不会阻断 SSE 展示。
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

    private void applyRunStarted(MainTaskEntity main, EventEnvelope event) {
        if (event.payload() instanceof EventPayload.RunStarted started) {
            main.setGoal(started.goal());
        }
        touch(main, event.timestamp());
        mainTaskService.updateById(main);
    }

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

    private void saveThought(RunState run, EventEnvelope event) {
        if (!(event.payload() instanceof EventPayload.TaskThought thought) || event.task() == null) {
            return;
        }
        TaskArtifactEntity artifact = newArtifact(run, event, "THOUGHT");
        artifact.setContent(writeJson(thought.delta()));
        artifactService.save(artifact);
    }

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

    private TaskArtifactEntity newArtifact(RunState run, EventEnvelope event, String kind) {
        TaskArtifactEntity artifact = new TaskArtifactEntity();
        artifact.setMainTaskId(run.getMainTaskId());
        artifact.setTaskId(event.task().taskId());
        artifact.setSeq(event.seq());
        artifact.setKind(kind);
        artifact.setCreatedAt(toLocal(event.timestamp()));
        return artifact;
    }

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

    private MainTaskEntity findByMainTaskId(String mainTaskId) {
        return mainTaskService.getOne(
                Wrappers.<MainTaskEntity>lambdaQuery()
                        .eq(MainTaskEntity::getMainTaskId, mainTaskId));
    }

    private SubtaskEntity findSubtask(String mainTaskId, String taskId) {
        return subtaskService.getOne(
                Wrappers.<SubtaskEntity>lambdaQuery()
                        .eq(SubtaskEntity::getMainTaskId, mainTaskId)
                        .eq(SubtaskEntity::getTaskId, taskId));
    }

    private void touch(MainTaskEntity main, Instant timestamp) {
        main.setUpdatedAt(toLocal(timestamp));
    }

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private boolean isTerminal(String status) {
        return MainTaskStatus.COMPLETED.name().equals(status)
                || MainTaskStatus.FAILED.name().equals(status);
    }

    private boolean isTerminalSubtask(String status) {
        return SubtaskStatus.SUCCESS.name().equals(status)
                || SubtaskStatus.FAILED.name().equals(status)
                || SubtaskStatus.CANCELLED.name().equals(status);
    }

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

    public List<SubtaskEntity> subtasksOf(String mainTaskId) {
        return subtaskService.list(Wrappers.<SubtaskEntity>lambdaQuery()
                .eq(SubtaskEntity::getMainTaskId, mainTaskId)
                .orderByAsc(SubtaskEntity::getSortOrder));
    }
}
