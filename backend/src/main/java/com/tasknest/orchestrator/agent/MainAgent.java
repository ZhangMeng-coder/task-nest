package com.tasknest.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tasknest.event.EventPayload;
import com.tasknest.event.TaskRef;
import com.tasknest.orchestrator.llm.LlmClient;
import com.tasknest.orchestrator.llm.LlmException;
import com.tasknest.orchestrator.tool.ToolExecutor;
import com.tasknest.orchestrator.tool.WorkspaceSandbox;
import com.tasknest.service.SecretProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 主 Agent：在两个子任务之间评估是否重排，并在全部子任务完成后汇总。
 * 重排使用只读规划模式；汇总不调用任何工具。
 *
 * <p>由编排器在调度窗口调用。重排输出必须是严格 JSON（continue / replan），
 * 新计划复用 {@link PlanParser} 的两层、工具与依赖校验，并允许引用未出现在
 * 本次 JSON 中的既有任务 ID。汇总失败时退回确定性文本，不阻断已完成的合并。
 */
@Component
public class MainAgent {

    private static final Logger log = LoggerFactory.getLogger(MainAgent.class);

    private final LlmClient llm;
    private final ObjectMapper objectMapper;
    private final SecretProvider secrets;

    public MainAgent(LlmClient llm, ObjectMapper objectMapper, SecretProvider secrets) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.secrets = secrets;
    }

    /**
     * 在重排窗口评估是否需要调整剩余计划。
     *
     * <p>以 {@link AgentMode#PLANNING} 运行，可读仓库并询问用户，但不能修改文件。
     * action=continue 返回 keep；action=replan 时把模型给出的完整任务数组重新交给
     * {@link PlanParser} 校验并生成新草案。运行取消返回 {@link ReplanDecision#abort()}。
     *
     * @param lastResult 刚完成子任务的结果摘要
     * @param remaining  尚未开始任务的文本清单
     * @throws AgentException PLAN_INVALID_OUTPUT，缺少合法 action / tasks 或计划校验失败
     */
    public ReplanDecision considerReplan(AgentSession session,
                                         TaskRef rootTask,
                                         Path root,
                                         String goal,
                                         EventPayload.Plan plan,
                                         String lastResult,
                                         String remaining) {
        ToolExecutor executor = new ToolExecutor(new WorkspaceSandbox(root), secrets::values);
        AgentLoop loop = new AgentLoop(llm, executor, objectMapper);
        AgentSpec spec = AgentSpec.planning(
                PromptFactory.replanSystem(),
                PromptFactory.replanUser(goal, plan, lastResult, remaining));
        AgentOutcome outcome = loop.run(session, rootTask, spec);
        if (outcome.cancelled()) {
            return ReplanDecision.abort();
        }
        JsonNode node = readJson(outcome.content());
        String action = node.path("action").asText("").trim().toLowerCase();
        if ("continue".equals(action)) {
            return ReplanDecision.keep();
        }
        if (!"replan".equals(action)) {
            throw new AgentException("PLAN_INVALID_OUTPUT",
                    "重排输出缺少合法的 action（continue / replan）");
        }
        JsonNode tasks = node.path("tasks");
        if (!tasks.isArray() || tasks.isEmpty()) {
            throw new AgentException("PLAN_INVALID_OUTPUT", "重排输出缺少 tasks 数组");
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("tasks", tasks);
        java.util.Set<String> newIds = new java.util.LinkedHashSet<>();
        for (JsonNode task : tasks) {
            String id = task.path("taskId").asText("");
            if (!id.isBlank()) {
                newIds.add(id.trim());
            }
        }
        // 新计划未包含的既有任务作为 external 依赖，允许新任务继续引用已开始 / 已完成任务。
        java.util.Set<String> externalIds = new java.util.LinkedHashSet<>();
        for (EventPayload.PlannedTask task : plan.tasks()) {
            if (!newIds.contains(task.taskId())) {
                externalIds.add(task.taskId());
            }
        }
        PlanDraft draft;
        try {
            draft = PlanParser.parse(
                    objectMapper.writeValueAsString(wrapper), goal, objectMapper, externalIds);
        } catch (AgentException exception) {
            throw new AgentException("PLAN_INVALID_OUTPUT",
                    "重排计划不合法: " + exception.getMessage());
        } catch (Exception exception) {
            throw new AgentException("PLAN_INVALID_OUTPUT",
                    "重排计划无法解析: " + exception.getMessage(), exception);
        }
        String reason = node.path("reason").asText("执行期计划重排");
        return ReplanDecision.replan(reason, draft);
    }

    /**
     * 最终汇总；模型不可用时退回确定性摘要，不阻断已成功的合并。
     *
     * <p>只发起一次不带工具的模型调用。模型抛 {@link LlmException}、返回空文本或
     * 调用异常时，使用“全部子任务已完成并合并 + facts”作为兜底文案。
     */
    public String summarize(String goal, EventPayload.Plan plan, String facts) {
        try {
            LlmClient.LlmResponse response = llm.chat(
                    List.of(
                            LlmClient.LlmMessage.system(PromptFactory.summarySystem()),
                            LlmClient.LlmMessage.user(
                                    PromptFactory.summaryUser(goal, plan, facts))),
                    List.of());
            String content = response.content();
            if (content != null && !content.isBlank()) {
                return content.trim();
            }
        } catch (LlmException exception) {
            log.warn("最终汇总模型调用失败，使用确定性摘要: {}", exception.getMessage());
        }
        return "全部子任务已完成并合并。\n" + facts;
    }

    private JsonNode readJson(String content) {
        String text = content == null ? "" : content.trim();
        // 模型可能包裹解释文字或代码块，只截取最外层 JSON 对象。
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AgentException("PLAN_INVALID_OUTPUT", "重排输出不是 JSON 对象");
        }
        try {
            return objectMapper.readTree(text.substring(start, end + 1));
        } catch (Exception exception) {
            throw new AgentException("PLAN_INVALID_OUTPUT",
                    "重排输出 JSON 解析失败: " + exception.getMessage(), exception);
        }
    }
}
