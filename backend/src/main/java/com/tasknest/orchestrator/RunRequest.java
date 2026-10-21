package com.tasknest.orchestrator;

public record RunRequest(
        String mockScript,
        String mainTaskId,
        String title,
        String goal
) {
}
