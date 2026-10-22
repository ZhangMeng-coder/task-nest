package com.tasknest.orchestrator.agent;

/**
 * Agent 循环错误，code 会进入 task_failed / error。
 *
 * <p>属于 Agent 执行层：{@link AgentLoop} 在超过迭代上限时抛出，
 * {@link PlanParser} 与 {@link MainAgent} 在模型输出不符合契约时抛出，
 * 上层编排器（执行运行或规划会话）再转换为失败事件。
 * {@code code} 是稳定的机器可读错误码（如 {@code AGENT_MAX_ITERATIONS}、
 * {@code PLAN_INVALID_OUTPUT}），调用方不应依赖 message 文本做分支判断。
 */
public class AgentException extends RuntimeException {

    /** 机器可读错误码，原样进入 task_failed / error 事件。 */
    private final String code;

    public AgentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
