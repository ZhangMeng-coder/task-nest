package com.tasknest.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 规划会话修订请求，对应
 * {@code POST /api/main-tasks/plan-sessions/{planSessionId}/revisions}。
 *
 * <p>{@code instruction} 必填且最多 4000 字；只有 {@code READY} 状态的会话可修订，
 * 修订进度通过规划 SSE 推送。</p>
 */
public record RevisePlanSessionRequest(
        @NotBlank(message = "修订提示不能为空")
        @Size(max = 4000, message = "修订提示最多 4000 字")
        String instruction
) {
}
