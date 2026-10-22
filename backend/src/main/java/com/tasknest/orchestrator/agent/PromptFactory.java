package com.tasknest.orchestrator.agent;

import com.tasknest.event.EventPayload;

import java.util.List;

/**
 * Agent 提示词模板。所有提示词只包含任务上下文，不含任何密钥或环境变量值。
 *
 * <p>纯静态工厂，供规划、子任务执行、审核、汇总与重排使用。提示词约束与
 * {@link PlanParser}、审核结果解析及工具策略互为补充：模型越界时仍由平台校验兜底，
 * 因此这里不重复实现安全判断。{@link #platform()} 暴露操作系统、架构与 Shell 类型，
 * 帮助模型选择正确的命令语法。
 */
public final class PromptFactory {

    private PromptFactory() {
    }

    /** 规划 Agent 系统提示：只读调查、歧义时 ask_user、最终输出严格两层计划 JSON。 */
    public static String planningSystem() {
        return """
                你是 TaskNest 的规划 Agent，负责把用户的软件开发目标拆成可直接执行的计划。
                你可以使用 fs.list / fs.find / fs.read 调查仓库，但不能修改任何文件或执行 Shell。
                规则：
                1. 需求含糊、存在多种实现方案或缺少关键信息时，必须调用 ask_user 提问，不要自行假设。
                2. 只允许两层结构：一个根任务（taskId 固定为 plan_root，parentTaskId 为 null）
                   加若干直属子任务（parentTaskId 为 plan_root）。
                3. 子任务必须可独立实现与验证，按依赖顺序排列；dependsOn 只能引用其他子任务 ID，
                   不能成环。
                4. 每个子任务必须给出 description、acceptanceCriteria（至少一条）、allowedTools
                   与可选 testCommand。allowedTools 只能是 fs.list / fs.find / fs.read /
                   fs.create / fs.write / shell.exec 的子集。纯文档任务 testCommand 设为 "none"。
                5. 不要输出 token、费用或时间估算。
                6. 计划完成后，最终回复必须是严格 JSON（不要 Markdown 代码块），格式：
                {
                  "title": "主任务标题",
                  "tasks": [
                    {
                      "taskId": "plan_root",
                      "parentTaskId": null,
                      "title": "主任务标题",
                      "agentName": "coordinator",
                      "dependsOn": []
                    },
                    {
                      "taskId": "t_1",
                      "parentTaskId": "plan_root",
                      "title": "子任务标题",
                      "agentName": "implementer",
                      "dependsOn": [],
                      "description": "要实现的完整内容",
                      "acceptanceCriteria": ["可验证的标准"],
                      "allowedTools": ["fs.list", "fs.read", "fs.create", "fs.write", "shell.exec"],
                      "testCommand": "npm test"
                    }
                  ]
                }
                在真正需要修改代码前，先调查仓库结构、构建方式与既有约定。
                """;
    }

    /** 组装规划用户提示：工作目录、目标、当前计划（修订时）与修订要求。 */
    public static String planningUser(String goal,
                                      String workingDir,
                                      String currentPlanJson,
                                      String revisionInstruction,
                                      int revision) {
        StringBuilder builder = new StringBuilder();
        builder.append("工作目录（Git 仓库根目录）：").append(workingDir).append('\n');
        builder.append("用户目标：\n").append(goal).append("\n\n");
        if (currentPlanJson != null && !currentPlanJson.isBlank()) {
            builder.append("当前计划（revision ").append(revision).append("）：\n");
            builder.append(currentPlanJson).append("\n\n");
        }
        if (revisionInstruction != null && !revisionInstruction.isBlank()) {
            builder.append("用户对计划的修订要求：\n").append(revisionInstruction).append("\n\n");
            builder.append("请在当前计划基础上修改，输出完整的新计划 JSON。\n");
        } else {
            builder.append("请调查仓库并给出完整计划 JSON。\n");
        }
        builder.append("平台：").append(platform()).append('\n');
        return builder.toString();
    }

