package com.tasknest.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.event.EventPayload;
import com.tasknest.orchestrator.tool.ToolRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解析规划 Agent 的最终 JSON，并校验两层结构、ID 唯一与依赖无环。
 * 结构违规统一抛出 PLAN_INVALID_OUTPUT，交由规划会话转换为 PLAN_AGENT_FAILED。
 *
 * <p>无状态解析器，规划阶段与执行期重排共用。校验范围包括：唯一根任务、
 * 至少一个直属子任务、子任务 ID 唯一且不得占用根 ID、allowedTools 必须属于执行工具集合、
 * parentTaskId 只能是根任务、dependsOn 只能指向已知（或显式允许的外部）任务且不能成环。
 * 缺失 taskId 时会生成占位 ID，但不会因此放宽其余约束。
 */
public final class PlanParser {

    public static final String ROOT_ID = "plan_root";

    private PlanParser() {
    }

    /**
     * 解析并严格校验计划，不引用计划外任务。
     *
     * @throws AgentException PLAN_INVALID_OUTPUT，内容为空、不是 JSON 或结构不合法时
     */
    public static PlanDraft parse(String content, String goal, ObjectMapper objectMapper) {
        return parse(content, goal, objectMapper, Set.of());
    }

    /**
     * 解析并严格校验计划，允许依赖指定的既有任务。
     *
     * <p>依赖会被归一化为规范任务 ID；重复依赖去重，指向根任务的依赖直接忽略。
     *
     * @param externalTaskIds 允许被依赖、但不出现在本次 JSON 中的既有任务 ID，
     *                        用于执行期重排时引用已开始或已完成的任务。
     * @throws AgentException PLAN_INVALID_OUTPUT，内容为空、不是 JSON 或结构不合法时
     */
    public static PlanDraft parse(String content, String goal, ObjectMapper objectMapper,
                                  Set<String> externalTaskIds) {
        JsonNode root = readJson(content, objectMapper);
        Set<String> external = externalTaskIds == null ? Set.of() : externalTaskIds;
        JsonNode tasksNode = root.path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            throw invalid("计划缺少 tasks 数组");
        }
        String title = text(root, "title");
        JsonNode rootNode = null;
        List<JsonNode> childNodes = new ArrayList<>();
        for (JsonNode node : tasksNode) {
            String parentId = text(node, "parentTaskId");
            if (parentId == null || parentId.isBlank()) {
                if (rootNode != null) {
                    throw invalid("计划只能有一个根任务");
                }
                rootNode = node;
            } else {
                childNodes.add(node);
            }
        }
        if (rootNode == null) {
            throw invalid("计划缺少根任务（parentTaskId 为 null）");
        }
        String providedRootId = text(rootNode, "taskId");
        if (title == null || title.isBlank()) {
            title = text(rootNode, "title");
        }
        if (title == null || title.isBlank()) {
            title = "软件开发任务";
        }
        if (childNodes.isEmpty()) {
            throw invalid("计划至少需要一个子任务");
        }

        // 记录原始 ID 到规范 ID 的映射，供 dependsOn 归一化；根任务一律改写为 plan_root。
        Map<String, String> idMap = new HashMap<>();
        if (providedRootId != null && !providedRootId.isBlank()
                && !ROOT_ID.equals(providedRootId)) {
            idMap.put(providedRootId, ROOT_ID);
        }
        Set<String> usedIds = new LinkedHashSet<>();
        usedIds.add(ROOT_ID);
        List<ChildNode> children = new ArrayList<>();
        int generated = 0;
        for (JsonNode node : childNodes) {
            String rawId = text(node, "taskId");
            String id;
            if (rawId == null || rawId.isBlank()) {
                do {
                    generated++;
                    id = "t_" + generated;
                } while (usedIds.contains(id));
            } else {
                id = rawId.trim();
            }
            if (ROOT_ID.equals(id)) {
                throw invalid("子任务不能使用根任务 ID " + ROOT_ID);
            }
            if (!usedIds.add(id)) {
                throw invalid("任务 ID 重复: " + id);
            }
            if (rawId != null && !rawId.isBlank()) {
                idMap.put(rawId, id);
            }
            children.add(new ChildNode(node, id));
        }

        List<EventPayload.PlannedTask> tasks = new ArrayList<>();
        String rootTitle = text(rootNode, "title");
        String rootAgent = text(rootNode, "agentName");
        tasks.add(new EventPayload.PlannedTask(
                ROOT_ID,
                null,
                blankTo(rootTitle, title),
                blankTo(rootAgent, "coordinator"),
                List.of(),
                null,
                null,
                null,
                null));

