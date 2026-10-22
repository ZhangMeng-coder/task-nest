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

/**
 * 子任务执行 Agent：在子任务分支上使用受限工具完成实现并运行验证。
 *
 * <p>由编排器在检出子任务分支后调用。每次尝试都新建 {@link WorkspaceSandbox} 与
 * {@link ToolExecutor}，工具集合取计划中的 allowedTools（为空时沿用执行模式全集）。
 * 需求含糊时模型通过 ask_user 挂起等待；重试时会带上一次摘要与审核退回意见。
 */
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

    /**
     * 运行一次子任务执行循环。
     *
     * <p>返回 {@link AgentOutcome}；取消或超过迭代上限的语义由 {@link AgentLoop} 决定，
     * 本方法不做额外判定。文件写入与 Shell 执行仍在沙箱和命令策略内。
     *
     * @param previousSummary 上一次尝试的完成摘要，首次执行时为 null
     * @param reviewFeedback  审核退回意见，非重做时为 null
     */
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
