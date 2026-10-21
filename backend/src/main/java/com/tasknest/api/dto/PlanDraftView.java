package com.tasknest.api.dto;

import java.util.List;

/**
 * 计划表草案/档案。markdown 是主任务详情直接展示的内容，rows 用于继续修订。
 */
public record PlanDraftView(
        String mainTaskId,
        String title,
        String goal,
        String mockScript,
        int version,
        String markdown,
        List<PlanRowView> rows,
        List<String> revisions
) {
    public PlanDraftView withMainTaskId(String value) {
        return new PlanDraftView(value, title, goal, mockScript, version, markdown, rows, revisions);
    }
}
