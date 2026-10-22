package com.tasknest.orchestrator.agent;

/**
 * 重排窗口的结论：继续执行当前计划，或提交一版新的完整计划。
 *
 * <p>由 {@link MainAgent#considerReplan} 返回给编排器。字段组合由工厂方法保证：
 * {@link #keep()} 继续当前计划；{@link #abort()} 表示运行已终止，调用方应立即停止调度；
 * {@link #replan} 时 {@code draft} 非空且已通过 {@link PlanParser} 的两层与依赖校验。
 */
public record ReplanDecision(
        boolean replan,
        String reason,
        PlanDraft draft,
        boolean cancelled
) {

    /** 当前计划仍然合理，继续执行。 */
    public static ReplanDecision keep() {
        return new ReplanDecision(false, null, null, false);
    }

    /** 运行已被取消或终止，不再继续调度。 */
    public static ReplanDecision abort() {
        return new ReplanDecision(false, null, null, true);
    }

    /** 提交一版新的完整计划，{@code draft} 必须已通过结构与依赖校验。 */
    public static ReplanDecision replan(String reason, PlanDraft draft) {
        return new ReplanDecision(true, reason, draft, false);
    }
}
