package com.tasknest.orchestrator.review;

import com.tasknest.event.EventPayload;

import java.util.List;

/**
 * 一次审核的最终结论。平台规则已在此处强制执行，编排器只按 verdict 分支。
 *
 * <p>由 {@code ReviewAgent} 返回。{@code cancelled=true} 表示审核因运行终止未完成，
 * 编排器不应据此发起重做；{@code verdict} 只会是 PASS 或 REWORK，
 * {@code testStatus} 只会是 PASSED / FAILED / UNAVAILABLE。紧凑构造器把 null 列表归一化。
 */
public record ReviewResult(
        String verdict,
        String summary,
        List<EventPayload.ReviewIssue> issues,
        String testStatus,
        List<EventPayload.TestEvidence> testEvidence,
        String modelName,
        long durationMs,
        boolean cancelled
) {

    public ReviewResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        testEvidence = testEvidence == null ? List.of() : List.copyOf(testEvidence);
    }

    /** 是否需要子 Agent 返工；大小写不敏感，服务于编排器分支。 */
    public boolean rework() {
        return "REWORK".equalsIgnoreCase(verdict);
    }

    /**
     * 生成回灌给子 Agent 的退回意见：摘要 + 逐条问题（文件:行、建议），
     * 测试状态为 FAILED 时追加提示。PASS 时也可安全调用，结果只含摘要。
     */
    public String feedback() {
        StringBuilder builder = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            builder.append(summary.trim());
        }
        for (EventPayload.ReviewIssue issue : issues) {
            builder.append("\n- [").append(issue.severity()).append("] ");
            if (issue.file() != null && !issue.file().isBlank()) {
                builder.append(issue.file());
                if (issue.line() != null) {
                    builder.append(':').append(issue.line());
                }
                builder.append(" ");
            }
            builder.append(issue.message() == null ? "" : issue.message());
            if (issue.suggestion() != null && !issue.suggestion().isBlank()) {
                builder.append("（建议：").append(issue.suggestion()).append("）");
            }
        }
        if ("FAILED".equalsIgnoreCase(testStatus)) {
            builder.append("\n测试状态：FAILED，请根据测试输出修复。");
        }
        return builder.toString();
    }
}
