package com.tasknest.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.config.TaskNestProperties;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.event.ToolStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从 classpath:mock/{name}.json 读取脚本，完成结构解析与跨事件校验。
 * 校验失败一律视为服务端内置资源问题（50002），不暴露为调用方参数错误。
 */
@Component
public class MockScriptLoader {

    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^evt_[A-Za-z0-9_]{1,64}$");
    private static final List<EventType> FORBIDDEN_IN_SCRIPT = List.of(EventType.INPUT_RECEIVED);

    private final ObjectMapper objectMapper;
    private final TaskNestProperties properties;

    public MockScriptLoader(ObjectMapper objectMapper, TaskNestProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean isAllowed(String name) {
        return properties.getMock().getScripts().contains(name);
    }

    public List<String> allowedScripts() {
        return List.copyOf(properties.getMock().getScripts());
    }

    public MockScript load(String name) {
        String location = "mock/" + name + ".json";
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new BusinessException(ErrorCode.INVALID_MOCK_SCRIPT, "内置脚本缺失: " + location);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            return validateAndBind(parseScript(name, root));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INVALID_MOCK_SCRIPT, "无法读取内置脚本: " + location);
        } catch (ScriptValidationException exception) {
            throw new BusinessException(ErrorCode.INVALID_MOCK_SCRIPT,
                    "内置脚本 " + name + " 无效: " + exception.getMessage());
        }
    }

    private MockScript parseScript(String name, JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new ScriptValidationException("根节点必须是对象");
        }
        JsonNode eventsNode = root.get("events");
        if (eventsNode == null || !eventsNode.isArray() || eventsNode.isEmpty()) {
            throw new ScriptValidationException("events 必须是非空数组");
        }
        String description = optionalText(root, "description");
        List<MockEvent> events = new ArrayList<>();
        for (JsonNode eventNode : eventsNode) {
            events.add(parseEvent(eventNode));
        }
        return new MockScript(name, description == null ? "" : description, events);
    }

    private MockEvent parseEvent(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new ScriptValidationException("events 中的每一项必须是对象");
        }
        String typeText = requiredText(node, "type");
        EventType type;
        try {
            type = EventType.fromWire(typeText);
        } catch (IllegalArgumentException exception) {
            throw new ScriptValidationException("未知事件类型: " + typeText);
        }
        if (FORBIDDEN_IN_SCRIPT.contains(type)) {
            throw new ScriptValidationException("脚本不允许定义 " + type.wireName() + " 事件");
        }
        long delayMs = number(node, "delayMs", 0, 0, 60_000, "delayMs");
        long dispatchDelayMs = number(node, "dispatchDelayMs", 0, 0, 1_000, "dispatchDelayMs");
        boolean dropDispatch = bool(node, "dropDispatch", false);

        String eventId = optionalText(node, "eventId");
        if (eventId != null && !EVENT_ID_PATTERN.matcher(eventId).matches()) {
            throw new ScriptValidationException("eventId 格式必须为 evt_[A-Za-z0-9_]{1,64}");
        }
        String taskId = optionalText(node, "taskId");
        TaskRef task = parseTaskRef(node.get("task"));

        JsonNode payloadNode = node.get("payload");
        if (payloadNode == null || !payloadNode.isObject()) {
            throw new ScriptValidationException(type.wireName() + " 的 payload 必须是对象");
        }
        EventPayload payload = parsePayload(type, payloadNode);

        Map<String, List<MockEvent>> branches = parseBranches(type, node.get("branches"));

        if (type.isTerminal() && (dispatchDelayMs > 0 || dropDispatch)) {
            throw new ScriptValidationException("终止事件不允许使用 dispatchDelayMs / dropDispatch");
        }
        if (type == EventType.WAITING_INPUT && dropDispatch) {
            throw new ScriptValidationException("waiting_input 不允许 dropDispatch");
        }

        return new MockEvent(delayMs, type, taskId, task, payload, eventId, dispatchDelayMs, dropDispatch, branches);
    }

    private Map<String, List<MockEvent>> parseBranches(EventType type, JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (type != EventType.WAITING_INPUT) {
            throw new ScriptValidationException("只有 waiting_input 可以定义 branches");
        }
        if (!node.isObject()) {
            throw new ScriptValidationException("branches 必须是对象");
        }
        Map<String, List<MockEvent>> branches = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().isBlank()) {
                throw new ScriptValidationException("branches 的选项 id 不能为空");
            }
            JsonNode eventsNode = entry.getValue();
            if (eventsNode == null || !eventsNode.isArray()) {
                throw new ScriptValidationException("branches[" + entry.getKey() + "] 必须是数组");
            }
            List<MockEvent> events = new ArrayList<>();
            for (JsonNode eventNode : eventsNode) {
                events.add(parseEvent(eventNode));
            }
            branches.put(entry.getKey(), events);
        }
        return branches;
    }

    private TaskRef parseTaskRef(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new ScriptValidationException("task 必须是对象");
        }
        return new TaskRef(
                requiredText(node, "taskId"),
                optionalText(node, "parentTaskId"),
                requiredText(node, "title"),
                requiredText(node, "agentName"));
    }

    private EventPayload parsePayload(EventType type, JsonNode payload) {
        return switch (type) {
            case RUN_STARTED -> new EventPayload.RunStarted(requiredText(payload, "goal"));
            case PLAN -> new EventPayload.Plan(parsePlannedTasks(payload.get("tasks")));
            case TASK_STARTED -> new EventPayload.Empty();
            case TASK_THOUGHT -> new EventPayload.TaskThought(requiredText(payload, "delta"));
            case PROMPT_ASSEMBLED -> new EventPayload.PromptAssembled(
                    text(payload, "systemPrompt", true),
                    text(payload, "userPrompt", true),
                    parseSnippets(payload.get("contextSnippets")),
                    requiredText(payload, "finalPrompt"));
            case TOOL_CALL -> new EventPayload.ToolCall(
                    optionalText(payload, "callId"),
                    requiredText(payload, "toolName"),
                    parseObject(payload.get("arguments"), "arguments"));
            case TOOL_RESULT -> new EventPayload.ToolResult(
                    optionalText(payload, "callId"),
                    requiredText(payload, "toolName"),
                    parseToolStatus(payload.get("status")),
                    parseAny(payload, "result"),
                    number(payload, "durationMs", 0, 0, 86_400_000, "durationMs"));
            case WAITING_INPUT -> new EventPayload.WaitingInput(
                    requiredText(payload, "question"),
                    parseOptions(payload.get("options")));
            case INPUT_RECEIVED -> throw new ScriptValidationException("脚本不允许定义 input_received 事件");
            case TASK_COMPLETED -> new EventPayload.TaskCompleted(text(payload, "summary", true));
            case TASK_FAILED -> new EventPayload.TaskFailed(
                    requiredText(payload, "error"),
                    requiredBoolean(payload, "retryable"));
            case RUN_COMPLETED -> new EventPayload.RunCompleted(text(payload, "summary", true));
            case ERROR -> new EventPayload.Error(
                    requiredText(payload, "code"),
                    requiredText(payload, "message"));
            case HEARTBEAT -> throw new ScriptValidationException("脚本不允许定义 heartbeat 事件");
        };
    }

    private List<EventPayload.PlannedTask> parsePlannedTasks(JsonNode node) {
        List<JsonNode> items = requiredArray(node, "tasks");
        if (items.isEmpty()) {
            throw new ScriptValidationException("plan.tasks 不能为空");
        }
        List<EventPayload.PlannedTask> tasks = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject()) {
                throw new ScriptValidationException("plan.tasks 中的每一项必须是对象");
            }
            tasks.add(new EventPayload.PlannedTask(
                    requiredText(item, "taskId"),
                    optionalText(item, "parentTaskId"),
                    requiredText(item, "title"),
                    requiredText(item, "agentName"),
                    parseStringArray(item.get("dependsOn"), "dependsOn")));
        }
        return tasks;
    }

    private List<EventPayload.DecisionOption> parseOptions(JsonNode node) {
        List<JsonNode> items = requiredArray(node, "options");
        if (items.size() < 2) {
            throw new ScriptValidationException("waiting_input.options 至少需要 2 个选项");
        }
        List<EventPayload.DecisionOption> options = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : items) {
            if (!item.isObject()) {
                throw new ScriptValidationException("options 中的每一项必须是对象");
            }
            String id = requiredText(item, "id");
            if (!ids.add(id)) {
                throw new ScriptValidationException("options 的 id 重复: " + id);
            }
            options.add(new EventPayload.DecisionOption(
                    id,
                    requiredText(item, "label"),
                    text(item, "description", true)));
        }
        return options;
    }

    private List<EventPayload.ContextSnippet> parseSnippets(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ScriptValidationException("contextSnippets 必须是数组");
        }
        List<EventPayload.ContextSnippet> snippets = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new ScriptValidationException("contextSnippets 中的每一项必须是对象");
            }
            snippets.add(new EventPayload.ContextSnippet(
                    requiredText(item, "source"),
                    text(item, "content", true)));
        }
        return snippets;
    }

    private Map<String, Object> parseObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw new ScriptValidationException(field + " 必须是对象");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = objectMapper.treeToValue(node, Map.class);
            return value;
        } catch (IOException exception) {
            throw new ScriptValidationException(field + " 无法解析为对象", exception);
        }
    }

    private Object parseAny(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null) {
            throw new ScriptValidationException("缺少字段 " + field);
        }
        if (node.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, Object.class);
        } catch (IOException exception) {
            throw new ScriptValidationException(field + " 无法解析", exception);
        }
    }

    private ToolStatus parseToolStatus(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ScriptValidationException("status 必须是字符串");
        }
        try {
            return ToolStatus.fromWire(node.asText());
        } catch (IllegalArgumentException exception) {
            throw new ScriptValidationException("status 必须是 success 或 error");
        }
    }

    private MockScript validateAndBind(MockScript raw) {
        List<MockEvent> events = raw.events();
        int planIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).type() == EventType.PLAN) {
                if (planIndex >= 0) {
                    throw new ScriptValidationException("脚本只能包含一个 plan 事件");
                }
                planIndex = i;
            }
        }
        if (planIndex < 0) {
            throw new ScriptValidationException("脚本必须包含一个 plan 事件");
        }
        for (int i = 0; i < planIndex; i++) {
            if (events.get(i).type() != EventType.RUN_STARTED) {
                throw new ScriptValidationException("plan 之前只允许 run_started");
            }
        }

        MockEvent planEvent = events.get(planIndex);
        if (!(planEvent.payload() instanceof EventPayload.Plan plan)) {
            throw new ScriptValidationException("plan 事件载荷无效");
        }
        Map<String, TaskRef> registry = buildRegistry(plan.tasks());
        TaskRef root = findRoot(registry);

        List<MockEvent> bound = new ArrayList<>();
        int runStartedCount = 0;
        for (int i = 0; i < events.size(); i++) {
            MockEvent event = events.get(i);
            if (event.type() == EventType.RUN_STARTED) {
                runStartedCount++;
            }
            if (i == events.size() - 1 && !event.type().isTerminal()) {
                throw new ScriptValidationException("脚本最后一个事件必须是 run_completed 或 error");
            }
            if (i < events.size() - 1 && event.type().isTerminal()) {
                throw new ScriptValidationException("终止事件之后不允许再有事件");
            }
            bound.add(bindEvent(event, registry, root, true));
        }
        if (runStartedCount > 1) {
            throw new ScriptValidationException("脚本最多只能包含一个 run_started 事件");
        }

        return new MockScript(raw.name(), raw.description(), List.copyOf(bound));
    }

    private MockEvent bindEvent(MockEvent event, Map<String, TaskRef> registry, TaskRef root, boolean topLevel) {
        EventType type = event.type();
        TaskRef task;
        if (type == EventType.RUN_STARTED || type.isTerminal()) {
            if (event.taskId() != null || event.task() != null) {
                throw new ScriptValidationException(type.wireName() + " 不允许携带任务信息");
            }
            task = null;
        } else if (type == EventType.PLAN) {
            if (!topLevel) {
                throw new ScriptValidationException("branches 中不允许定义 plan 事件");
            }
            if (event.taskId() != null && !event.taskId().equals(root.taskId())) {
                throw new ScriptValidationException("plan 的 taskId 必须等于根任务 " + root.taskId());
            }
            if (event.task() != null) {
                validateTaskRef(event.task(), root);
            }
            task = root;
        } else {
            if (event.taskId() == null) {
                throw new ScriptValidationException(type.wireName() + " 必须提供 taskId");
            }
            TaskRef registered = registry.get(event.taskId());
            if (registered == null) {
                throw new ScriptValidationException("未知 taskId: " + event.taskId());
            }
            if (event.task() != null) {
                validateTaskRef(event.task(), registered);
                task = event.task();
            } else {
                task = registered;
            }
        }

        Map<String, List<MockEvent>> branches = Map.of();
        if (type == EventType.WAITING_INPUT) {
            if (!(event.payload() instanceof EventPayload.WaitingInput payload)) {
                throw new ScriptValidationException("waiting_input 载荷无效");
            }
            if (!event.branches().isEmpty()) {
                Set<String> optionIds = new LinkedHashSet<>();
                for (EventPayload.DecisionOption option : payload.options()) {
                    optionIds.add(option.id());
                }
                if (!event.branches().keySet().equals(optionIds)) {
                    throw new ScriptValidationException("branches 的键必须与 waiting_input.options 的 id 一一对应");
                }
                Map<String, List<MockEvent>> boundBranches = new LinkedHashMap<>();
                for (Map.Entry<String, List<MockEvent>> entry : event.branches().entrySet()) {
                    List<MockEvent> boundBranch = new ArrayList<>();
                    for (MockEvent branchEvent : entry.getValue()) {
                        EventType branchType = branchEvent.type();
                        if (branchType == EventType.PLAN
                                || branchType == EventType.RUN_STARTED
                                || branchType.isTerminal()) {
                            throw new ScriptValidationException("branches 中不允许定义 " + branchType.wireName() + " 事件");
                        }
                        boundBranch.add(bindEvent(branchEvent, registry, root, false));
                    }
                    boundBranches.put(entry.getKey(), List.copyOf(boundBranch));
                }
                branches = boundBranches;
            }
        }

        return new MockEvent(
                event.delayMs(),
                event.type(),
                event.taskId(),
                task,
                event.payload(),
                event.eventId(),
                event.dispatchDelayMs(),
                event.dropDispatch(),
                branches);
    }

    private Map<String, TaskRef> buildRegistry(List<EventPayload.PlannedTask> tasks) {
        Map<String, TaskRef> registry = new LinkedHashMap<>();
        for (EventPayload.PlannedTask task : tasks) {
            if (registry.containsKey(task.taskId())) {
                throw new ScriptValidationException("plan 中 taskId 重复: " + task.taskId());
            }
            registry.put(task.taskId(), new TaskRef(
                    task.taskId(), task.parentTaskId(), task.title(), task.agentName()));
        }

        int roots = 0;
        for (TaskRef ref : registry.values()) {
            if (ref.parentTaskId() == null) {
                roots++;
            } else if (!registry.containsKey(ref.parentTaskId())) {
                throw new ScriptValidationException("parentTaskId 不存在: " + ref.parentTaskId());
            }
        }
        if (roots != 1) {
            throw new ScriptValidationException("plan 必须且只能有一个根任务");
        }

        TaskRef root = registry.values().stream()
                .filter(ref -> ref.parentTaskId() == null)
                .findFirst()
                .orElseThrow(() -> new ScriptValidationException("plan 缺少根任务"));
        for (TaskRef ref : registry.values()) {
            if (ref.parentTaskId() != null && !ref.parentTaskId().equals(root.taskId())) {
                throw new ScriptValidationException(
                        "任务只允许两层：子任务 " + ref.taskId() + " 必须直接挂在主任务 " + root.taskId() + " 下");
            }
        }

        for (EventPayload.PlannedTask task : tasks) {
            for (String dependency : task.dependsOn()) {
                if (!registry.containsKey(dependency)) {
                    throw new ScriptValidationException("dependsOn 引用了未知任务: " + dependency);
                }
            }
        }

        for (TaskRef ref : registry.values()) {
            Set<String> visited = new HashSet<>();
            String current = ref.taskId();
            while (current != null) {
                if (!visited.add(current)) {
                    throw new ScriptValidationException("plan 中存在父子环: " + ref.taskId());
                }
                current = registry.get(current).parentTaskId();
            }
        }
        return registry;
    }

    private TaskRef findRoot(Map<String, TaskRef> registry) {
        return registry.values().stream()
                .filter(ref -> ref.parentTaskId() == null)
                .findFirst()
                .orElseThrow(() -> new ScriptValidationException("plan 缺少根任务"));
    }

    private void validateTaskRef(TaskRef actual, TaskRef expected) {
        if (!actual.taskId().equals(expected.taskId())
                || !java.util.Objects.equals(actual.parentTaskId(), expected.parentTaskId())
                || !actual.title().equals(expected.title())
                || !actual.agentName().equals(expected.agentName())) {
            throw new ScriptValidationException("task 与 plan 中的任务定义不一致: " + actual.taskId());
        }
    }

    private List<JsonNode> requiredArray(JsonNode node, String field) {
        if (node == null || !node.isArray()) {
            throw new ScriptValidationException(field + " 必须是数组");
        }
        List<JsonNode> items = new ArrayList<>();
        node.forEach(items::add);
        return items;
    }

    private List<String> parseStringArray(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ScriptValidationException(field + " 必须是字符串数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new ScriptValidationException(field + " 必须是非空字符串数组");
            }
            values.add(item.asText());
        }
        return values;
    }

    private String requiredText(JsonNode node, String field) {
        return text(node, field, false);
    }

    private String requiredTextValue(JsonNode node, String field) {
        if (node == null) {
            throw new ScriptValidationException("缺少字段 " + field);
        }
        return requiredText(node, field);
    }

    private String text(JsonNode node, String field, boolean allowEmpty) {
        if (node == null) {
            throw new ScriptValidationException("缺少字段 " + field);
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new ScriptValidationException(field + " 必须是字符串");
        }
        String text = value.asText();
        if (!allowEmpty && text.isBlank()) {
            throw new ScriptValidationException(field + " 不能为空");
        }
        return text;
    }

    private String optionalText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ScriptValidationException(field + " 必须是字符串");
        }
        return value.asText().isBlank() ? null : value.asText();
    }

    private boolean requiredBoolean(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isBoolean()) {
            throw new ScriptValidationException(field + " 必须是布尔值");
        }
        return node.get(field).asBoolean();
    }

    private boolean bool(JsonNode node, String field, boolean defaultValue) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return defaultValue;
        }
        if (!node.get(field).isBoolean()) {
            throw new ScriptValidationException(field + " 必须是布尔值");
        }
        return node.get(field).asBoolean();
    }

    private long number(JsonNode node, String field, long defaultValue, long min, long max, String label) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (!value.isIntegralNumber()) {
            throw new ScriptValidationException(label + " 必须是整数");
        }
        long number = value.asLong();
        if (number < min || number > max) {
            throw new ScriptValidationException(label + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return number;
    }
}
