package com.tasknest.orchestrator.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.event.ToolStatus;
import com.tasknest.orchestrator.llm.LlmClient;
import com.tasknest.orchestrator.tool.ToolException;
import com.tasknest.orchestrator.tool.ToolExecutor;
import com.tasknest.orchestrator.tool.ToolRegistry;
import com.tasknest.store.UserInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 通用工具调用循环：模型输出 -> tool_call 事件 -> 执行 -> tool_result -> 继续，
 * 直到模型不再请求工具。ask_user 转化为 waiting_input / input_received。
 *
 * <p>规划、子任务执行、审核与重排共用本循环，差异只由 {@link AgentSpec} 的模式、
 * 提示词与允许工具表达。循环开始时发布 prompt_assembled，每轮把模型文本作为
 * task_thought 事件追加；工具调用按 callId 与 tool_result 配对，执行失败也转为
 * ERROR 结果回传模型，让模型有机会改用安全方案而不是直接终止运行。
 * 超过 {@code maxIterations} 抛 {@code AGENT_MAX_ITERATIONS}；会话取消时返回 cancelled 结果。
 */
public class AgentLoop {

    /** 单条工具结果回传给模型前的最大字符数，超出部分截断。 */
    private static final int MAX_TOOL_MESSAGE_CHARS = 300_000;

    private final LlmClient llm;
    private final ToolExecutor tools;
    private final ObjectMapper objectMapper;

