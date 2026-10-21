package com.tasknest.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.orchestrator.GitService;
import com.tasknest.orchestrator.agent.AgentMode;
import com.tasknest.orchestrator.tool.ReviewCommandPolicy;
import com.tasknest.orchestrator.tool.ToolException;
import com.tasknest.orchestrator.tool.ToolExecutor;
import com.tasknest.orchestrator.tool.WorkspaceSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 审核前由平台独立执行一次测试，记录命令、退出码与输出摘要；
 * 之后校验测试是否留下工作区副作用。测试在已提交的子任务分支上执行。
 */
@Component
public class ReviewTestRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewTestRunner.class);
    private static final int TEST_TIMEOUT_MS = 600_000;

    private final ReviewCommandPolicy policy = new ReviewCommandPolicy();
    private final GitService gitService;
    private final ObjectMapper objectMapper;

    public ReviewTestRunner(GitService gitService, ObjectMapper objectMapper) {
        this.gitService = gitService;
        this.objectMapper = objectMapper;
    }

    public Evidence run(Path root, String plannedCommand, Supplier<List<String>> secrets) {
        String command = plannedCommand == null || plannedCommand.isBlank()
                ? detect(root) : plannedCommand.trim();
        if (command == null || command.isBlank() || "none".equalsIgnoreCase(command)) {
            return new Evidence(command == null ? "none" : command, null, false,
                    "未配置测试命令，且无法自动探测", "UNAVAILABLE", List.of());
        }
        if (!policy.allows(command)) {
            return new Evidence(command, null, false,
                    "测试命令不在审核白名单内", "UNAVAILABLE", List.of());
        }
        List<String> before = gitService.statusLines(root);
        ToolExecutor executor = new ToolExecutor(new WorkspaceSandbox(root), secrets);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("command", command);
        args.put("cwd", ".");
        args.put("timeoutMs", TEST_TIMEOUT_MS);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) executor.execute(
                    com.tasknest.orchestrator.tool.ToolRegistry.SHELL_EXEC,
                    args, AgentMode.REVIEW);
            Object exit = result.get("exitCode");
            int exitCode = exit instanceof Number number ? number.intValue() : -1;
            String summary = summarize(String.valueOf(result.getOrDefault("stdout", "")),
                    String.valueOf(result.getOrDefault("stderr", "")));
            List<String> sideEffects = newSideEffects(before, gitService.statusLines(root));
            String status = exitCode == 0 ? "PASSED" : "FAILED";
            return new Evidence(command, exitCode, false, summary, status, sideEffects);
        } catch (ToolException exception) {
            if ("COMMAND_TIMEOUT".equals(exception.code())) {
                return new Evidence(command, null, true,
                        exception.getMessage(), "FAILED", List.of());
            }
            if ("COMMAND_BLOCKED".equals(exception.code())) {
                return new Evidence(command, null, false,
                        "测试命令被策略拦截: " + exception.getMessage(), "UNAVAILABLE", List.of());
            }
            log.warn("审核测试执行失败 command={} code={}", command, exception.code());
            return new Evidence(command, null, false,
                    "测试命令执行失败: " + exception.getMessage(), "UNAVAILABLE", List.of());
        } catch (RuntimeException exception) {
            log.warn("审核测试执行异常 command={}", command, exception);
            return new Evidence(command, null, false,
                    "测试命令执行异常: " + exception.getMessage(), "UNAVAILABLE", List.of());
        }
    }

    private String detect(Path root) {
        try {
            Path packageJson = root.resolve("package.json");
            if (Files.isRegularFile(packageJson)) {
                JsonNode scripts = objectMapper.readTree(packageJson.toFile()).path("scripts");
                if (!scripts.path("test").asText("").isBlank()) {
                    if (Files.isRegularFile(root.resolve("pnpm-lock.yaml"))) {
                        return "pnpm test";
                    }
                    if (Files.isRegularFile(root.resolve("yarn.lock"))) {
                        return "yarn test";
                    }
                    return "npm test";
                }
            }
            if (Files.isRegularFile(root.resolve("pom.xml"))) {
                return "mvn test";
            }
            if (Files.isRegularFile(root.resolve("gradlew"))
                    || Files.isRegularFile(root.resolve("gradlew.bat"))) {
                return isWindows() ? "gradlew.bat test" : "./gradlew test";
            }
            if (Files.isRegularFile(root.resolve("go.mod"))) {
                return "go test ./...";
            }
            if (Files.isRegularFile(root.resolve("Cargo.toml"))) {
                return "cargo test";
            }
            if (Files.isRegularFile(root.resolve("pytest.ini"))
                    || Files.isRegularFile(root.resolve("pyproject.toml"))) {
                return "python -m pytest";
            }
            return "none";
        } catch (IOException exception) {
            log.warn("探测测试命令失败", exception);
            return "none";
        }
    }

    private List<String> newSideEffects(List<String> before, List<String> after) {
        if (after == null || after.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> initial = new java.util.LinkedHashSet<>(
                before == null ? List.of() : before);
        List<String> added = new ArrayList<>();
        for (String line : after) {
            if (!initial.contains(line)) {
                added.add(line);
            }
        }
        return List.copyOf(added);
    }

    private String summarize(String stdout, String stderr) {
        String value = (stdout == null ? "" : stdout)
                + (stderr == null || stderr.isBlank() ? "" : "\n[stderr]\n" + stderr);
        value = value.trim();
        if (value.length() > 2000) {
            value = value.substring(value.length() - 2000);
        }
        return value;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public record Evidence(
            String command,
            Integer exitCode,
            boolean timedOut,
            String summary,
            String testStatus,
            List<String> sideEffects
    ) {
        public boolean hasSideEffects() {
            return sideEffects != null && !sideEffects.isEmpty();
        }
    }
}
