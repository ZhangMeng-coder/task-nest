package com.tasknest.event;

import java.util.List;
import java.util.Map;

/**
 * 事件 payload 的类型化模型。密封层次把 {@link EventType} 的 17 种事件载荷
 * 限制在已知形状内，线上由统一的 Jackson 序列化输出；坏数据只会在解析边界失败。
 *
 * <p>{@link Empty} 用于没有业务载荷的事件，由 {@code TASK_STARTED} 与 {@code HEARTBEAT}
 * 共用；其余多数事件有独立 record，{@code PlannedTask} / {@code ContextSnippet} 等嵌套
 * record 只是载荷的一部分。新增字段时必须同步前端 {@code api/events.ts} 与
 * {@code docs/events.md}。</p>
 */
public sealed interface EventPayload {

    record Empty() implements EventPayload {
    }

    record RunStarted(String goal) implements EventPayload {
    }

    /**
     * 计划载荷。规划会话中 revision 从 1 开始；执行期动态重排时递增，并携带
     * reason / changedTaskIds。
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
     * 计划中的单个任务。P3 规划器给出 description 与 acceptanceCriteria；
     * allowedTools 缺省表示执行器的全部工具。
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

    /** 增量思考文本；前端按追加方式拼接，重试时保留历史。 */
    record TaskThought(String delta) implements EventPayload {
    }

    /**
     * 一次 Agent 调用的提示词快照。重试或重做时可能多次出现，
     * 前端保留历史并允许切换查看。
     */
    record PromptAssembled(
            String systemPrompt,
            String userPrompt,
            List<ContextSnippet> contextSnippets,
            String finalPrompt
    ) implements EventPayload {
    }

    record ContextSnippet(String source, String content) {
    }

    /**
     * 工具调用请求。与 {@link ToolResult} 按 {@code callId} 配对；
     * 缺失 callId 时前后端都按工具名取最近一条未匹配调用（LIFO）。
     */
    record ToolCall(
            String callId,
            String toolName,
            Map<String, Object> arguments
    ) implements EventPayload {
    }

    /**
     * 工具执行结果；{@code status} 使用线上的 {@code success} / {@code error}。
     */
    record ToolResult(
            String callId,
            String toolName,
            ToolStatus status,
            Object result,
            long durationMs
    ) implements EventPayload {
    }

    /**
     * 等待人抉择的问题。{@code allowCustom} 为 {@code null} 时按不允许自由输入处理。
     */
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

    /**
     * 子任务失败。{@code retryable=true} 只把子任务置为 {@code RETRYING}，
     * 不是运行终止；{@code false} 才是子任务终态。
     */
    record TaskFailed(String error, boolean retryable) implements EventPayload {
    }

    record ReviewStarted(int attempt) implements EventPayload {
    }

    /**
     * 审核结论。{@code verdict} 为 {@code PASS} 或 {@code REWORK}；
     * REWORK 会把子任务置为 {@code RETRYING}，PASS 由后续 task_completed 收尾。
     */
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

    /**
     * Git 时间线记录（分支、提交、合并、回滚）。只用于展示，不改变任务状态。
     */
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

    /**
     * 终止错误；{@code code} 为业务错误码字符串，{@code message} 面向用户展示。
     */
    record Error(String code, String message) implements EventPayload {
    }
}