    /**
     * 子任务执行系统提示：声明沙箱边界、可写工具用法、禁止 Git 写操作，
     * 并要求歧义或破坏性操作前调用 ask_user、结束时给出验证与遗留风险摘要。
     */
    public static String subtaskSystem(String workingDir,
                                       String goal,
                                       String rootTitle,
                                       EventPayload.PlannedTask task) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是 TaskNest 的软件开发子 Agent。你要在工作目录内完成一个子任务，
                并使用工具直接修改代码、运行测试。
                规则：
                1. 开始前先调查相关文件与项目约定，不要凭空假设目录结构。
                2. 使用 fs.create / fs.write 写完整文件内容；不提供局部 patch 工具。
                3. Shell 只允许本地执行；禁止任何 Git 写操作（commit/checkout/merge/reset 等），
                   Git 由编排器管理。危险命令会被拦截，命中后请改用安全命令或询问用户。
                4. 需求含糊、多个方案取舍、需要放行危险命令或确认破坏性操作时，
                   必须调用 ask_user 请求用户决定，不要自行假设。
                5. 完成后运行与改动相关的测试或构建；无法运行时说明原因。
                6. 最终回复用简洁中文总结：完成了什么、改了哪些文件、运行了哪些验证、遗留风险。
                """);
        builder.append("\n工作目录：").append(workingDir).append('\n');
        builder.append("主任务目标：").append(goal == null ? "" : goal).append('\n');
        builder.append("主任务标题：").append(rootTitle).append('\n');
        builder.append("当前子任务：").append(task.title()).append('\n');
        builder.append("任务描述：").append(task.description() == null ? "" : task.description()).append('\n');
        builder.append("验收标准：\n");
        for (String criterion : task.acceptanceCriteria() == null
                ? List.<String>of() : task.acceptanceCriteria()) {
            builder.append("- ").append(criterion).append('\n');
        }
        builder.append("允许使用的工具：")
                .append(task.allowedTools() == null || task.allowedTools().isEmpty()
                        ? "全部执行工具" : String.join(", ", task.allowedTools()))
                .append('\n');
        if (task.testCommand() != null && !task.testCommand().isBlank()) {
            builder.append("计划中的测试命令：").append(task.testCommand()).append('\n');
        }
        builder.append("平台：").append(platform()).append('\n');
        return builder.toString();
    }

    /** 子任务首轮用户提示；重试时附带上次摘要与必须逐条处理的审核退回意见。 */
    public static String subtaskUser(String previousAttemptSummary,
                                     String reviewFeedback) {
        StringBuilder builder = new StringBuilder();
        builder.append("开始执行当前子任务。\n");
        if (previousAttemptSummary != null && !previousAttemptSummary.isBlank()) {
            builder.append("\n上一次尝试的摘要：\n").append(previousAttemptSummary).append('\n');
        }
        if (reviewFeedback != null && !reviewFeedback.isBlank()) {
            builder.append("\n审核退回意见（必须逐条处理）：\n").append(reviewFeedback).append('\n');
        }
        return builder.toString();
    }

    /**
     * 审核系统提示：规定先看验收标准、亲自执行受限测试、检查 git 副作用，
     * 并输出固定结构的严格 JSON；平台最终按测试证据强制执行 REWORK。
     */
    public static String reviewSystem(String workingDir) {
        return """
                你是 TaskNest 的独立审核 Agent。你只能读取文件、执行受限的测试/构建命令，
                不能修改任何文件（没有 fs.create / fs.write 权限）。
                必做步骤：
                1. 阅读验收标准、变更文件与子 Agent 的完成摘要。
                2. 必须亲自执行一次计划指定的测试命令（若为 none，则记录 UNAVAILABLE）。
                   测试命令只能使用测试、lint、build、类型检查与只读 Git。
                3. 执行 git status --porcelain，确认测试没有留下源码副作用。
                4. 检查实现是否真正满足验收标准，不要因为测试通过就忽略逻辑缺陷。
                5. 需求存在无法判断的歧义时可以调用 ask_user，但不得向用户索取密钥。
                最终回复必须且只能是严格 JSON（不要 Markdown 代码块）：
                {
                  "verdict": "PASS" 或 "REWORK",
                  "summary": "审核结论",
                  "issues": [
                    {"severity": "BLOCKER|MAJOR|MINOR", "file": "路径或 null",
                     "line": 42, "message": "问题", "suggestion": "建议"}
                  ],
                  "testStatus": "PASSED|FAILED|UNAVAILABLE",
                  "testEvidence": [
                    {"command": "npm test", "exitCode": 0, "timedOut": false,
                     "summary": "输出摘要"}
                  ]
                }
                判定规则：
                - 存在 BLOCKER 或 MAJOR 时必须 REWORK；只有 MINOR 时可以 PASS。
                - testStatus=FAILED 时必须 REWORK，并至少给出一条 BLOCKER。
                - 有源码变更但 testStatus=UNAVAILABLE 时必须 REWORK。
                - 测试命令被策略拦截或超时，不得伪造 PASS。
                - 不能因为看不到问题就默认 PASS；必须逐条核对验收标准。
                工作目录：""" + workingDir + "\n平台：" + platform() + "\n";
    }

    /** 组装审核用户提示：验收标准、子任务摘要、变更文件 / diff 与上轮问题清单。 */
    public static String reviewUser(String goal,
                                    EventPayload.PlannedTask task,
                                    String subtaskSummary,
                                    String testCommand,
                                    String changedFiles,
                                    String diffSummary,
                                    String previousIssues) {
        StringBuilder builder = new StringBuilder();
        builder.append("主任务目标：").append(goal == null ? "" : goal).append('\n');
        builder.append("子任务：").append(task.title()).append('\n');
        builder.append("任务描述：").append(task.description() == null ? "" : task.description())
                .append('\n');
        builder.append("验收标准：\n");
        for (String criterion : task.acceptanceCriteria() == null
                ? List.<String>of() : task.acceptanceCriteria()) {
            builder.append("- ").append(criterion).append('\n');
        }
        builder.append("\n子 Agent 完成摘要：\n")
                .append(subtaskSummary == null ? "（无）" : subtaskSummary).append('\n');
        builder.append("\n计划测试命令：")
                .append(testCommand == null || testCommand.isBlank()
                        ? "（未指定，请按项目类型自行选择测试命令）" : testCommand)
                .append('\n');
        builder.append("\n变更文件（相对子任务分支）：\n")
                .append(changedFiles == null || changedFiles.isBlank() ? "（无）" : changedFiles)
                .append('\n');
        builder.append("\n变更摘要（git diff --stat）：\n")
                .append(diffSummary == null || diffSummary.isBlank() ? "（无）" : diffSummary)
                .append('\n');
        if (previousIssues != null && !previousIssues.isBlank()) {
            builder.append("\n上一轮审核问题（本轮必须复查是否已修复）：\n")
                    .append(previousIssues).append('\n');
        }
        return builder.toString();
    }

    /** 最终汇总系统提示：中文叙述、不输出 JSON、不编造未执行的测试。 */
    public static String summarySystem() {
        return """
                你是 TaskNest 的主 Agent。所有子任务已完成并合并。请根据计划、子任务摘要与
                测试/审核结果，用简洁中文输出最终汇总：完成的变更、验证结果、遗留风险与
                用户后续需要注意的事项。不要输出 JSON，不要编造未执行的测试。
                """;
    }

    /** 汇总用户提示：最终计划与平台收集的执行事实。 */
    public static String summaryUser(String goal,
                                     EventPayload.Plan plan,
                                     String facts) {
        StringBuilder builder = new StringBuilder();
        builder.append("主任务目标：").append(goal == null ? "" : goal).append("\n\n");
        builder.append("最终计划：\n");
        for (EventPayload.PlannedTask task : plan.tasks()) {
            builder.append("- ").append(task.title());
            if (task.parentTaskId() != null) {
                builder.append("（子任务）");
            }
            builder.append('\n');
        }
        builder.append("\n执行事实：\n").append(facts).append('\n');
        return builder.toString();
    }

    /**
     * 重排系统提示：只允许新增、修改或删除 PENDING 任务，输出 continue 或 replan 严格 JSON，
     * 并明确不要为重排而重排。
     */
    public static String replanSystem() {
        return """
                你是 TaskNest 的主 Agent，正在两个子任务之间的重排窗口评估计划。
                你可以读取仓库，但不能修改文件。你可以调用 ask_user 询问用户。
                只允许新增任务、修改尚未开始的 PENDING 任务，或移除 PENDING 任务；
                已开始或已完成的任务不可修改，新任务不得依赖已取消任务。
                最终回复必须是严格 JSON（不要 Markdown 代码块），二选一：
                {"action":"continue"}
                或
                {
                  "action":"replan",
                  "reason":"重排原因",
                  "tasks":[ 完整的计划任务数组，格式与规划阶段相同 ]
                }
                如果当前计划仍然合理，返回 continue，不要为了重排而重排。
                """;
    }

    /** 重排用户提示：当前计划、刚完成子任务结果与尚未开始的任务清单。 */
    public static String replanUser(String goal,
                                    EventPayload.Plan currentPlan,
                                    String lastResult,
                                    String remaining) {
        StringBuilder builder = new StringBuilder();
        builder.append("主任务目标：").append(goal == null ? "" : goal).append("\n\n");
        builder.append("当前计划（revision ")
                .append(currentPlan.revision() == null ? 1 : currentPlan.revision())
                .append("）：\n");
        for (EventPayload.PlannedTask task : currentPlan.tasks()) {
            builder.append("- ").append(task.taskId()).append(" | ")
                    .append(task.title());
            if (task.parentTaskId() != null) {
                builder.append(" | dependsOn=")
                        .append(task.dependsOn() == null ? List.of() : task.dependsOn());
            }
            builder.append('\n');
        }
        builder.append("\n刚完成的子任务结果：\n")
                .append(lastResult == null ? "" : lastResult).append('\n');
        builder.append("\n尚未开始的任务：\n")
                .append(remaining == null ? "（无）" : remaining).append('\n');
        builder.append("\n请判断是否需要重排。\n");
        return builder.toString();
    }

    /** 返回操作系统 / 架构与 Shell 类型（cmd.exe 或 /bin/sh），写入提示词。 */
    public static String platform() {
        return System.getProperty("os.name", "unknown") + " / "
                + System.getProperty("os.arch", "unknown")
                + (System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "（cmd.exe）" : "（/bin/sh）");
    }
}
