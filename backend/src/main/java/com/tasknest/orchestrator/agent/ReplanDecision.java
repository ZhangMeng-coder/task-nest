package com.tasknest.orchestrator.agent;

/** 重排窗口的结论：继续执行当前计划，或提交一版新的完整计划。 */
public record ReplanDecision(
        boolean replan,
        String reason,
        PlanDraft draft,
        boolean cancelled
) {

    public static ReplanDecision keep() {
        return new ReplanDecision(false, null, null, false);
    }

    public static ReplanDecision abort() {
        return new ReplanDecision(false, null, null, true);
    }

    public static ReplanDecision replan(String reason, PlanDraft draft) {
        return new ReplanDecision(true, reason, draft, false);
    }
}
