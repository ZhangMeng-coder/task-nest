package com.tasknest.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.api.dto.PlanDraftView;
import com.tasknest.api.dto.PlanRowView;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventPayload;
import com.tasknest.persistence.entity.MainTaskEntity;
import com.tasknest.persistence.entity.MainTaskPlanEntity;
import com.tasknest.persistence.service.MainTaskEntityService;
import com.tasknest.persistence.service.MainTaskPlanEntityService;
import com.tasknest.store.MainTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 计划表渲染、修订与持久化。
 *
 * <p>计划内容来自真实规划 Agent 的确认稿；本类负责把两层任务渲染为 Markdown、
 * 保存 / 读取一对一计划档案，并支持运行期动态重排与档案保存后的提示词修订。</p>
 */
@Service
public class PlanDraftService {

    private static final Logger log = LoggerFactory.getLogger(PlanDraftService.class);
    private static final TypeReference<List<PlanRowView>> ROWS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final int MAX_INSTRUCTION_PREVIEW = 18;

    private final MainTaskPlanEntityService planService;
    private final MainTaskEntityService mainTaskService;
    private final ObjectMapper objectMapper;

    public PlanDraftService(MainTaskPlanEntityService planService,
                            MainTaskEntityService mainTaskService,
                            ObjectMapper objectMapper) {
        this.planService = planService;
        this.mainTaskService = mainTaskService;
        this.objectMapper = objectMapper;
    }

    /** 保存规划会话确认的计划。 */
    public PlanDraftView fromConfirmed(String goal,
                                       String title,
                                       List<PlanRowView> rows,
                                       List<String> revisions,
                                       int revision) {
        return buildDraft(null, title, goal, rows, revisions, revision);
    }

