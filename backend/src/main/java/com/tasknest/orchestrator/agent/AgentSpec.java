package com.tasknest.orchestrator.agent;

import java.util.List;

/**
 * 一次 Agent 循环的输入。allowedTools 为空表示使用当前模式允许的工具全集。
 */
public record AgentSpec(
        String systemPrompt,
        String userPrompt,
        List<String> allowedTools,
        AgentMode mode,
        int maxIterations
) {
    public AgentSpec {
        maxIterations = maxIterations <= 0 ? 40 : maxIterations;
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        mode = mode == null ? AgentMode.EXECUTION : mode;
    }

    public static AgentSpec execution(String systemPrompt, String userPrompt,
                                      List<String> allowedTools) {
        return new AgentSpec(systemPrompt, userPrompt, allowedTools,
                AgentMode.EXECUTION, 40);
    }

    public static AgentSpec planning(String systemPrompt, String userPrompt) {
        return new AgentSpec(systemPrompt, userPrompt, List.of(),
                AgentMode.PLANNING, 30);
    }

    public static AgentSpec review(String systemPrompt, String userPrompt) {
        return new AgentSpec(systemPrompt, userPrompt, List.of(),
                AgentMode.REVIEW, 30);
    }

    public boolean reviewMode() {
        return mode == AgentMode.REVIEW;
    }
}
