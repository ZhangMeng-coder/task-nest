package com.tasknest.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.api.dto.ArtifactView;
import com.tasknest.api.dto.MainTaskDetail;
import com.tasknest.api.dto.MainTaskSummary;
import com.tasknest.api.dto.PlanDraftView;
import com.tasknest.api.dto.PlanRowView;
import com.tasknest.api.dto.PromptView;
import com.tasknest.api.dto.SubtaskView;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventPayload;
import com.tasknest.persistence.entity.MainTaskEntity;
import com.tasknest.persistence.entity.MainTaskPlanEntity;
import com.tasknest.persistence.entity.PromptEntity;
import com.tasknest.persistence.entity.RunEventEntity;
import com.tasknest.persistence.entity.SubtaskEntity;
import com.tasknest.persistence.entity.TaskArtifactEntity;
import com.tasknest.persistence.service.MainTaskEntityService;
import com.tasknest.persistence.service.MainTaskPlanEntityService;
import com.tasknest.persistence.service.PromptEntityService;
import com.tasknest.persistence.service.RunEventEntityService;
import com.tasknest.persistence.service.SubtaskEntityService;
import com.tasknest.persistence.service.TaskArtifactEntityService;
import com.tasknest.store.MainTaskStatus;
import com.tasknest.store.SubtaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主任务档案查询。所有查询都基于 MyBatis-Plus 的基础 list/get/count 方法。
 */
@Service
public class MainTaskQueryService {

