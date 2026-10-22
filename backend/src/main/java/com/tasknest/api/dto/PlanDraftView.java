package com.tasknest.api.dto;

import java.util.List;

/**
 * 计划表档案视图，用于规划确认与主任务详情。
 *
 * <p>{@code markdown} 是详情页直接展示的内容，{@code rows} 用于继续修订；
 * {@code version} 是持久化计划表的版本，{@code revision} 是计划重排修订号
 * （从 1 开始，旧构造器缺省时取 {@code max(1, version)}）。{@code mainTaskId} 在保存前
 * 可为 {@code null}。</p>
 */
public record PlanDraftView(
        String mainTaskId,
        String title,
        String goal,
        int version,
        String markdown,
        List<PlanRowView> rows,
        List<String> revisions,
        int revision
) {
    public PlanDraftView(String mainTaskId,
                         String title,
                         String goal,
                         int version,
                         String markdown,
                         List<PlanRowView> rows,
                         List<String> revisions) {
        this(mainTaskId, title, goal, version, markdown, rows, revisions,
                Math.max(1, version));
    }

    public PlanDraftView withMainTaskId(String value) {
        return new PlanDraftView(value, title, goal, version, markdown, rows,
                revisions, revision);
    }
}
