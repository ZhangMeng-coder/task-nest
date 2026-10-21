package com.tasknest.event;

import java.util.List;
import java.util.Map;

/**
 * 事件 payload 的类型化模型。密封层次把 17 种事件的载荷限制在已知形状内，
 * 线上由统一的 Jackson 序列化输出。
 */
public sealed interface EventPayload {

    record Empty() implements EventPayload {
    }

    record RunStarted(String goal) implements EventPayload {
    }

    /**
     * 计划载荷。规划会话中 revision 从 1 开始；执行期动态重排时递增，并携带
     * reason / changedTaskIds。旧 Mock 脚本只提供 tasks，其余字段为 null。
     */
    record Plan(
            List<PlannedTask> tasks,
            Integer revision,
            String reason,
            List<String> changedTaskIds
    ) implements EventPayload {
        public Plan(List<PlannedTask> tasks) {
            this(tasks, null, null, null);
        }
    }

    /**
     * 计划中的单个任务。P3 规划器必须给出 description 与 acceptanceCriteria；
     * 旧 Mock 脚本可以省略。allowedTools 缺省表示执行器的全部工具。
     */
    record PlannedTask(
            String taskId,
            String parentTaskId,
            String title,
            String agentName,
            List<String> dependsOn,
            String description,
            List<String> acceptanceCriteria,
            List<String> allowedTools,
            String testCommand
    ) {
        public PlannedTask(String taskId,
                           String parentTaskId,
                           String title,
                           String agentName,
                           List<String> dependsOn) {
            this(taskId, parentTaskId, title, agentName, dependsOn, null, null, null, null);
        }
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
            List<DecisionOption> options,
            Boolean allowCustom,
            String customPlaceholder
    ) implements EventPayload {
        public WaitingInput(String question, List<DecisionOption> options) {
            this(question, options, null, null);
        }

        public boolean customAllowed() {
            return Boolean.TRUE.equals(allowCustom);
        }
    }

    record DecisionOption(String id, String label, String description) {
    }

    /** 至少一个字段非空；P3 允许同时提交选项与自由输入。 */
    record InputReceived(String selectedOptionId, String customInput) implements EventPayload {
        public InputReceived(String selectedOptionId) {
            this(selectedOptionId, null);
        }
    }

    record TaskCompleted(String summary) implements EventPayload {
    }

    record TaskFailed(String error, boolean retryable) implements EventPayload {
    }

    record ReviewStarted(int attempt) implements EventPayload {
    }

    record ReviewCompleted(
            int attempt,
            String verdict,
            String summary,
            List<ReviewIssue> issues,
            String testStatus,
            List<TestEvidence> testEvidence,
            String modelName,
            long durationMs
    ) implements EventPayload {
    }

    record ReviewIssue(
            String severity,
            String file,
            Integer line,
            String message,
            String suggestion
    ) {
    }

    record TestEvidence(
            String command,
            Integer exitCode,
            boolean timedOut,
            String summary
    ) {
    }

    record GitCheckpoint(
            String action,
            String branch,
            String targetBranch,
            String commit,
            Integer attempt,
            boolean noChanges,
            String message
    ) implements EventPayload {
    }

    record RunCompleted(String summary) implements EventPayload {
    }

    record Error(String code, String message) implements EventPayload {
    }
}
