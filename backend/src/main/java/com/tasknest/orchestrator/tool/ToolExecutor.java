package com.tasknest.orchestrator.tool;

import com.tasknest.orchestrator.agent.AgentMode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 执行 P3 工具。所有路径经 {@link WorkspaceSandbox} 校验，所有命令先过策略。
 * 返回值为可直接写入 tool_result.result 的 JSON 友好结构。
 *
 * <p>由 {@link com.tasknest.orchestrator.agent.AgentLoop} 调用，是模型工具请求的
 * 唯一执行入口（ask_user 除外）。单文件 1MB、单次读取 256KB / 2000 行、
 * 单进程输出 64KB 均有上限；写文件使用临时文件原子替换。子进程环境只透传
 * 构建测试所需变量，输出中的已配置密钥会被脱敏；REVIEW 模式额外套用
 * {@link ReviewCommandPolicy}。所有文件路径均由沙箱限制在仓库根内。
 */
public class ToolExecutor {

    private static final int MAX_ENTRIES = 500;
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final long MAX_READ_BYTES = 256L * 1024L;
    private static final int MAX_READ_LINES = 2000;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final int MAX_TIMEOUT_MS = 600_000;
    private static final Set<String> FIND_IGNORED = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".venv", "venv");
    private static final Set<String> ENV_ALLOWLIST = Set.of(
            "PATH", "PATHEXT", "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "GRADLE_HOME",
            "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR",
            "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "APPDATA", "LOCALAPPDATA",
            "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "OS",
            "PROCESSOR_ARCHITECTURE", "NUMBER_OF_PROCESSORS", "LANG", "LC_ALL",
            "SHELL", "TERM", "USER", "USERNAME", "LOGNAME", "PWD");

    private final WorkspaceSandbox sandbox;
    private final Supplier<List<String>> secrets;
    private final CommandPolicy commandPolicy = new CommandPolicy();
    private final ReviewCommandPolicy reviewCommandPolicy = new ReviewCommandPolicy();

    public ToolExecutor(WorkspaceSandbox sandbox, Supplier<List<String>> secrets) {
        this.sandbox = sandbox;
        this.secrets = secrets == null ? List::of : secrets;
    }

    public WorkspaceSandbox sandbox() {
        return sandbox;
    }

    /**
     * 按规范化工具名分派执行。
     *
     * <p>写工具与规划模式的 Shell 在分派前直接拒绝；ask_user 必须由 AgentLoop 处理。
     * 参数缺失或类型错误抛 INVALID_ARGUMENT；路径越界、敏感路径与命令拦截分别由
     * {@link WorkspaceSandbox} 和命令策略抛 {@link ToolException}。
     */
    public Object execute(String rawToolName, Map<String, Object> arguments, AgentMode mode) {
        String toolName = ToolRegistry.canonical(rawToolName);
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        AgentMode effectiveMode = mode == null ? AgentMode.EXECUTION : mode;
        // 在分派前统一做模式门禁，避免依赖每个工具实现自觉检查权限。
        if (effectiveMode != AgentMode.EXECUTION
                && (ToolRegistry.FS_CREATE.equals(toolName)
                || ToolRegistry.FS_WRITE.equals(toolName)
                || (effectiveMode == AgentMode.PLANNING
                && ToolRegistry.SHELL_EXEC.equals(toolName)))) {
            throw new ToolException("TOOL_NOT_ALLOWED",
                    effectiveMode + " 模式不允许工具: " + toolName);
        }
        return switch (toolName) {
            case ToolRegistry.FS_LIST -> list(args);
            case ToolRegistry.FS_FIND -> find(args);
            case ToolRegistry.FS_READ -> read(args);
            case ToolRegistry.FS_CREATE -> create(args);
            case ToolRegistry.FS_WRITE -> write(args);
            case ToolRegistry.SHELL_EXEC -> shell(args, effectiveMode == AgentMode.REVIEW);
            case ToolRegistry.ASK_USER -> throw new ToolException(
                    "INVALID_ARGUMENT", "ask_user 由 Agent 循环处理，不能直接执行");
            default -> throw new ToolException("INVALID_ARGUMENT", "未知工具: " + rawToolName);
        };
    }

    private Map<String, Object> list(Map<String, Object> args) {
        Path directory = sandbox.resolveExisting(stringArg(args, "path", ".", false), true);
        boolean includeHidden = boolArg(args, "includeHidden", false);
        int maxEntries = clamp(intArg(args, "maxEntries", 200), 1, MAX_ENTRIES);
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (!includeHidden && name.startsWith(".")) {
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("path", sandbox.relative(entry.toAbsolutePath().normalize()));
                item.put("type", typeOf(attributes));
                if (attributes.isRegularFile()) {
                    item.put("size", attributes.size());
                }
                entries.add(item);
                // 多收集一项，用来准确判断是否真的被截断。
                if (entries.size() > maxEntries) {
                    truncated = true;
                    break;
                }
            }
        } catch (IOException exception) {
            throw new ToolException("IO_ERROR", "列出目录失败: " + exception.getMessage());
        }
        if (truncated) {
            entries.remove(entries.size() - 1);
        }
        entries.sort(Comparator
                .comparing((Map<String, Object> item) ->
                        "directory".equals(item.get("type")) ? 0 : 1)
                .thenComparing(item -> ((String) item.get("name")).toLowerCase(Locale.ROOT)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", sandbox.relative(directory));
        result.put("entries", entries);
        result.put("truncated", truncated);
        return result;
    }

    private Map<String, Object> find(Map<String, Object> args) {
        String pattern = stringArg(args, "pattern", null, true);
        Path start = sandbox.resolveExisting(stringArg(args, "path", ".", false), true);
        boolean includeHidden = boolArg(args, "includeHidden", false);
        int maxResults = clamp(intArg(args, "maxResults", 200), 1, MAX_ENTRIES);
        Pattern matcher = glob(pattern);
        List<Map<String, Object>> matches = new ArrayList<>();
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(start)) {
                        String name = dir.getFileName().toString();
                        if (FIND_IGNORED.contains(name)
                                || (!includeHidden && name.startsWith("."))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relative = sandbox.relative(file.toAbsolutePath().normalize());
                    String name = file.getFileName().toString();
                    if (!includeHidden && name.startsWith(".")) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!matches(relative, name, matcher)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (matches.size() >= maxResults) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", relative);
                    item.put("type", typeOf(attrs));
                    if (attrs.isRegularFile()) {
                        item.put("size", attrs.size());
                    }
                    matches.add(item);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new ToolException("IO_ERROR", "查找失败: " + exception.getMessage());
        }
        matches.sort(Comparator.comparing(item -> (String) item.get("path")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pattern", pattern);
        result.put("matches", matches);
        result.put("truncated", truncated[0]);
        return result;
    }

    private Map<String, Object> read(Map<String, Object> args) {
        Path file = sandbox.resolveExisting(stringArg(args, "path", null, true), false);
        sandbox.ensureSensitive(sandbox.relative(file));
        int startLine = Math.max(1, intArg(args, "startLine", 1));
        Integer requestedEnd = optionalInt(args, "endLine");
        if (requestedEnd != null && requestedEnd < startLine) {
            throw new ToolException("INVALID_ARGUMENT", "endLine 不能小于 startLine");
        }
        if (isBinary(file)) {
            throw new ToolException("BINARY_FILE", "文件包含二进制内容: "
                    + sandbox.relative(file));
        }
        StringBuilder content = new StringBuilder();
        long bytes = 0;
        int totalLines = 0;
        int returnedLines = 0;
        int lastReturnedLine = startLine - 1;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                if (totalLines < startLine || (requestedEnd != null && totalLines > requestedEnd)) {
                    continue;
                }
                if (returnedLines >= MAX_READ_LINES) {
                    truncated = true;
                    continue;
                }
                String piece = (returnedLines == 0 ? "" : "\n") + line;
                int pieceBytes = piece.getBytes(StandardCharsets.UTF_8).length;
                if (bytes + pieceBytes > MAX_READ_BYTES) {
                    truncated = true;
                    continue;
                }
                content.append(piece);
                bytes += pieceBytes;
                returnedLines++;
                lastReturnedLine = totalLines;
            }
        } catch (IOException exception) {
            throw new ToolException("IO_ERROR", "读取文件失败: " + exception.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", sandbox.relative(file));
        result.put("content", content.toString());
        result.put("startLine", startLine);
        result.put("endLine", lastReturnedLine);
        result.put("totalLines", totalLines);
        result.put("size", size(file));
        result.put("truncated", truncated);
        return result;
    }

    private Map<String, Object> create(Map<String, Object> args) {
        Path target = sandbox.resolveForWrite(stringArg(args, "path", null, true));
        String content = stringArg(args, "content", "", false);
        byte[] bytes = contentBytes(content);
        boolean createParents = boolArg(args, "createParents", true);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolException("FILE_EXISTS", "文件已存在: " + sandbox.relative(target));
        }
        Path parent = target.getParent();
        if (!Files.isDirectory(parent)) {
            if (!createParents) {
                throw new ToolException("PARENT_NOT_FOUND",
                        "父目录不存在: " + sandbox.relative(parent));
            }
            try {
                Files.createDirectories(parent);
            } catch (IOException exception) {
                throw new ToolException("IO_ERROR", "创建父目录失败: " + exception.getMessage());
            }
        }
        atomicWrite(target, bytes, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", sandbox.relative(target));
        result.put("bytesWritten", bytes.length);
        result.put("created", true);
        return result;
    }

    private Map<String, Object> write(Map<String, Object> args) {
        Path target = sandbox.resolveForWrite(stringArg(args, "path", null, true));
        String content = stringArg(args, "content", "", false);
        byte[] bytes = contentBytes(content);
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        long previousBytes = 0;
        if (existed) {
            if (!Files.isRegularFile(target)) {
                throw new ToolException("NOT_A_FILE", "不是普通文件: " + sandbox.relative(target));
            }
            previousBytes = size(target);
        }
        Path parent = target.getParent();
        if (!Files.isDirectory(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException exception) {
                throw new ToolException("IO_ERROR", "创建父目录失败: " + exception.getMessage());
            }
        }
        atomicWrite(target, bytes, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", sandbox.relative(target));
        result.put("bytesWritten", bytes.length);
        result.put("created", !existed);
        result.put("previousBytes", previousBytes);
        return result;
    }

    /**
     * 执行本地 Shell。命令先过策略，超时强杀整个进程树；输出截断并脱敏。
     * 工作目录必须在沙箱内，默认为仓库根。
     */
    private Map<String, Object> shell(Map<String, Object> args, boolean reviewMode) {
        String command = stringArg(args, "command", null, true).trim();
        String rawCwd = stringArg(args, "cwd", ".", false);
        Path cwd = rawCwd.isBlank() || ".".equals(rawCwd)
                ? sandbox.root()
                : sandbox.resolveExisting(rawCwd, true);
        int timeoutMs = clamp(intArg(args, "timeoutMs", DEFAULT_TIMEOUT_MS),
                1_000, MAX_TIMEOUT_MS);
        // 审核模式先过更严格的白名单；普通执行只过危险命令策略。
        if (reviewMode) {
            reviewCommandPolicy.check(command);
        } else {
            commandPolicy.check(command);
        }
        ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
        builder.directory(cwd.toFile());
        filterEnvironment(builder.environment());
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new ToolException("COMMAND_START_FAILED",
                    "无法启动命令: " + exception.getMessage());
        }
        OutputCollector stdout = new OutputCollector();
        OutputCollector stderr = new OutputCollector();
        Thread outThread = pump(process.getInputStream(), stdout, "tasknest-stdout");
        Thread errThread = pump(process.getErrorStream(), stderr, "tasknest-stderr");
        int exitCode;
        boolean timedOut;
        try {
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                killTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
                exitCode = -1;
            } else {
                timedOut = false;
                exitCode = process.exitValue();
            }
            outThread.join(2_000);
            errThread.join(2_000);
        } catch (InterruptedException exception) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new ToolException("COMMAND_INTERRUPTED", "命令执行被中断");
        }
        if (timedOut) {
            throw new ToolException("COMMAND_TIMEOUT",
                    "命令超时（" + timeoutMs + "ms）: " + command);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", redact(command));
        result.put("cwd", sandbox.relative(cwd));
        result.put("exitCode", exitCode);
        result.put("stdout", redact(stdout.text()));
        result.put("stderr", redact(stderr.text()));
        result.put("timedOut", false);
        result.put("truncated", stdout.truncated || stderr.truncated);
        return result;
    }

    private List<String> shellCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("cmd.exe", "/d", "/s", "/c", command);
        }
        return List.of("/bin/sh", "-c", command);
    }

    // 只透传构建 / 测试所需变量，避免把密钥等敏感环境变量泄漏给模型发起的进程。
    private void filterEnvironment(Map<String, String> environment) {
        Map<String, String> current = System.getenv();
        environment.clear();
        for (String name : ENV_ALLOWLIST) {
            String value = current.get(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        // Windows 变量名大小写不敏感，补一层查找。
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            for (Map.Entry<String, String> entry : current.entrySet()) {
                if (ENV_ALLOWLIST.contains(entry.getKey().toUpperCase(Locale.ROOT))) {
                    environment.put(entry.getKey(), entry.getValue());
                }
            }
        }
        // 避免 vitest / jest 等测试工具进入交互式 watch 模式。
        environment.putIfAbsent("CI", "1");
    }

    private Thread pump(InputStream stream, OutputCollector collector, String name) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream input = stream) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    collector.append(buffer, read);
                }
            } catch (IOException ignored) {
                // 进程被终止时流会关闭，已收集的内容仍然有效。
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void killTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // 进程可能已退出。
            }
        });
        process.destroyForcibly();
    }

    // 命令与输出中出现的已配置密钥统一替换，防止进入事件账本与模型上下文。
    private String redact(String value) {
        String result = value;
        for (String secret : secrets.get()) {
            if (secret != null && !secret.isBlank()) {
                result = result.replace(secret, "***");
            }
        }
        return result;
    }

    // 先写同目录临时文件再原子替换，避免写入中断留下半截源码。
    private void atomicWrite(Path target, byte[] bytes, boolean replace) {
        Path parent = target.getParent();
        Path temp = null;
        try {
            temp = Files.createTempFile(parent, ".tasknest-", ".tmp");
            Files.write(temp, bytes,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            move(temp, target, replace);
            temp = null;
        } catch (IOException exception) {
            throw new ToolException("IO_ERROR", "写入文件失败: " + exception.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响结果。
                }
            }
        }
    }

    private void move(Path temp, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            if (replace) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp, target);
            }
        }
    }

    private boolean isBinary(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read = input.read(buffer);
            for (int index = 0; index < read; index++) {
                if (buffer[index] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            throw new ToolException("IO_ERROR", "读取文件失败: " + exception.getMessage());
        }
    }

    private long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            return 0;
        }
    }

    private byte[] contentBytes(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new ToolException("INVALID_ARGUMENT", "content 超过 1MB 限制");
        }
        return bytes;
    }

    private String typeOf(BasicFileAttributes attributes) {
        if (attributes.isDirectory()) {
            return "directory";
        }
        if (attributes.isRegularFile()) {
            return "file";
        }
        return "other";
    }

    private Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        String normalized = pattern.replace('\\', '/');
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (current == '*') {
                if (index + 1 < normalized.length() && normalized.charAt(index + 1) == '*') {
                    index++;
                    if (index + 1 < normalized.length() && normalized.charAt(index + 1) == '/') {
                        index++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }

    private boolean matches(String relative, String name, Pattern matcher) {
        return matcher.matcher(relative).matches() || matcher.matcher(name).matches();
    }

    private String stringArg(Map<String, Object> args, String key, String fallback,
                             boolean required) {
        Object value = args.get(key);
        if (value == null) {
            if (required) {
                throw new ToolException("INVALID_ARGUMENT", "缺少必填参数: " + key);
            }
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw new ToolException("INVALID_ARGUMENT", "参数 " + key + " 必须是字符串");
        }
        if (required && text.isBlank()) {
            throw new ToolException("INVALID_ARGUMENT", "参数 " + key + " 不能为空");
        }
        return text;
    }

    private int intArg(Map<String, Object> args, String key, int fallback) {
        Integer value = optionalInt(args, key);
        return value == null ? fallback : value;
    }

    private Integer optionalInt(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && text.matches("-?\\d+")) {
            return Integer.parseInt(text);
        }
        throw new ToolException("INVALID_ARGUMENT", "参数 " + key + " 必须是整数");
    }

    private boolean boolArg(Map<String, Object> args, String key, boolean fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw new ToolException("INVALID_ARGUMENT", "参数 " + key + " 必须是布尔值");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class OutputCollector {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated;

        synchronized void append(byte[] bytes, int length) {
            int remaining = MAX_OUTPUT_BYTES - buffer.size();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int write = Math.min(remaining, length);
            buffer.write(bytes, 0, write);
            if (write < length) {
                truncated = true;
            }
        }

        synchronized String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
