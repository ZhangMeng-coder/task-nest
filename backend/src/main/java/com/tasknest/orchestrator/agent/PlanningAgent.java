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

/** 规划会话中的模型循环：只读工具 + ask_user，最终产出两层计划。 */
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
