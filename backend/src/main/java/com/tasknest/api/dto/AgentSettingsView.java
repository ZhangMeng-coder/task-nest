package com.tasknest.api.dto;

import java.time.Instant;

public record AgentSettingsView(
        boolean reviewEnabled,
        int reviewMaxReworks,
        Instant updatedAt
) {
}