    public AgentLoop(LlmClient llm, ToolExecutor tools, ObjectMapper objectMapper) {
        this.llm = llm;
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行完整循环并返回终止结果。
     *
     * <p>会发布 prompt_assembled、task_thought、tool_call / tool_result 事件；
     * 模型在某一轮不再请求工具时结束，返回最后一次非空文本。会话取消时提前返回
     * {@code cancelled=true}，不再调用模型或工具。
     *
     * @throws AgentException 超过 {@code spec.maxIterations()} 仍未结束（AGENT_MAX_ITERATIONS）
     */
    public AgentOutcome run(AgentSession session, TaskRef task, AgentSpec spec) {
        List<LlmClient.LlmMessage> messages = new ArrayList<>();
        messages.add(LlmClient.LlmMessage.system(spec.systemPrompt()));
        messages.add(LlmClient.LlmMessage.user(spec.userPrompt()));
        session.publish(EventType.PROMPT_ASSEMBLED, task,
                new EventPayload.PromptAssembled(
                        spec.systemPrompt(),
                        spec.userPrompt(),
                        List.of(),
                        spec.systemPrompt() + "\n\n" + spec.userPrompt()));

        List<Map<String, Object>> toolDefinitions = ToolRegistry.definitions(
                spec.mode(), spec.allowedTools());
        int toolCallCount = 0;
        String lastContent = "";
        // 迭代上限兜底模型反复调用工具；耗尽即失败，避免运行无限占用线程与工作区。
        for (int iteration = 1; iteration <= spec.maxIterations(); iteration++) {
            if (session.cancelled()) {
                return new AgentOutcome(lastContent, toolCallCount, true, iteration - 1);
            }
            LlmClient.LlmResponse response = llm.chat(messages, toolDefinitions);
            if (response.content() != null && !response.content().isBlank()) {
                lastContent = response.content();
                session.publish(EventType.TASK_THOUGHT, task,
                        new EventPayload.TaskThought(response.content()));
            }
            List<LlmClient.LlmToolCall> calls = normalizeCalls(response.toolCalls());
            if (calls.isEmpty()) {
                return new AgentOutcome(lastContent, toolCallCount, false, iteration);
            }
            // assistant 消息携带 tool_calls，随后每条 tool 结果按 callId 与之一一配对。
            messages.add(LlmClient.LlmMessage.assistant(response.content(), calls));
            for (LlmClient.LlmToolCall call : calls) {
                if (session.cancelled()) {
                    return new AgentOutcome(lastContent, toolCallCount, true, iteration);
                }
                ToolInvocation invocation = invoke(session, task, spec, call);
                toolCallCount++;
                if (invocation.cancelled()) {
                    return new AgentOutcome(lastContent, toolCallCount, true, iteration);
                }
                messages.add(LlmClient.LlmMessage.tool(call.id(), invocation.content()));
            }
        }
        throw new AgentException("AGENT_MAX_ITERATIONS",
                "Agent 在 " + spec.maxIterations() + " 轮内未结束");
    }

    private ToolInvocation invoke(AgentSession session, TaskRef task, AgentSpec spec,
                                  LlmClient.LlmToolCall call) {
        String toolName = ToolRegistry.canonical(call.name());
        Map<String, Object> arguments = parseArguments(call.arguments());
        session.publish(EventType.TOOL_CALL, task,
                new EventPayload.ToolCall(call.id(), toolName, arguments));
        if (ToolRegistry.isConversational(toolName)) {
            return askUser(session, task, call.id(), arguments);
        }
        if (!allowed(toolName, spec)) {
            Map<String, Object> error = errorResult("TOOL_NOT_ALLOWED",
                    "当前 Agent 不允许使用工具: " + toolName, null);
            publishResult(session, task, call.id(), toolName, ToolStatus.ERROR, error, 0);
            return new ToolInvocation(serialize(error), false);
        }
        long started = System.nanoTime();
        try {
            Object result = tools.execute(toolName, arguments, spec.mode());
            long duration = elapsedMs(started);
            publishResult(session, task, call.id(), toolName, ToolStatus.SUCCESS, result, duration);
            return new ToolInvocation(serialize(result), false);
        } catch (ToolException exception) {
            Map<String, Object> error = errorResult(
                    exception.code(), exception.getMessage(), exception.rule());
            publishResult(session, task, call.id(), toolName, ToolStatus.ERROR, error,
                    elapsedMs(started));
            return new ToolInvocation(serialize(error), false);
        } catch (RuntimeException exception) {
            Map<String, Object> error = errorResult(
                    "TOOL_INTERNAL_ERROR", "工具执行异常: " + exception.getMessage(), null);
            publishResult(session, task, call.id(), toolName, ToolStatus.ERROR, error,
                    elapsedMs(started));
            return new ToolInvocation(serialize(error), false);
        }
    }

    private ToolInvocation askUser(AgentSession session, TaskRef task, String callId,
                                   Map<String, Object> arguments) {
        String question = stringValue(arguments.get("question"));
        if (question == null || question.isBlank()) {
            Map<String, Object> error = errorResult(
                    "INVALID_ARGUMENT", "ask_user 缺少 question", null);
            publishResult(session, task, callId, ToolRegistry.ASK_USER,
                    ToolStatus.ERROR, error, 0);
            return new ToolInvocation(serialize(error), false);
        }
        List<EventPayload.DecisionOption> options = options(arguments.get("options"));
        boolean allowCustom = boolValue(arguments.get("allowCustom"), true);
        String placeholder = stringValue(arguments.get("customPlaceholder"));
        if (options.isEmpty() && !allowCustom) {
            Map<String, Object> error = errorResult("INVALID_ARGUMENT",
                    "ask_user 至少需要一个选项或允许自由输入", null);
            publishResult(session, task, callId, ToolRegistry.ASK_USER,
                    ToolStatus.ERROR, error, 0);
            return new ToolInvocation(serialize(error), false);
        }
        UserInput input = session.awaitInput(task,
                new EventPayload.WaitingInput(question, options, allowCustom, placeholder));
        if (input == null) {
            return new ToolInvocation(serialize(errorResult(
                    "RUN_ENDED", "运行已结束，未收到用户输入", null)), true);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("selectedOptionId", input.selectedOptionId());
        result.put("selectedOptionLabel", labelOf(options, input.selectedOptionId()));
        result.put("customInput", input.customInput());
        return new ToolInvocation(serialize(result), false);
    }

    private boolean allowed(String toolName, AgentSpec spec) {
        if (ToolRegistry.ASK_USER.equals(toolName)) {
            return true;
        }
        Set<String> modeTools = switch (spec.mode()) {
            case PLANNING -> ToolRegistry.PLANNING_TOOLS;
            case REVIEW -> ToolRegistry.REVIEW_TOOLS;
            case EXECUTION -> ToolRegistry.EXECUTION_TOOLS;
        };
        if (!modeTools.contains(toolName)) {
            return false;
        }
        return spec.allowedTools().isEmpty() || spec.allowedTools().stream()
                .map(ToolRegistry::canonical)
                .anyMatch(toolName::equals);
    }

    private List<LlmClient.LlmToolCall> normalizeCalls(List<LlmClient.LlmToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        List<LlmClient.LlmToolCall> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // 兼容服务可能缺失或重复 call id；补齐唯一 id，保证 tool_call / tool_result 可稳定配对。
        int index = 1;
        for (LlmClient.LlmToolCall call : calls) {
            String id = call.id();
            if (id == null || id.isBlank() || !seen.add(id)) {
                do {
                    index++;
                    id = "call_" + index;
                } while (seen.contains(id));
                seen.add(id);
            }
            normalized.add(new LlmClient.LlmToolCall(id, call.name(), call.arguments()));
            index++;
        }
        return normalized;
    }

    private Map<String, Object> parseArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    rawArguments, new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception exception) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("_raw", rawArguments);
            fallback.put("_parseError", "参数不是合法 JSON");
            return fallback;
        }
    }

    private List<EventPayload.DecisionOption> options(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<EventPayload.DecisionOption> options = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String id = stringValue(map.get("id"));
                String label = stringValue(map.get("label"));
                String description = stringValue(map.get("description"));
                if (id != null && !id.isBlank() && label != null && !label.isBlank()) {
                    options.add(new EventPayload.DecisionOption(id, label, description));
                }
            }
            if (options.size() >= 8) {
                break;
            }
        }
        return options;
    }

    private String labelOf(List<EventPayload.DecisionOption> options, String optionId) {
        if (optionId == null) {
            return null;
        }
        return options.stream()
                .filter(option -> option.id().equals(optionId))
                .map(EventPayload.DecisionOption::label)
                .findFirst()
                .orElse(null);
    }

    private void publishResult(AgentSession session, TaskRef task, String callId, String toolName,
                               ToolStatus status, Object result, long durationMs) {
        session.publish(EventType.TOOL_RESULT, task,
                new EventPayload.ToolResult(callId, toolName, status, result, durationMs));
    }

    private Map<String, Object> errorResult(String code, String message, String rule) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (rule != null) {
            error.put("rule", rule);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", error);
        return result;
    }

    private String serialize(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            // 读取大文件等结果可能远超上下文预算，截断后再写回模型消息。
            if (json.length() > MAX_TOOL_MESSAGE_CHARS) {
                return json.substring(0, MAX_TOOL_MESSAGE_CHARS)
                        + "\n…[tool result truncated for model]";
            }
            return json;
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean boolValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return "true".equalsIgnoreCase(text);
        }
        return fallback;
    }

    private record ToolInvocation(String content, boolean cancelled) {
    }
}
