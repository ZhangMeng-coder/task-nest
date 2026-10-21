package com.tasknest.orchestrator.agent;

public record AgentOutcome(
        String content,
        int toolCalls,
        boolean cancelled,
        int iterations
) {
}
