package com.tasknest.orchestrator.tool;

import com.tasknest.orchestrator.agent.AgentMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具名称、OpenAI function 定义与别名映射。
 * 模型使用下划线名称（fs_read），事件契约与内部实现使用点号名称（fs.read）。
 */
public final class ToolRegistry {

    public static final String FS_LIST = "fs.list";
    public static final String FS_FIND = "fs.find";
    public static final String FS_READ = "fs.read";
    public static final String FS_CREATE = "fs.create";
    public static final String FS_WRITE = "fs.write";
    public static final String SHELL_EXEC = "shell.exec";
    public static final String ASK_USER = "ask_user";

    public static final Set<String> EXECUTION_TOOLS = Set.of(
            FS_LIST, FS_FIND, FS_READ, FS_CREATE, FS_WRITE, SHELL_EXEC, ASK_USER);
    public static final Set<String> REVIEW_TOOLS = Set.of(
            FS_LIST, FS_FIND, FS_READ, SHELL_EXEC, ASK_USER);
    public static final Set<String> PLANNING_TOOLS = Set.of(
            FS_LIST, FS_FIND, FS_READ, ASK_USER);
    private static final Set<String> CONVERSATIONAL_TOOLS = Set.of(ASK_USER);

    private ToolRegistry() {
    }

    public static String toFunctionName(String toolName) {
        return toolName.replace('.', '_');
    }

    public static String toToolName(String functionName) {
        if (functionName == null) {
            return "";
        }
        String value = functionName.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "fs_list" -> FS_LIST;
            case "fs_find" -> FS_FIND;
            case "fs_read" -> FS_READ;
            case "fs_create" -> FS_CREATE;
            case "fs_write" -> FS_WRITE;
            case "shell_exec" -> SHELL_EXEC;
            case "ask_user" -> ASK_USER;
            default -> value.contains(".") ? value : value.replace('_', '.');
        };
    }

    /** 别名（下划线）也接受，便于容错。 */
    public static String canonical(String toolName) {
        return toToolName(toolName);
    }

    public static boolean isConversational(String toolName) {
        return CONVERSATIONAL_TOOLS.contains(canonical(toolName));
    }

    public static List<Map<String, Object>> definitions(AgentMode mode, List<String> allowed) {
        List<Map<String, Object>> definitions = new ArrayList<>();
        AgentMode effectiveMode = mode == null ? AgentMode.EXECUTION : mode;
        Set<String> allowedSet = allowed == null || allowed.isEmpty()
                ? switch (effectiveMode) {
                    case PLANNING -> PLANNING_TOOLS;
                    case REVIEW -> REVIEW_TOOLS;
                    case EXECUTION -> EXECUTION_TOOLS;
                }
                : Set.copyOf(allowed.stream().map(ToolRegistry::canonical).toList());
        for (String name : List.of(FS_LIST, FS_FIND, FS_READ)) {
            if (allowedSet.contains(name)) {
                definitions.add(definition(name, schemaFor(name)));
            }
        }
        if (effectiveMode == AgentMode.EXECUTION && allowedSet.contains(FS_CREATE)) {
            definitions.add(definition(FS_CREATE, schemaFor(FS_CREATE)));
        }
        if (effectiveMode == AgentMode.EXECUTION && allowedSet.contains(FS_WRITE)) {
            definitions.add(definition(FS_WRITE, schemaFor(FS_WRITE)));
        }
        if (effectiveMode != AgentMode.PLANNING && allowedSet.contains(SHELL_EXEC)) {
            definitions.add(definition(SHELL_EXEC, schemaFor(SHELL_EXEC)));
        }
        if (allowedSet.contains(ASK_USER)) {
            definitions.add(definition(ASK_USER, schemaFor(ASK_USER)));
        }
        return definitions;
    }

    private static Map<String, Object> definition(String name, Map<String, Object> parameters) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", toFunctionName(name));
        function.put("description", description(name));
        function.put("parameters", parameters);
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", "function");
        definition.put("function", function);
        return definition;
    }

    private static String description(String name) {
        return switch (name) {
            case FS_LIST -> "列出工作目录中的一层目录项。路径必须位于工作目录内。";
            case FS_FIND -> "按 glob 模式（*、**、?）查找工作目录中的文件或目录，不执行正则。";
            case FS_READ -> "读取工作目录内的 UTF-8 文本文件，可按行范围读取。";
            case FS_CREATE -> "创建新文件；目标已存在时失败，不覆盖。";
            case FS_WRITE -> "写入完整文件内容；文件不存在时创建，存在时覆盖。";
            case SHELL_EXEC -> "在工作目录内执行本地 Shell 命令；危险命令会被拦截。";
            case ASK_USER -> "当需求含糊或存在多个方案时，向用户提问并等待选择或自由输入。";
            default -> name;
        };
    }

    private static Map<String, Object> schemaFor(String name) {
        return switch (name) {
            case FS_LIST -> schema(
                    Map.of(
                            "path", string("要列出的目录，默认 ."),
                            "includeHidden", bool("是否包含点文件，默认 false"),
                            "maxEntries", integer("最多返回条目数，默认 200，最大 500")),
                    List.of());
            case FS_FIND -> schema(
                    Map.of(
                            "pattern", string("glob 模式，例如 **/*.ts"),
                            "path", string("查找起点目录，默认 ."),
                            "maxResults", integer("最多返回数量，默认 200，最大 500"),
                            "includeHidden", bool("是否包含点文件，默认 false")),
                    List.of("pattern"));
            case FS_READ -> schema(
                    Map.of(
                            "path", string("文件路径"),
                            "startLine", integer("起始行，从 1 开始"),
                            "endLine", integer("结束行，含端点")),
                    List.of("path"));
            case FS_CREATE -> schema(
                    Map.of(
                            "path", string("新文件路径"),
                            "content", string("完整文件内容"),
                            "createParents", bool("是否创建缺失父目录，默认 true")),
                    List.of("path", "content"));
            case FS_WRITE -> schema(
                    Map.of(
                            "path", string("文件路径"),
                            "content", string("完整文件内容")),
                    List.of("path", "content"));
            case SHELL_EXEC -> schema(
                    Map.of(
                            "command", string("要执行的命令"),
                            "cwd", string("工作目录内的相对目录，默认 ."),
                            "timeoutMs", integer("超时毫秒，1000-600000，默认 120000")),
                    List.of("command"));
            case ASK_USER -> {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("type", "object");
                option.put("properties", Map.of(
                        "id", string("选项 id"),
                        "label", string("选项标题"),
                        "description", string("选项说明")));
                option.put("required", List.of("id", "label"));
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("question", string("要向用户说明的问题"));
                properties.put("options", Map.of(
                        "type", "array",
                        "items", option,
                        "description", "可选项，最多 5 个"));
                properties.put("allowCustom", bool("是否允许自由输入"));
                properties.put("customPlaceholder", string("自由输入框占位文案"));
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", properties);
                schema.put("required", List.of("question"));
                yield schema;
            }
            default -> schema(Map.of(), List.of());
        };
    }

    private static Map<String, Object> schema(Map<String, Object> properties,
                                              List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }
}
