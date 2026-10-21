package com.tasknest.api.dto;

public record ConfirmPlanSessionResponse(
        String mainTaskId,
        String runId,
        String workingDir,
        int planVersion
) {
}
