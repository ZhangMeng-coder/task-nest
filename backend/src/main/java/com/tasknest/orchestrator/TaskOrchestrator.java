package com.tasknest.orchestrator;

/**
 * 编排器抽象。P3 用 SpringAiTaskOrchestrator 替换 Mock 实现时，此接口保持不变。
 */
public interface TaskOrchestrator {

    String start(String runId, RunRequest request);

    void submitInput(String runId, String taskId, String optionId, String customInput);
}
