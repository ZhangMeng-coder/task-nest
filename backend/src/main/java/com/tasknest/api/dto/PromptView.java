package com.tasknest.api.dto;

import com.tasknest.event.EventPayload;

import java.time.Instant;
import java.util.List;

public record PromptView(
        String taskId,
        long seq,
        String systemPrompt,
        String userPrompt,
        List<EventPayload.ContextSnippet> contextSnippets,
        String finalPrompt,
        Instant createdAt
) {
}
