package com.tasknest.api.dto;

import com.tasknest.event.EventPayload;

import java.time.Instant;
import java.util.List;

/**
 * 提示词快照，随主任务档案详情返回，用于回放子任务每次尝试的上下文。
 *
 * <p>{@code taskId} 与 {@code seq} 定位事件；{@code systemPrompt} / {@code userPrompt}
 * 是组装前的内容，{@code contextSnippets} 为选入的上下文片段，{@code finalPrompt} 为最终
 * 发送给模型的文本。重试时同一任务可能出现多条记录。</p>
 */
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
