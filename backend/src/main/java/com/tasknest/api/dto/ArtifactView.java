package com.tasknest.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 子任务产物视图，随主任务档案详情（{@code GET /api/main-tasks/{mainTaskId}}）返回。
 *
 * <p>{@code kind} 取值为 {@code THOUGHT}、{@code TOOL_CALL} 或 {@code TOOL_RESULT}；
 * {@code seq} 是对应事件的序号，{@code callId} / {@code toolName} 用于配对调用与结果。
 * 工具调用未返回时 {@code status} / {@code durationMs} 为空，返回后由对应结果回填；
 * {@code content} 为原始 JSON，无法解析时可为 {@code null}。</p>
 */
public record ArtifactView(
        String taskId,
        long seq,
        String kind,
        String callId,
        String toolName,
        String status,
        Long durationMs,
        JsonNode content,
        Instant createdAt
) {
}
