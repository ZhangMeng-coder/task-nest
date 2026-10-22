package com.tasknest.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.event.TaskRef;
import com.tasknest.orchestrator.llm.LlmClient;
import com.tasknest.orchestrator.tool.ToolExecutor;
import com.tasknest.orchestrator.tool.WorkspaceSandbox;
import com.tasknest.service.PlanSessionState;
import com.tasknest.service.PlanSessionStore;
import com.tasknest.service.SecretProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 规划会话中的模型循环：只读工具 + ask_user，最终产出两层计划。
 *
 * <p>由规划会话服务在会话线程中调用。每次生成都为会话工作目录新建独立的
 * {@link WorkspaceSandbox} 与 {@link ToolExecutor}，以 {@link PlanAgentSession}
 * 作为事件出口；模型最终文本交给 {@link PlanParser} 做结构与依赖校验。
 * 规划模式不提供写文件与 Shell 工具，因此对仓库只读。
 */
@Component
public class PlanningAgent {

    public static final String PLAN_ROOT_ID = "plan_root";
    public static final TaskRef PLAN_ROOT_TASK =
            new TaskRef(PLAN_ROOT_ID, null, "规划", "planner");

    private final LlmClient llm;
    private final ObjectMapper objectMapper;
    private final SecretProvider secrets;
    private final PlanSessionStore store;

    public PlanningAgent(LlmClient llm,
                         ObjectMapper objectMapper,
                         SecretProvider secrets,
                         PlanSessionStore store) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.secrets = secrets;
        this.store = store;
    }

    /**
     * 生成新计划或按修订意见重新生成计划。
     *
     * <p>使用 {@link AgentMode#PLANNING}，事件写入规划会话 SSE。模型可通过 ask_user
     * 阻塞等待用户抉择；会话被取消时直接返回 {@code null}。
     *
     * @param revisionInstruction 非空时在 {@code state.planJson} 基础上修订，否则生成全新计划
     * @throws AgentException PLAN_INVALID_OUTPUT，模型输出无法解析或不满足两层计划约束时
     */
    public PlanDraft generate(PlanSessionState state, String revisionInstruction) {
        WorkspaceSandbox sandbox = new WorkspaceSandbox(Path.of(state.getWorkingDir()));
        ToolExecutor executor = new ToolExecutor(sandbox, secrets::values);
        AgentLoop loop = new AgentLoop(llm, executor, objectMapper);
        AgentSpec spec = AgentSpec.planning(
                PromptFactory.planningSystem(),
                PromptFactory.planningUser(
                        state.getGoal(),
                        state.getWorkingDir(),
                        state.getPlanJson(),
                        revisionInstruction,
                        state.getRevision()));
        AgentOutcome outcome = loop.run(
                new PlanAgentSession(store, state), PLAN_ROOT_TASK, spec);
        if (outcome.cancelled()) {
            return null;
        }
        return PlanParser.parse(outcome.content(), state.getGoal(), objectMapper);
    }
}
