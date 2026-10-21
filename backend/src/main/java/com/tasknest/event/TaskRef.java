package com.tasknest.event;

public record TaskRef(
        String taskId,
        String parentTaskId,
        String title,
        String agentName
) {
}