    private static final Logger log = LoggerFactory.getLogger(MainTaskQueryService.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<EventPayload.DecisionOption>> OPTIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<EventPayload.ContextSnippet>> SNIPPETS = new TypeReference<>() {
    };
    private static final TypeReference<List<PlanRowView>> PLAN_ROWS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> PLAN_REVISIONS = new TypeReference<>() {
    };

    private final MainTaskEntityService mainTaskService;
    private final MainTaskPlanEntityService planService;
    private final SubtaskEntityService subtaskService;
    private final PromptEntityService promptService;
    private final TaskArtifactEntityService artifactService;
    private final RunEventEntityService eventService;
    private final ObjectMapper objectMapper;

    public MainTaskQueryService(MainTaskEntityService mainTaskService,
                                MainTaskPlanEntityService planService,
                                SubtaskEntityService subtaskService,
                                PromptEntityService promptService,
                                TaskArtifactEntityService artifactService,
                                RunEventEntityService eventService,
                                ObjectMapper objectMapper) {
        this.mainTaskService = mainTaskService;
        this.planService = planService;
        this.subtaskService = subtaskService;
        this.promptService = promptService;
        this.artifactService = artifactService;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    public List<MainTaskSummary> list(int limit) {
        int bounded = Math.min(Math.max(limit, 1), 200);
        List<MainTaskEntity> entities = mainTaskService.list(
                Wrappers.<MainTaskEntity>lambdaQuery()
                        .orderByDesc(MainTaskEntity::getCreatedAt)
                        .last("LIMIT " + bounded));
        Map<String, Integer> planVersions = new HashMap<>();
        if (!entities.isEmpty()) {
            List<String> ids = entities.stream().map(MainTaskEntity::getMainTaskId).toList();
            List<MainTaskPlanEntity> plans = planService.list(
                    Wrappers.<MainTaskPlanEntity>lambdaQuery()
                            .in(MainTaskPlanEntity::getMainTaskId, ids));
            for (MainTaskPlanEntity plan : plans) {
                planVersions.put(plan.getMainTaskId(),
                        plan.getPlanVersion() == null ? 0 : plan.getPlanVersion());
            }
        }
        List<MainTaskSummary> result = new ArrayList<>(entities.size());
        for (MainTaskEntity entity : entities) {
            long total = subtaskService.count(Wrappers.<SubtaskEntity>lambdaQuery()
                    .eq(SubtaskEntity::getMainTaskId, entity.getMainTaskId()));
            long completed = subtaskService.count(Wrappers.<SubtaskEntity>lambdaQuery()
                    .eq(SubtaskEntity::getMainTaskId, entity.getMainTaskId())
                    .eq(SubtaskEntity::getStatus, SubtaskStatus.SUCCESS.name()));
            result.add(toSummary(entity, total, completed,
                    planVersions.getOrDefault(entity.getMainTaskId(), 0)));
        }
        return result;
    }

    public MainTaskDetail detail(String mainTaskId) {
        MainTaskEntity main = findMain(mainTaskId);
        List<SubtaskEntity> subtasks = subtaskService.list(
                Wrappers.<SubtaskEntity>lambdaQuery()
                        .eq(SubtaskEntity::getMainTaskId, mainTaskId)
                        .orderByAsc(SubtaskEntity::getSortOrder));
        List<PromptEntity> prompts = promptService.list(
                Wrappers.<PromptEntity>lambdaQuery()
                        .eq(PromptEntity::getMainTaskId, mainTaskId)
                        .orderByAsc(PromptEntity::getSeq));
        List<TaskArtifactEntity> artifacts = artifactService.list(
                Wrappers.<TaskArtifactEntity>lambdaQuery()
                        .eq(TaskArtifactEntity::getMainTaskId, mainTaskId)
                        .orderByAsc(TaskArtifactEntity::getSeq));
        List<RunEventEntity> events = eventService.list(
                Wrappers.<RunEventEntity>lambdaQuery()
                        .eq(RunEventEntity::getMainTaskId, mainTaskId)
                        .orderByAsc(RunEventEntity::getSeq));
        MainTaskPlanEntity planEntity = planService.getOne(
                Wrappers.<MainTaskPlanEntity>lambdaQuery()
                        .eq(MainTaskPlanEntity::getMainTaskId, mainTaskId));
        PlanDraftView plan = planEntity == null ? null : toPlan(planEntity);

        long completed = subtasks.stream()
                .filter(subtask -> SubtaskStatus.SUCCESS.name().equals(subtask.getStatus()))
                .count();
        List<JsonNode> eventNodes = new ArrayList<>();
        long lastSeq = 0;
        for (RunEventEntity event : events) {
            JsonNode node = readTree(event.getEventJson());
            if (node != null) {
                eventNodes.add(node);
            }
            lastSeq = Math.max(lastSeq, event.getSeq());
        }

        int planVersion = plan == null ? 0 : plan.version();
        return new MainTaskDetail(
                toSummary(main, subtasks.size(), completed, planVersion),
                plan,
                subtasks.stream().map(this::toSubtask).toList(),
                prompts.stream().map(this::toPrompt).toList(),
                artifacts.stream().map(this::toArtifact).toList(),
                eventNodes,
                lastSeq);
    }

    private MainTaskEntity findMain(String mainTaskId) {
        MainTaskEntity main = mainTaskService.getOne(
                Wrappers.<MainTaskEntity>lambdaQuery()
                        .eq(MainTaskEntity::getMainTaskId, mainTaskId));
        if (main == null) {
            throw new BusinessException(ErrorCode.MAIN_TASK_NOT_FOUND);
        }
        return main;
    }

    private MainTaskSummary toSummary(MainTaskEntity entity,
                                      long subtaskCount,
                                      long completed,
                                      int planVersion) {
        return new MainTaskSummary(
                entity.getMainTaskId(),
                entity.getRunId(),
                entity.getRootTaskId(),
                entity.getTitle(),
                entity.getGoal(),
                entity.getMockScript(),
                planVersion,
                MainTaskStatus.valueOf(entity.getStatus()),
                entity.getSummary(),
                entity.getError(),
                subtaskCount,
                completed,
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getUpdatedAt()),
                toInstant(entity.getCompletedAt()));
    }

    private PlanDraftView toPlan(MainTaskPlanEntity entity) {
        return new PlanDraftView(
                entity.getMainTaskId(),
                entity.getPlanTitle(),
                entity.getPlanGoal(),
                entity.getPlanScript(),
                entity.getPlanVersion() == null ? 1 : entity.getPlanVersion(),
                entity.getPlanMarkdown(),
                readJson(entity.getPlanRows(), PLAN_ROWS, Collections.emptyList()),
                readJson(entity.getPlanRevisions(), PLAN_REVISIONS, Collections.emptyList()));
    }

    private SubtaskView toSubtask(SubtaskEntity entity) {
        return new SubtaskView(
                entity.getTaskId(),
                entity.getTitle(),
                entity.getAgentName(),
                SubtaskStatus.valueOf(entity.getStatus()),
                entity.getSortOrder() == null ? 0 : entity.getSortOrder(),
                readJson(entity.getDependsOn(), STRING_LIST, Collections.emptyList()),
                entity.getSummary(),
                entity.getError(),
                entity.getWaitingQuestion(),
                readJson(entity.getWaitingOptions(), OPTIONS, Collections.emptyList()),
                entity.getSelectedOptionId(),
                toInstant(entity.getStartedAt()),
                toInstant(entity.getCompletedAt()),
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getUpdatedAt()));
    }

    private PromptView toPrompt(PromptEntity entity) {
        return new PromptView(
                entity.getTaskId(),
                entity.getSeq() == null ? 0 : entity.getSeq(),
                entity.getSystemPrompt(),
                entity.getUserPrompt(),
                readJson(entity.getContextSnippets(), SNIPPETS, Collections.emptyList()),
                entity.getFinalPrompt(),
                toInstant(entity.getCreatedAt()));
    }

    private ArtifactView toArtifact(TaskArtifactEntity entity) {
        return new ArtifactView(
                entity.getTaskId(),
                entity.getSeq() == null ? 0 : entity.getSeq(),
                entity.getKind(),
                entity.getCallId(),
                entity.getToolName(),
                entity.getStatus(),
                entity.getDurationMs(),
                readTree(entity.getContent()),
                toInstant(entity.getCreatedAt()));
    }

    private <T> T readJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            log.warn("无法解析持久化 JSON: {}", json, exception);
            return fallback;
        }
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            log.warn("无法解析持久化 JSON: {}", json, exception);
            return null;
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
