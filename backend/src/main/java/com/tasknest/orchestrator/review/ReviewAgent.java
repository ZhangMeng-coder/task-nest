package com.tasknest.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.event.EventPayload;
import com.tasknest.event.TaskRef;
import com.tasknest.orchestrator.agent.AgentException;
import com.tasknest.orchestrator.agent.AgentLoop;
import com.tasknest.orchestrator.agent.AgentOutcome;
import com.tasknest.orchestrator.agent.AgentSession;
import com.tasknest.orchestrator.agent.AgentSpec;
import com.tasknest.orchestrator.agent.PromptFactory;
import com.tasknest.orchestrator.llm.LlmClient;
import com.tasknest.orchestrator.llm.LlmException;
import com.tasknest.orchestrator.tool.ToolExecutor;
import com.tasknest.orchestrator.tool.WorkspaceSandbox;
import com.tasknest.service.SecretProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 独立审核 Agent：先由平台在子任务分支上执行测试，再让模型只读代码并给出结构化结论。
 * 平台强制执行判定规则，模型输出不能绕过失败测试或源码副作用。
 *
 * <p>由编排器在子任务提交后调用。流程固定为：取消检查 → 平台测试与副作用检查 →
 * 模型审核（最多 2 次调用）→ 严格 JSON 解析 → {@code enforce} 重写 verdict。
 * 审核模式没有写工具，Shell 受 {@code ReviewCommandPolicy} 限制；模型结论只是输入，
 * 平台证据优先。模型两次调用都失败时抛 {@code REVIEW_UNAVAILABLE}。
 */
