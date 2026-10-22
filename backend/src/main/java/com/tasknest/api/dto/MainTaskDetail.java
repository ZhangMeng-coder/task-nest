package com.tasknest.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 主任务档案详情，由 {@code GET /api/main-tasks/{mainTaskId}} 返回，用于只读回放。
 *
 * <p>{@code mainTask} 为摘要；{@code plan} 在未保存计划时为 {@code null}；
 * {@code subtasks}、{@code prompts}、{@code artifacts} 按事件顺序排列；
 * {@code events} 是持久化的原始事件 JSON，{@code lastSeq} 为其中最大序号。
 * 前端在此模式下不建立 SSE，也不提交抉择。</p>
 */
public record MainTaskDetail(
        MainTaskSummary mainTask,
        PlanDraftView plan,
        List<SubtaskView> subtasks,
        List<PromptView> prompts,
        List<ArtifactView> artifacts,
        List<JsonNode> events,
        long lastSeq
) {
}
