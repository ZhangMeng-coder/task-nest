package com.tasknest.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.api.dto.PlanDraftInput;
import com.tasknest.api.dto.PlanDraftView;
import com.tasknest.api.dto.PlanRowView;
import com.tasknest.api.dto.RevisePlanRequest;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.orchestrator.MockEvent;
import com.tasknest.orchestrator.MockScript;
import com.tasknest.orchestrator.MockScriptLoader;
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
 * 计划表生成、提示词修订与持久化。
 *
 * <p>P3 的计划生成会交给真实模型；当前阶段按 Mock 脚本的 plan 事件生成确定性的
 * Markdown 计划，并允许用自然语言提示做增删步骤，保证原型交互可完整演算。</p>
 */
@Service
public class PlanDraftService {

    private static final Logger log = LoggerFactory.getLogger(PlanDraftService.class);
    private static final TypeReference<List<PlanRowView>> ROWS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final int MAX_INSTRUCTION_PREVIEW = 18;

    private final MockScriptLoader scriptLoader;
    private final MainTaskPlanEntityService planService;
    private final MainTaskEntityService mainTaskService;
    private final ObjectMapper objectMapper;

    public PlanDraftService(MockScriptLoader scriptLoader,
                            MainTaskPlanEntityService planService,
                            MainTaskEntityService mainTaskService,
                            ObjectMapper objectMapper) {
        this.scriptLoader = scriptLoader;
        this.planService = planService;
        this.mainTaskService = mainTaskService;
        this.objectMapper = objectMapper;
    }

    public PlanDraftView generate(String mockScript, String goal) {
        assertAllowed(mockScript);
        MockScript script = scriptLoader.load(mockScript);
        EventPayload.Plan plan = extractPlan(script);
        EventPayload.PlannedTask root = rootOf(plan);
        String effectiveGoal = resolveGoal(script, goal);
        List<PlanRowView> rows = rowsFrom(plan, root);
        return buildDraft(null, root.title(), effectiveGoal, mockScript, rows, List.of());
    }

    public PlanDraftView fromInput(String mockScript, String goal, PlanDraftInput input) {
        assertAllowed(mockScript);
        MockScript script = scriptLoader.load(mockScript);
        String effectiveGoal = resolveGoal(script, goal);
        List<String> revisions = input.revisions() == null ? List.of() : List.copyOf(input.revisions());
        return buildDraft(null, input.title().trim(), effectiveGoal, mockScript,
                List.copyOf(input.rows()), revisions);
    }

    public PlanDraftView revise(RevisePlanRequest request) {
        assertAllowed(request.mockScript());
        scriptLoader.load(request.mockScript());
        return applyInstruction(
                request.title(),
                request.goal(),
                request.rows(),
                request.revisions(),
                request.instruction(),
                request.mockScript());
    }

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
        entity.setPlanScript(draft.mockScript());
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
                instruction,
                current.mockScript());
        return save(mainTaskId, revised);
    }

    private PlanDraftView applyInstruction(String title,
                                           String goal,
                                           List<PlanRowView> rows,
                                           List<String> revisions,
                                           String instruction,
                                           String mockScript) {
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
        return buildDraft(null, title, goal, mockScript, nextRows, nextRevisions);
    }

    private PlanDraftView buildDraft(String mainTaskId,
                                     String title,
                                     String goal,
                                     String mockScript,
                                     List<PlanRowView> rows,
                                     List<String> revisions) {
        int version = revisions.size() + 1;
        String markdown = buildMarkdown(title, goal, rows, revisions);
        return new PlanDraftView(
                mainTaskId,
                title,
                goal,
                mockScript,
                version,
                markdown,
                List.copyOf(rows),
                List.copyOf(revisions));
    }

    private List<PlanRowView> rowsFrom(EventPayload.Plan plan, EventPayload.PlannedTask root) {
        List<EventPayload.PlannedTask> children = plan.tasks().stream()
                .filter(task -> root.taskId().equals(task.parentTaskId()))
                .toList();
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < children.size(); index++) {
            indexes.put(children.get(index).taskId(), index + 1);
        }
        List<PlanRowView> rows = new ArrayList<>(children.size());
        for (EventPayload.PlannedTask child : children) {
            rows.add(new PlanRowView(
                    child.title(),
                    child.agentName(),
                    dependencyOf(child.dependsOn(), indexes),
                    outputOf(child.title())));
        }
        return rows;
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

    private String outputOf(String title) {
        if (matches(title, "收集", "检索", "调研", "采集", "核对")) {
            return "事实与来源";
        }
        if (matches(title, "风险", "复核", "审核", "检查")) {
            return "复核结论";
        }
        if (matches(title, "撰写", "正文", "报告", "周报", "草稿")) {
            return "初稿";
        }
        if (matches(title, "计划", "方案", "设计", "起草")) {
            return "可执行方案";
        }
        if (matches(title, "汇总", "归类", "分析", "评估")) {
            return "结论与清单";
        }
        return "可复核结果";
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

    private void assertAllowed(String mockScript) {
        if (mockScript == null || !scriptLoader.isAllowed(mockScript)) {
            throw new BusinessException(ErrorCode.INVALID_SCRIPT,
                    "mockScript 必须是 " + String.join(" / ", scriptLoader.allowedScripts()));
        }
    }

    private EventPayload.Plan extractPlan(MockScript script) {
        for (MockEvent event : script.events()) {
            if (event.type() == EventType.PLAN && event.payload() instanceof EventPayload.Plan plan) {
                return plan;
            }
        }
        throw new BusinessException(ErrorCode.INVALID_MOCK_SCRIPT, "脚本没有可用的 plan 事件");
    }

    private EventPayload.PlannedTask rootOf(EventPayload.Plan plan) {
        return plan.tasks().stream()
                .filter(task -> task.parentTaskId() == null)
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_MOCK_SCRIPT, "脚本计划缺少主任务"));
    }

    private String resolveGoal(MockScript script, String goal) {
        if (goal != null && !goal.isBlank()) {
            return goal.trim();
        }
        for (MockEvent event : script.events()) {
            if (event.type() == EventType.RUN_STARTED
                    && event.payload() instanceof EventPayload.RunStarted started) {
                return started.goal();
            }
        }
        return "未填写目标";
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
                entity.getPlanScript(),
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