    /**
     * 执行期动态重排后更新计划档案：保留已有修订记录，追加本次重排原因并递增版本。
     * 已取消的任务不再出现在计划表中，其状态由子任务记录保留。
     */
    @Transactional
    public void applyRuntimePlan(String mainTaskId, EventPayload.Plan plan, String reason) {
        PlanDraftView current = find(mainTaskId);
        if (current == null || plan == null) {
            return;
        }
        String title = plan.tasks().stream()
                .filter(task -> task.parentTaskId() == null)
                .map(EventPayload.PlannedTask::title)
                .findFirst()
                .orElse(current.title());
        List<EventPayload.PlannedTask> children = plan.tasks().stream()
                .filter(task -> task.parentTaskId() != null)
                .toList();
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < children.size(); index++) {
            indexes.put(children.get(index).taskId(), index + 1);
        }
        List<PlanRowView> rows = new ArrayList<>(children.size());
        for (EventPayload.PlannedTask child : children) {
            String output = child.acceptanceCriteria() == null
                    || child.acceptanceCriteria().isEmpty()
                    ? "可复核结果" : child.acceptanceCriteria().get(0);
            rows.add(new PlanRowView(
                    child.title(),
                    child.agentName(),
                    dependencyOf(child.dependsOn(), indexes),
                    output,
                    child.taskId(),
                    child.description(),
                    child.acceptanceCriteria(),
                    child.allowedTools(),
                    child.testCommand()));
        }
        List<String> revisions = new ArrayList<>(current.revisions());
        int revision = plan.revision() == null
                ? Math.max(1, current.revision()) + 1 : plan.revision();
        revisions.add("动态重排 revision " + revision + "："
                + (reason == null || reason.isBlank() ? "主 Agent 调整计划" : reason));
        PlanDraftView next = buildDraft(mainTaskId, title, current.goal(),
                rows, revisions, revision);
        save(mainTaskId, next);
    }

    /**
     * 新增或更新主任务的一对一计划档案。计划表与主任务分表保存，
     * 因此已存在的库无需给 {@code tn_main_task} 补列。
     *
     * @return 回填 {@code mainTaskId} 的计划视图
     */
    @Transactional
    public PlanDraftView save(String mainTaskId, PlanDraftView draft) {
        LocalDateTime now = LocalDateTime.now();
        MainTaskPlanEntity entity = findByMainTaskId(mainTaskId);
        if (entity == null) {
            entity = new MainTaskPlanEntity();
            entity.setMainTaskId(mainTaskId);
            entity.setCreatedAt(now);
        }
        entity.setPlanTitle(draft.title());
        entity.setPlanGoal(draft.goal());
        entity.setPlanVersion(draft.version());
        entity.setPlanMarkdown(draft.markdown());
        entity.setPlanRows(writeJson(draft.rows()));
        entity.setPlanRevisions(writeJson(draft.revisions()));
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            planService.save(entity);
        } else {
            planService.updateById(entity);
        }
        return draft.withMainTaskId(mainTaskId);
    }

    /**
     * 查询计划档案；没有计划时返回 {@code null}，由调用方决定是否视为错误。
     */
    public PlanDraftView find(String mainTaskId) {
        MainTaskPlanEntity entity = findByMainTaskId(mainTaskId);
        return entity == null ? null : toView(entity);
    }

    /**
     * 对已经保存的计划做一次提示词修订。只更新计划档案，不回滚或新增正在执行的子任务。
     */
    @Transactional
    public PlanDraftView reviseStored(String mainTaskId, String instruction) {
        MainTaskEntity main = findMain(mainTaskId);
        if (MainTaskStatus.COMPLETED.name().equals(main.getStatus())
                || MainTaskStatus.FAILED.name().equals(main.getStatus())) {
            throw new BusinessException(ErrorCode.PLAN_READ_ONLY);
        }
        PlanDraftView current = find(mainTaskId);
        if (current == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        PlanDraftView revised = applyInstruction(
                current.title(),
                current.goal(),
                current.rows(),
                current.revisions(),
                instruction);
        return save(mainTaskId, revised);
    }

    /**
     * 修订规则完全由关键词决定（删 / 精简 / 风险复核 / 其他补充步骤），
     * 不调用模型；每次修订追加一条修订记录并重算版本号。
     */
    private PlanDraftView applyInstruction(String title,
                                           String goal,
                                           List<PlanRowView> rows,
                                           List<String> revisions,
                                           String instruction) {
        String note = instruction == null ? "" : instruction.trim();
        if (note.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "先输入一条修改提示");
        }
        List<PlanRowView> nextRows = new ArrayList<>(rows == null ? List.of() : rows);
        List<String> nextRevisions = new ArrayList<>(revisions == null ? List.of() : revisions);
        String summary;
        if (matches(note, "删", "精简", "减少")) {
            if (nextRows.size() > 1) {
                PlanRowView removed = nextRows.remove(nextRows.size() - 1);
                summary = "按提示「" + note + "」移除步骤：" + removed.title();
            } else {
                summary = "按提示「" + note + "」尝试精简，计划至少保留一个步骤，本次未改动";
            }
        } else if (matches(note, "风险", "复核", "检查")) {
            nextRows.add(new PlanRowView(
                    "补充风险复核",
                    "审核者",
                    dependencyOf(nextRows.size()),
                    "风险与后续动作"));
            summary = "按提示「" + note + "」增加风险复核步骤";
        } else {
            String preview = note.length() > MAX_INSTRUCTION_PREVIEW
                    ? note.substring(0, MAX_INSTRUCTION_PREVIEW)
                    : note;
            nextRows.add(new PlanRowView(
                    "补充步骤：" + preview,
                    "执行者",
                    dependencyOf(nextRows.size()),
                    "补充说明与结果"));
            summary = "按提示「" + note + "」增加补充步骤";
        }
        nextRevisions.add(summary);
        return buildDraft(null, title, goal, nextRows, nextRevisions);
    }

    /**
     * 未显式给出修订号时，以“修订记录数 + 1”作为版本号，保证首次草案为 1。
     */
    private PlanDraftView buildDraft(String mainTaskId,
                                     String title,
                                     String goal,
                                     List<PlanRowView> rows,
                                     List<String> revisions) {
        return buildDraft(mainTaskId, title, goal, rows, revisions,
                revisions.size() + 1);
    }

    private PlanDraftView buildDraft(String mainTaskId,
                                     String title,
                                     String goal,
                                     List<PlanRowView> rows,
                                     List<String> revisions,
                                     int revision) {
        int version = Math.max(1, revision);
        String markdown = buildMarkdown(title, goal, rows, revisions);
        return new PlanDraftView(
                mainTaskId,
                title,
                goal,
                version,
                markdown,
                List.copyOf(rows),
                List.copyOf(revisions),
                version);
    }

    private String dependencyOf(List<String> dependsOn, Map<String, Integer> indexes) {
        Set<Integer> steps = new LinkedHashSet<>();
        for (String taskId : dependsOn) {
            Integer index = indexes.get(taskId);
            if (index != null) {
                steps.add(index);
            }
        }
        if (steps.isEmpty()) {
            return "—";
        }
        List<Integer> ordered = steps.stream().sorted(Comparator.naturalOrder()).toList();
        StringJoiner joiner = new StringJoiner("、");
        for (Integer step : ordered) {
            joiner.add(String.valueOf(step));
        }
        return "第 " + joiner + " 步";
    }

    private String dependencyOf(int previousRowCount) {
        return previousRowCount <= 0 ? "—" : "第 " + previousRowCount + " 步";
    }

    private String buildMarkdown(String title,
                                 String goal,
                                 List<PlanRowView> rows,
                                 List<String> revisions) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(title).append("\n\n");
        builder.append("> 目标：").append(oneLine(goal)).append("\n\n");
        builder.append("## 执行计划\n\n");
        builder.append("| # | 子任务 | Agent | 依赖 | 预期产出 |\n");
        builder.append("| --- | --- | --- | --- | --- |\n");
        for (int index = 0; index < rows.size(); index++) {
            PlanRowView row = rows.get(index);
            builder.append("| ").append(index + 1)
                    .append(" | ").append(cell(row.title()))
                    .append(" | ").append(cell(row.agent()))
                    .append(" | ").append(cell(row.dependency()))
                    .append(" | ").append(cell(row.output()))
                    .append(" |\n");
        }
        builder.append("\n## 执行约束\n\n");
        builder.append("- 只允许 **两层结构**：一个主任务 + 直属子任务。\n");
        builder.append("- 每个子任务结束必须回传摘要或错误，不允许静默跳过。\n");
        builder.append("- 需要人判断时暂停并等待抉择，不得自行假设。\n");
        builder.append("- 计划表是主任务档案的一部分，修订后随主任务一起保存。\n");
        if (!revisions.isEmpty()) {
            builder.append("\n## 修订记录\n\n");
            for (String revision : revisions) {
                builder.append("- ").append(oneLine(revision)).append("\n");
            }
        }
        return builder.toString();
    }

    private String cell(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ").trim();
    }

    private boolean matches(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
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

    private MainTaskPlanEntity findByMainTaskId(String mainTaskId) {
        return planService.getOne(Wrappers.<MainTaskPlanEntity>lambdaQuery()
                .eq(MainTaskPlanEntity::getMainTaskId, mainTaskId));
    }

    private PlanDraftView toView(MainTaskPlanEntity entity) {
        return new PlanDraftView(
                entity.getMainTaskId(),
                entity.getPlanTitle(),
                entity.getPlanGoal(),
                entity.getPlanVersion() == null ? 1 : entity.getPlanVersion(),
                entity.getPlanMarkdown(),
                readJson(entity.getPlanRows(), ROWS, List.of()),
                readJson(entity.getPlanRevisions(), STRINGS, List.of()));
    }

    private <T> T readJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            log.warn("无法解析计划表 JSON: {}", json, exception);
            return fallback;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            log.warn("无法序列化计划表内容", exception);
            return null;
        }
    }
}
