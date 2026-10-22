package com.tasknest.orchestrator.agent;

import java.util.List;

/**
 * 一次 Agent 循环的输入。allowedTools 为空表示使用当前模式允许的工具全集。
 *
 * <p>由各 Agent 构造后交给 {@link AgentLoop}。紧凑构造器负责归一化：
 * 非正的 {@code maxIterations} 回退到 40，{@code allowedTools} 复制为不可变列表，
 * 空 {@code mode} 回退到 {@link AgentMode#EXECUTION}。
 * 迭代上限用于兜底模型反复调用工具的情况，耗尽后由循环抛错而非无限运行。
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

    /** 执行模式规格，默认 40 轮上限；allowedTools 为空表示执行工具全集。 */
    public static AgentSpec execution(String systemPrompt, String userPrompt,
                                      List<String> allowedTools) {
        return new AgentSpec(systemPrompt, userPrompt, allowedTools,
                AgentMode.EXECUTION, 40);
    }

    /** 规划模式规格，30 轮上限；可用工具由规划模式固定为只读集合。 */
    public static AgentSpec planning(String systemPrompt, String userPrompt) {
        return new AgentSpec(systemPrompt, userPrompt, List.of(),
                AgentMode.PLANNING, 30);
    }

    /** 审核模式规格，30 轮上限；可用工具为只读文件与受限测试 Shell。 */
    public static AgentSpec review(String systemPrompt, String userPrompt) {
        return new AgentSpec(systemPrompt, userPrompt, List.of(),
                AgentMode.REVIEW, 30);
    }

    /** 是否为审核模式；调用方可据此选择更严格的输出解析与结果校验。 */
    public boolean reviewMode() {
        return mode == AgentMode.REVIEW;
    }
}
