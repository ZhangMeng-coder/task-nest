package com.tasknest.orchestrator.tool;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 审核 Agent 的 Shell 白名单：只允许测试、lint、build、类型检查与只读 Git。
 * 危险命令策略仍会先执行；这里再收紧到可审计的构建/测试命令。
 *
 * <p>属于工具安全层：{@link ToolExecutor} 在 REVIEW 模式执行 shell.exec 前调用。
 * 复合命令按 {@code &&} / {@code ||} / {@code ;} 分段校验，禁止管道与重定向；
 * 允许前置 {@code VAR=value} 环境变量赋值，但按真实可执行文件判断白名单。
 * 命中即抛 COMMAND_BLOCKED。
 */
public class ReviewCommandPolicy {

    private static final Set<String> DIRECT_BINARIES = Set.of(
            "tsc", "vue-tsc", "eslint", "vitest", "jest", "pytest", "phpunit");
    private static final Set<String> NPM_SCRIPTS = Set.of(
            "test", "lint", "build", "typecheck", "type-check", "check", "verify");
    private static final Set<String> MVN_GOALS = Set.of(
            "test", "verify", "package", "compile", "checkstyle:check");
    private static final Set<String> GRADLE_TASKS = Set.of(
            "test", "check", "build", "compileJava", "compileTestJava");
    private static final Set<String> MVN_OPTIONS_WITH_VALUE = Set.of(
            "-f", "--file", "-pl", "--projects", "-p", "--activate-profiles",
            "-s", "--settings", "-gs", "--global-settings", "-t", "--toolchains",
            "-rf", "--resume-from");
    private static final Set<String> GRADLE_OPTIONS_WITH_VALUE = Set.of(
            "-p", "--project-dir", "-g", "--gradle-user-home",
            "-c", "--settings-file", "-b", "--build-file");

    private final CommandPolicy commandPolicy = new CommandPolicy();

    /**
     * 校验审核命令。空命令与 {@code none} 单独处理；其余先过危险命令策略，
     * 再逐段检查是否属于允许的测试 / 构建工具与目标。
     */
    public void check(String command) {
        String value = command == null ? "" : command.trim();
        if (value.isEmpty()) {
            throw new ToolException("INVALID_ARGUMENT", "command 不能为空");
        }
        if (value.equalsIgnoreCase("none")) {
            return;
        }
        commandPolicy.check(value);
        // 复合命令逐段校验，避免用 && / || / ; 把危险段藏在白名单命令之后。
        for (String segment : value.split("&&|\\|\\||;")) {
            checkSegment(stripEnvironment(segment.trim()));
        }
    }

    /** 命令是否通过白名单；用于测试探测结果的可用性判断，不抛出异常。 */
    public boolean allows(String command) {
        try {
            check(command);
            return true;
        } catch (ToolException exception) {
            return false;
        }
    }

    private void checkSegment(String segment) {
        if (segment.isEmpty()) {
            return;
        }
        if (segment.contains("|") || segment.contains(">") || segment.contains("<")) {
            throw blocked("审核命令不允许管道或重定向");
        }
        List<String> tokens = List.of(segment.split("\\s+"));
        String executable = tokens.get(0).toLowerCase(Locale.ROOT);
        switch (executable) {
            case "npm", "pnpm", "yarn" -> checkPackageManager(tokens);
            case "npx" -> checkNpx(tokens);
            case "mvn", "./mvnw", "mvnw", "mvn.cmd", "./mvnw.cmd" -> checkMvn(tokens);
            case "gradle", "./gradlew", "gradlew", "gradlew.bat", "gradle.bat" ->
                    checkGradle(tokens);
            case "python", "python3", "py" -> checkPython(tokens);
            case "go" -> checkGo(tokens);
            case "dotnet" -> checkDotnet(tokens);
            case "cargo" -> checkCargo(tokens);
            case "git" -> {
                // 只读 Git 已由 CommandPolicy 校验。
            }
            default -> {
                if (!DIRECT_BINARIES.contains(executable.replace(".cmd", ""))) {
                    throw blocked("审核 Agent 不允许执行命令: " + executable);
                }
            }
        }
    }