        Map<String, Set<String>> depends = new LinkedHashMap<>();
        for (ChildNode child : children) {
            JsonNode node = child.node();
            String id = child.id();
            String nodeTitle = text(node, "title");
            if (nodeTitle == null || nodeTitle.isBlank()) {
                throw invalid("子任务 " + id + " 缺少 title");
            }
            String description = text(node, "description");
            if (description == null || description.isBlank()) {
                throw invalid("子任务 " + id + " 缺少 description");
            }
            List<String> criteria = strings(node.path("acceptanceCriteria"));
            if (criteria.isEmpty()) {
                throw invalid("子任务 " + id + " 缺少 acceptanceCriteria");
            }
            List<String> allowedTools = strings(node.path("allowedTools"));
            for (String tool : allowedTools) {
                String canonical = ToolRegistry.canonical(tool);
                if (!ToolRegistry.EXECUTION_TOOLS.contains(canonical)) {
                    throw invalid("子任务 " + id + " 使用了未知工具: " + tool);
                }
            }
            String parent = text(node, "parentTaskId");
            if (parent != null && !parent.isBlank()
                    && !parent.equals(providedRootId) && !ROOT_ID.equals(parent)) {
                throw invalid("任务只允许两层，子任务 " + id + " 的父任务必须是根任务");
            }
            List<String> rawDependencies = strings(node.path("dependsOn"));
            List<String> normalizedDependencies = new ArrayList<>();
            for (String dependency : rawDependencies) {
                String mapped = idMap.getOrDefault(dependency, dependency);
                if (ROOT_ID.equals(mapped)) {
                    continue;
                }
                if (mapped.equals(id)
                        || (!usedIds.contains(mapped) && !external.contains(mapped))) {
                    throw invalid("子任务 " + id + " 依赖了非法任务: " + dependency);
                }
                if (!normalizedDependencies.contains(mapped)) {
                    normalizedDependencies.add(mapped);
                }
            }
            depends.put(id, new LinkedHashSet<>(normalizedDependencies));
            String testCommand = text(node, "testCommand");
            tasks.add(new EventPayload.PlannedTask(
                    id,
                    ROOT_ID,
                    nodeTitle.trim(),
                    blankTo(text(node, "agentName"), "implementer"),
                    List.copyOf(normalizedDependencies),
                    description.trim(),
                    criteria,
                    allowedTools.stream().map(ToolRegistry::canonical).toList(),
                    testCommand == null || testCommand.isBlank() ? null : testCommand.trim()));
        }
        ensureAcyclic(depends);
        return new PlanDraft(title, goal == null ? "" : goal, List.copyOf(tasks));
    }

    private static JsonNode readJson(String content, ObjectMapper objectMapper) {
        if (content == null || content.isBlank()) {
            throw invalid("规划结果为空");
        }
        String value = content.trim();
        // 容错模型偶尔包裹的 Markdown 代码块，随后只提取最外层 JSON 对象。
        int fence = value.indexOf("```");
        if (fence >= 0) {
            int start = value.indexOf('\n', fence);
            int end = value.lastIndexOf("```");
            if (start > 0 && end > start) {
                value = value.substring(start + 1, end);
            }
        }
        int first = value.indexOf('{');
        int last = value.lastIndexOf('}');
        if (first < 0 || last <= first) {
            throw invalid("规划结果不是 JSON 对象");
        }
        try {
            return objectMapper.readTree(value.substring(first, last + 1));
        } catch (Exception exception) {
            throw invalid("规划 JSON 解析失败: " + exception.getMessage());
        }
    }

    private static void ensureAcyclic(Map<String, Set<String>> depends) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (String id : depends.keySet()) {
            indegree.put(id, 0);
            outgoing.put(id, new ArrayList<>());
        }
        for (Map.Entry<String, Set<String>> entry : depends.entrySet()) {
            for (String dependency : entry.getValue()) {
                indegree.merge(entry.getKey(), 1, Integer::sum);
                outgoing.computeIfAbsent(dependency, ignored -> new ArrayList<>())
                        .add(entry.getKey());
            }
        }
        // Kahn 拓扑排序：从入度为 0 的节点剥离，若最终访问数少于节点数则存在环。
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, count) -> {
            if (count == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            visited++;
            for (String next : outgoing.getOrDefault(id, List.of())) {
                int nextCount = indegree.merge(next, -1, Integer::sum);
                if (nextCount == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited != depends.size()) {
            throw invalid("任务依赖存在环");
        }
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record ChildNode(JsonNode node, String id) {
    }

    private static AgentException invalid(String message) {
        return new AgentException("PLAN_INVALID_OUTPUT", message);
    }
}
