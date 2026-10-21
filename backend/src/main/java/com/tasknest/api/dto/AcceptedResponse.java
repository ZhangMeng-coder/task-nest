package com.tasknest.api.dto;

public record AcceptedResponse(
        String runId,
        String taskId,
        String selectedOptionId,
        String customInput
) {
    public AcceptedResponse(String runId, String taskId, String selectedOptionId) {
        this(runId, taskId, selectedOptionId, null);
    }
}
