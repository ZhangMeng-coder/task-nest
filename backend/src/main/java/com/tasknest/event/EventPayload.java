package com.tasknest.event;

import java.util.List;
import java.util.Map;

/**
 * 事件 payload 的类型化模型。密封层次把 14 种事件的载荷限制在已知形状内，
 * 线上由统一的 EventSerializer 输出。
 */
public sealed interface EventPayload {

    record Empty() implements EventPayload {
    }

    record RunStarted(String goal) implements EventPayload {
    }

    record Plan(List<PlannedTask> tasks) implements EventPayload {
    }

    record PlannedTask(
            String taskId,
            String parentTaskId,
            String title,
            String agentName,
            List<String> dependsOn
    ) {
    }

    record TaskThought(String delta) implements EventPayload {
    }

    record PromptAssembled(
            String systemPrompt,
            String userPrompt,
            List<ContextSnippet> contextSnippets,
            String finalPrompt
    ) implements EventPayload {
    }

    record ContextSnippet(String source, String content) {
    }

    record ToolCall(
            String callId,
            String toolName,
            Map<String, Object> arguments
    ) implements EventPayload {
    }

    record ToolResult(
            String callId,
            String toolName,
            ToolStatus status,
            Object result,
            long durationMs
    ) implements EventPayload {
    }

    record WaitingInput(
            String question,
            List<DecisionOption> options
    ) implements EventPayload {
    }

    record DecisionOption(String id, String label, String description) {
    }

    record InputReceived(String selectedOptionId) implements EventPayload {
    }

    record TaskCompleted(String summary) implements EventPayload {
    }

    record TaskFailed(String error, boolean retryable) implements EventPayload {
    }

    record RunCompleted(String summary) implements EventPayload {
    }

    record Error(String code, String message) implements EventPayload {
    }
}
