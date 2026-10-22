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
import com.tasknest.persistence.entity.MainTaskWorkspaceEntity;
import com.tasknest.persistence.entity.PromptEntity;
import com.tasknest.persistence.entity.RunEventEntity;
import com.tasknest.persistence.entity.SubtaskEntity;
import com.tasknest.persistence.entity.TaskArtifactEntity;
import com.tasknest.persistence.service.MainTaskEntityService;
import com.tasknest.persistence.service.MainTaskPlanEntityService;
import com.tasknest.persistence.service.MainTaskWorkspaceEntityService;
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
 * 主任务档案只读查询服务：左侧目录使用列表，档案页使用详情。
 * <p>所有读取基于 MyBatis-Plus 的基础 list/get/count 方法，不建立 SSE、不提交抉择，
 * 因此档案模式天然只读。持久化的 JSON 字段按行容错解析，坏数据只记日志并回退，
 * 不阻断整份档案的展示。</p>
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
    private final MainTaskWorkspaceEntityService workspaceService;
    private final SubtaskEntityService subtaskService;
    private final PromptEntityService promptService;
    private final TaskArtifactEntityService artifactService;
    private final RunEventEntityService eventService;
    private final ObjectMapper objectMapper;

    public MainTaskQueryService(MainTaskEntityService mainTaskService,
                                MainTaskPlanEntityService planService,
                                MainTaskWorkspaceEntityService workspaceService,
                                SubtaskEntityService subtaskService,
                                PromptEntityService promptService,
                                TaskArtifactEntityService artifactService,
                                RunEventEntityService eventService,
                                ObjectMapper objectMapper) {
        this.mainTaskService = mainTaskService;
        this.planService = planService;
        this.workspaceService = workspaceService;
        this.subtaskService = subtaskService;
        this.promptService = promptService;
        this.artifactService = artifactService;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    /**
     * 按创建时间倒序查询主任务目录。
     *
     * @param limit 期望条数；会夹紧到 1–200，避免目录请求无上限扫描
     * @return 目录摘要，附带计划版本、工作目录与子任务总数/完成数
     */
    public List<MainTaskSummary> list(int limit) {
        int bounded = Math.min(Math.max(limit, 1), 200);
        List<MainTaskEntity> entities = mainTaskService.list(
                Wrappers.<MainTaskEntity>lambdaQuery()
                        .orderByDesc(MainTaskEntity::getCreatedAt)
                        .last("LIMIT " + bounded));
        Map<String, Integer> planVersions = new HashMap<>();
        Map<String, String> workingDirs = new HashMap<>();
        if (!entities.isEmpty()) {
            List<String> ids = entities.stream().map(MainTaskEntity::getMainTaskId).toList();
            List<MainTaskPlanEntity> plans = planService.list(
                    Wrappers.<MainTaskPlanEntity>lambdaQuery()
                            .in(MainTaskPlanEntity::getMainTaskId, ids));
            for (MainTaskPlanEntity plan : plans) {
                planVersions.put(plan.getMainTaskId(),
                        plan.getPlanVersion() == null ? 0 : plan.getPlanVersion());
            }
            List<MainTaskWorkspaceEntity> workspaces = workspaceService.list(
                    Wrappers.<MainTaskWorkspaceEntity>lambdaQuery()
                            .in(MainTaskWorkspaceEntity::getMainTaskId, ids));
            for (MainTaskWorkspaceEntity workspace : workspaces) {
                workingDirs.put(workspace.getMainTaskId(), workspace.getWorkingDir());
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
                    planVersions.getOrDefault(entity.getMainTaskId(), 0),
                    workingDirs.get(entity.getMainTaskId())));
        }
        return result;
    }

    /**
     * 组装主任务只读档案：主任务、计划、直属子任务、提示词、中间结果与事件账本。
     * <p>{@code event_json} 逐行解析为 JSON 节点，空值或坏行跳过；{@code lastSeq} 取
     * 持久化事件的最大序号，前端据此判断档案是否完整。该入口只读 MySQL，
     * 不会恢复运行或重新建立 SSE。</p>
     *
     * @param mainTaskId 主任务业务 ID
     * @return 档案详情
     * @throws BusinessException 主任务不存在时返回 {@link ErrorCode#MAIN_TASK_NOT_FOUND}
     */
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
        MainTaskWorkspaceEntity workspaceEntity = workspaceService.getOne(
                Wrappers.<MainTaskWorkspaceEntity>lambdaQuery()
                        .eq(MainTaskWorkspaceEntity::getMainTaskId, mainTaskId));
        String workingDir = workspaceEntity == null ? null : workspaceEntity.getWorkingDir();

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
                toSummary(main, subtasks.size(), completed, planVersion, workingDir),
                plan,
                subtasks.stream().map(this::toSubtask).toList(),
                prompts.stream().map(this::toPrompt).toList(),
                artifacts.stream().map(this::toArtifact).toList(),
                eventNodes,
                lastSeq);
    }

    /**
     * 按主任务业务 ID 读取档案；不存在时转换为统一的业务异常。
     */
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
                                      int planVersion,
                                      String workingDir) {
        return new MainTaskSummary(
                entity.getMainTaskId(),
                entity.getRunId(),
                entity.getRootTaskId(),
                entity.getTitle(),
                entity.getGoal(),
                workingDir,
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

    /**
     * 容错解析 JSON 列：空值或解析失败返回调用方给定的默认值，并记录告警。
     */
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

    /**
     * 解析事件原文或中间结果 JSON；空值或坏 JSON 返回 {@code null}，由调用方跳过该行。
     */
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
