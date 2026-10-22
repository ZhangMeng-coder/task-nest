package com.tasknest.orchestrator.agent;

/**
 * 一次 {@link AgentLoop#run} 的终止结果。
 *
 * <p>循环层返回值：{@code content} 是模型最后一次非空文本回复，可能为空串；
 * {@code toolCalls} 统计已处理（含失败）的工具调用数；
 * {@code cancelled} 为 true 时表示运行或规划会话已终止，调用方应放弃本次结果；
 * {@code iterations} 是实际消耗的模型轮次，取消发生在首轮之前时为 0。
 */
public record AgentOutcome(
        String content,
        int toolCalls,
        boolean cancelled,
        int iterations
) {
}
