package com.tasknest.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.event.EventPayload;
import com.tasknest.event.TaskRef;
import com.tasknest.orchestrator.llm.LlmClient;
import com.tasknest.orchestrator.tool.ToolExecutor;
import com.tasknest.orchestrator.tool.WorkspaceSandbox;
import com.tasknest.service.SecretProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** 子任务执行 Agent：在子任务分支上使用受限工具完成实现并运行验证。 */
@Component
public class SubAgent {

    private final LlmClient llm;
    private final ObjectMapper objectMapper;
    private final SecretProvider secrets;

    public SubAgent(LlmClient llm, ObjectMapper objectMapper, SecretProvider secrets) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.secrets = secrets;
    }

    public AgentOutcome run(AgentSession session,
                            TaskRef taskRef,
                            Path root,
                            String goal,
                            String rootTitle,
                            EventPayload.PlannedTask task,
                            String previousSummary,
                            String reviewFeedback) {
        ToolExecutor executor = new ToolExecutor(new WorkspaceSandbox(root), secrets::values);
        AgentLoop loop = new AgentLoop(llm, executor, objectMapper);
        List<String> allowedTools = task.allowedTools() == null || task.allowedTools().isEmpty()
                ? List.of() : task.allowedTools();
        AgentSpec spec = AgentSpec.execution(
                PromptFactory.subtaskSystem(root.toString(), goal, rootTitle, task),
                PromptFactory.subtaskUser(previousSummary, reviewFeedback),
                allowedTools);
        return loop.run(session, taskRef, spec);
    }
}
