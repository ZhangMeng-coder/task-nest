package com.tasknest.api.dto;

import java.time.Instant;

/**
 * Agent 审核设置视图，用于 {@code GET /api/settings/agent} 与
 * {@code PUT /api/settings/agent} 的响应。
 *
 * <p>设置来自单例表 {@code tn_agent_setting}。{@code reviewEnabled} 控制是否在子任务
 * 完成后调用审核 Agent；{@code reviewMaxReworks} 为审核失败时允许的最大重做次数（0–5），
 * {@code updatedAt} 为最后保存时间。</p>
 */
public record AgentSettingsView(
        boolean reviewEnabled,
        int reviewMaxReworks,
        Instant updatedAt
) {
}
