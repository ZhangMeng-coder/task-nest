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
 *
 * <p>由 {@code ReviewAgent} 在模型审核前调用。命令优先取计划中的 testCommand，
 * 否则按仓库类型自动探测；执行前经 {@link ReviewCommandPolicy} 白名单校验，
 * 超时上限 10 分钟。执行失败、被策略拦截或超时都转换为 {@link Evidence}
 * （UNAVAILABLE / FAILED），不向调用方抛异常，保证审核流程仍能给出结论。
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

    /**
     * 在仓库根执行一次测试并收集证据。
     *
     * <p>测试前后各取一次 git status 以识别工作区副作用；stdout / stderr 合并后
     * 只保留尾部 2000 字符。策略拦截或探测失败记为 UNAVAILABLE，
     * 超时与非零退出记为 FAILED。测试执行阶段的工具异常会转换为证据，
     * 而不是中断审核。
     *
     * @param plannedCommand 计划声明的测试命令；空白时自动探测
     * @param secrets        输出脱敏用的密钥提供者，可为 null
     */
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
        // 仅在计划未给出测试命令时探测；按仓库标志文件选择项目已有的测试入口。
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

    // 对比测试前后的 git status 行，任何新增行都视为测试留下的工作区副作用。
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
        // 失败定位信息通常在输出末尾，保留尾部而非头部。
        if (value.length() > 2000) {
            value = value.substring(value.length() - 2000);
        }
        return value;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 一次平台测试的证据快照。{@code exitCode} 在超时或未能启动时为 null；
     * {@code sideEffects} 是测试后新增的 git status 行，非空即判定审核失败。
     */
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