    private void checkPackageManager(List<String> tokens) {
        if (tokens.size() < 2) {
            throw blocked("缺少 npm/pnpm/yarn 子命令");
        }
        String sub = tokens.get(1).toLowerCase(Locale.ROOT);
        if ("test".equals(sub)) {
            return;
        }
        if ("run".equals(sub) && tokens.size() >= 3
                && NPM_SCRIPTS.contains(tokens.get(2).toLowerCase(Locale.ROOT))) {
            return;
        }
        throw blocked("只允许 test / lint / build / typecheck 脚本");
    }

    private void checkNpx(List<String> tokens) {
        if (tokens.size() < 2) {
            throw blocked("缺少 npx 目标命令");
        }
        String target = tokens.get(1).toLowerCase(Locale.ROOT).replace(".cmd", "");
        if (!DIRECT_BINARIES.contains(target) && !"tsc".equals(target)) {
            throw blocked("npx 只允许测试/类型检查工具: " + target);
        }
    }

    private void checkMvn(List<String> tokens) {
        boolean goal = false;
        for (int index = 1; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith("-")) {
                if (MVN_OPTIONS_WITH_VALUE.contains(token)) {
                    index++;
                }
                continue;
            }
            if (!MVN_GOALS.contains(token)) {
                throw blocked("Maven 只允许 test/verify/package/compile: " + token);
            }
            goal = true;
        }
        if (!goal) {
            throw blocked("Maven 命令缺少测试或构建目标");
        }
    }

    private void checkGradle(List<String> tokens) {
        boolean goal = false;
        for (int index = 1; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith("-")) {
                if (GRADLE_OPTIONS_WITH_VALUE.contains(token)) {
                    index++;
                }
                continue;
            }
            if (!GRADLE_TASKS.contains(token)) {
                throw blocked("Gradle 只允许 test/check/build: " + token);
            }
            goal = true;
        }
        if (!goal) {
            throw blocked("Gradle 命令缺少测试或构建任务");
        }
    }

    private void checkPython(List<String> tokens) {
        if (tokens.size() < 3
                || !"-m".equals(tokens.get(1))
                || !"pytest".equals(tokens.get(2).toLowerCase(Locale.ROOT))) {
            throw blocked("Python 只允许 python -m pytest");
        }
    }

    private void checkGo(List<String> tokens) {
        if (tokens.size() < 2 || !"test".equals(tokens.get(1).toLowerCase(Locale.ROOT))) {
            throw blocked("Go 只允许 go test");
        }
    }

    private void checkDotnet(List<String> tokens) {
        if (tokens.size() < 2) {
            throw blocked("缺少 dotnet 子命令");
        }
        String sub = tokens.get(1).toLowerCase(Locale.ROOT);
        if (!"test".equals(sub) && !"build".equals(sub)) {
            throw blocked("dotnet 只允许 test/build");
        }
    }

    private void checkCargo(List<String> tokens) {
        if (tokens.size() < 2) {
            throw blocked("缺少 cargo 子命令");
        }
        String sub = tokens.get(1).toLowerCase(Locale.ROOT);
        if (!"test".equals(sub) && !"check".equals(sub) && !"build".equals(sub)) {
            throw blocked("cargo 只允许 test/check/build");
        }
    }

    // 跳过前置 VAR=value 赋值，按真实可执行文件判断白名单，而不是把变量名当命令。
    private String stripEnvironment(String segment) {
        String value = segment;
        while (value.matches("^[A-Za-z_][A-Za-z0-9_]*=.*")) {
            int space = value.indexOf(' ');
            if (space < 0) {
                return "";
            }
            value = value.substring(space + 1).trim();
        }
        return value;
    }

    private ToolException blocked(String message) {
        return new ToolException("COMMAND_BLOCKED", message, "review-command-policy");
    }
}
