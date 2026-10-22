package com.tasknest.orchestrator;

/**
 * 任务编排器抽象：接收一次运行的启动与人工抉择，具体执行由实现类在后台线程池中完成。
 * 唯一实现是 {@link CodingTaskOrchestrator}；SSE 与 REST 层只依赖本接口。
 */
public interface TaskOrchestrator {

    /**
     * 启动一次运行并返回 runId。参数或资源校验失败时同步抛业务异常；进入后台后的失败
     * 通过 error 事件报告，不再抛回调用方。
     */
    String start(String runId, RunRequest request);

    /**
     * 向指定任务的等待项提交人工抉择。实现需校验任务归属与当前等待状态，非法时抛业务异常；
     * 成功投递后唤醒编排线程继续执行。
     */
    void submitInput(String runId, String taskId, String optionId, String customInput);
}