@Component
public class ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final Set<String> NON_SOURCE_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "rst", "adoc",
            "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties");

    private final LlmClient llm;
    private final ObjectMapper objectMapper;
    private final SecretProvider secrets;
    private final ReviewTestRunner testRunner;

    public ReviewAgent(LlmClient llm,
                       ObjectMapper objectMapper,
                       SecretProvider secrets,
                       ReviewTestRunner testRunner) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.secrets = secrets;
        this.testRunner = testRunner;
    }

    /**
     * 执行一次完整审核并返回平台强制后的结论。
     *
     * <p>先检查取消，再运行平台测试；测试产生工作区副作用时直接 REWORK，不再调用模型。
     * 模型每次输出都会校验 verdict / testStatus 结构，失败时最多重试一次。
     * 取消返回 {@code cancelled=true} 的结果，编排器不应据此发起重做。
     *
     * @param context 审核输入，含变更文件、测试命令与上一轮问题
     * @throws AgentException REVIEW_UNAVAILABLE，模型调用两次均失败
     */
    public ReviewResult review(AgentSession session, TaskRef taskRef,
                               Path root, ReviewContext context) {
        long started = System.nanoTime();
        // 运行已取消时不再启动测试进程，避免取消后仍长时间占用工作区。
        if (session.cancelled()) {
            return cancelledWithoutTest(started);
        }
        ReviewTestRunner.Evidence evidence = testRunner.run(
                root, context.testCommand(), secrets::values);
        // 测试必须只读；出现工作区副作用时平台直接判 REWORK，不让模型推翻。
        if (evidence.hasSideEffects()) {
            return sideEffectResult(evidence, started);
        }
        if (session.cancelled()) {
            return cancelledResult(evidence, started);
        }

        String userPrompt = PromptFactory.reviewUser(
                context.goal(),
                context.task(),
                context.subtaskSummary(),
                context.testCommand(),
                String.join("\n", context.changedFiles()),
                context.diffSummary(),
                context.previousIssues()) + evidenceBlock(evidence);
        AgentSpec spec = AgentSpec.review(
                PromptFactory.reviewSystem(context.workingDir()), userPrompt);

        RuntimeException lastFailure = null;
        // 模型输出可能不稳定，最多重试一次；两次都失败才判定审核不可用。
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ToolExecutor executor = new ToolExecutor(
                        new WorkspaceSandbox(root), secrets::values);
                AgentLoop loop = new AgentLoop(llm, executor, objectMapper);
                AgentOutcome outcome = loop.run(session, taskRef, spec);
                if (outcome.cancelled()) {
                    return cancelledResult(evidence, started);
                }
                ReviewResult parsed = parse(outcome.content(), evidence);
                return enforce(parsed, evidence, context, started);
            } catch (LlmException | AgentException exception) {
                lastFailure = exception;
                log.warn("审核 Agent 第 {} 次调用失败: {}", attempt, exception.getMessage());
            }
        }
        throw new AgentException("REVIEW_UNAVAILABLE",
                "审核 Agent 调用失败: "
                        + (lastFailure == null ? "未知错误" : lastFailure.getMessage()),
                lastFailure);
    }

    /** 解析模型 JSON；结构错误或 verdict / testStatus 非法时抛 REVIEW_INVALID_OUTPUT。 */
    private ReviewResult parse(String content, ReviewTestRunner.Evidence evidence) {
        JsonNode root = readJson(content);
        String rawVerdict = root.path("verdict").asText("").trim().toUpperCase(Locale.ROOT);
        if (!"PASS".equals(rawVerdict) && !"REWORK".equals(rawVerdict)) {
            throw new AgentException("REVIEW_INVALID_OUTPUT",
                    "审核输出缺少合法的 verdict");
        }
        String summary = root.path("summary").asText("");
        List<EventPayload.ReviewIssue> issues = new ArrayList<>();
        JsonNode issueNodes = root.path("issues");
        if (issueNodes.isArray()) {
            for (JsonNode node : issueNodes) {
                String severity = node.path("severity").asText("MINOR")
                        .trim().toUpperCase(Locale.ROOT);
                if (!Set.of("BLOCKER", "MAJOR", "MINOR").contains(severity)) {
                    severity = "MINOR";
                }
                issues.add(new EventPayload.ReviewIssue(
                        severity,
                        textOrNull(node, "file"),
                        node.hasNonNull("line") && node.path("line").canConvertToInt()
                                ? node.path("line").asInt() : null,
                        node.path("message").asText(""),
                        textOrNull(node, "suggestion")));
            }
        }
        String rawStatus = root.path("testStatus").asText("")
                .trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PASSED", "FAILED", "UNAVAILABLE").contains(rawStatus)) {
            throw new AgentException("REVIEW_INVALID_OUTPUT",
                    "审核输出缺少合法的 testStatus");
        }
        List<EventPayload.TestEvidence> evidenceList = new ArrayList<>();
        JsonNode evidenceNodes = root.path("testEvidence");
        if (evidenceNodes.isArray()) {
            for (JsonNode node : evidenceNodes) {
                evidenceList.add(new EventPayload.TestEvidence(
                        node.path("command").asText(""),
                        node.hasNonNull("exitCode") && node.path("exitCode").canConvertToInt()
                                ? node.path("exitCode").asInt() : null,
                        node.path("timedOut").asBoolean(false),
                        truncate(node.path("summary").asText(""), 1000)));
            }
        }
        evidenceList.add(toEventEvidence(evidence));
        String modelName = llm.modelName();
        return new ReviewResult(rawVerdict, summary, issues, rawStatus, evidenceList,
                modelName, 0, false);
    }

    /**
     * 用平台测试证据覆盖模型结论：失败测试、阻塞问题、源码变更但测试不可用都强制 REWORK；
     * REWORK 但模型未给出问题时补一条通用 MAJOR，保证退回意见可执行。
     */
    private ReviewResult enforce(ReviewResult parsed,
                                 ReviewTestRunner.Evidence evidence,
                                 ReviewContext context,
                                 long started) {
        List<EventPayload.ReviewIssue> issues = new ArrayList<>(parsed.issues());
        String testStatus = parsed.testStatus();
        String verdict = parsed.verdict();

        // 平台证据只能收紧结论：失败不能翻成通过，通过可以修正模型的 UNAVAILABLE。
        if ("FAILED".equals(evidence.testStatus())) {
            testStatus = "FAILED";
        } else if ("PASSED".equals(evidence.testStatus())
                && "UNAVAILABLE".equals(testStatus)) {
            testStatus = "PASSED";
        }

        if ("FAILED".equals(testStatus)) {
            verdict = "REWORK";
            boolean referenced = issues.stream()
                    .anyMatch(issue -> "BLOCKER".equals(issue.severity()));
            if (!referenced) {
                issues.add(new EventPayload.ReviewIssue(
                        "BLOCKER", null, null,
                        "平台执行的测试失败: " + evidence.command()
                                + (evidence.exitCode() == null ? ""
                                : "（退出码 " + evidence.exitCode() + "）"),
                        "根据测试输出修复失败用例后重新提交"));
            }
        }
        boolean blocking = issues.stream().anyMatch(issue ->
                "BLOCKER".equals(issue.severity()) || "MAJOR".equals(issue.severity()));
        if (blocking) {
            verdict = "REWORK";
        }
        if ("UNAVAILABLE".equals(testStatus)
                && hasSourceChanges(context.changedFiles())) {
            verdict = "REWORK";
            addMissingIssue(issues, "UNAVAILABLE",
                    "存在源码变更但测试不可用，无法验证实现",
                    "提供可执行的测试命令，或修复测试环境后重新审核");
        }
        if ("REWORK".equals(verdict)) {
            addMissingIssue(issues, "MAJOR",
                    parsed.summary() == null || parsed.summary().isBlank()
                            ? "审核未通过" : parsed.summary(),
                    "根据审核意见修改后重新提交");
        }
        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new ReviewResult(verdict, parsed.summary(), issues, testStatus,
                parsed.testEvidence(), parsed.modelName(), duration, false);
    }

    private void addMissingIssue(List<EventPayload.ReviewIssue> issues,
                                 String severity, String message, String suggestion) {
        if (issues.isEmpty()) {
            issues.add(new EventPayload.ReviewIssue(severity, null, null, message, suggestion));
        }
    }

    private ReviewResult sideEffectResult(ReviewTestRunner.Evidence evidence, long started) {
        List<EventPayload.ReviewIssue> issues = new ArrayList<>();
        issues.add(new EventPayload.ReviewIssue(
                "BLOCKER", null, null,
                "测试执行后工作区出现新的改动，判定 REVIEW_SIDE_EFFECT",
                "测试必须只读，不得修改源码或生成未忽略文件"));
        for (String line : evidence.sideEffects()) {
            issues.add(new EventPayload.ReviewIssue(
                    "MAJOR", line, null, "测试产生的副作用文件", "移除该副作用"));
        }
        return new ReviewResult("REWORK",
                "测试留下工作区副作用，本次审核失败",
                issues, evidence.testStatus(), List.of(toEventEvidence(evidence)),
                llm.modelName(), elapsed(started), false);
    }

    private ReviewResult cancelledResult(ReviewTestRunner.Evidence evidence, long started) {
        return new ReviewResult("REWORK", "运行已结束，审核被取消",
                List.of(), evidence.testStatus(), List.of(toEventEvidence(evidence)),
                llm.modelName(), elapsed(started), true);
    }

    private ReviewResult cancelledWithoutTest(long started) {
        return new ReviewResult("REWORK", "运行已结束，审核被取消",
                List.of(), "UNAVAILABLE", List.of(),
                llm.modelName(), elapsed(started), true);
    }

    private EventPayload.TestEvidence toEventEvidence(ReviewTestRunner.Evidence evidence) {
        return new EventPayload.TestEvidence(
                evidence.command(), evidence.exitCode(), evidence.timedOut(),
                truncate(evidence.summary(), 1000));
    }

    private String evidenceBlock(ReviewTestRunner.Evidence evidence) {
        StringBuilder builder = new StringBuilder();
        builder.append("\n\n平台独立执行的测试结果（已提交的子任务分支，作为权威证据）：\n");
        builder.append("- 命令：").append(evidence.command()).append('\n');
        builder.append("- 退出码：")
                .append(evidence.exitCode() == null ? "无" : evidence.exitCode()).append('\n');
        builder.append("- 是否超时：").append(evidence.timedOut() ? "是" : "否").append('\n');
        builder.append("- 测试状态：").append(evidence.testStatus()).append('\n');
        builder.append("- 输出摘要：\n")
                .append(evidence.summary() == null || evidence.summary().isBlank()
                        ? "（无输出）" : truncate(evidence.summary(), 4000))
                .append('\n');
        builder.append("请在结论中引用该证据；你仍可自行执行只读检查，但不得修改文件。\n");
        return builder.toString();
    }

    private JsonNode readJson(String content) {
        String text = content == null ? "" : content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AgentException("REVIEW_INVALID_OUTPUT", "审核输出不是合法 JSON");
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(start, end + 1));
            if (node == null || !node.isObject()) {
                throw new AgentException("REVIEW_INVALID_OUTPUT", "审核输出不是 JSON 对象");
            }
            return node;
        } catch (AgentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AgentException("REVIEW_INVALID_OUTPUT",
                    "审核输出 JSON 解析失败: " + exception.getMessage(), exception);
        }
    }

    private boolean hasSourceChanges(List<String> changedFiles) {
        for (String file : changedFiles) {
            String name = file == null ? "" : file.trim();
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            // 仅文档 / 配置类扩展名不要求可执行测试，其余变更都视为源码。
            if (!NON_SOURCE_EXTENSIONS.contains(extension)) {
                return true;
            }
        }
        return false;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
