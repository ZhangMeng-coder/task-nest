package com.tasknest.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

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
