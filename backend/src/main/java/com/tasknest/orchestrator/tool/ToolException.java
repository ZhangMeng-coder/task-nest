package com.tasknest.orchestrator.tool;

/**
 * 工具执行错误。code 会原样写入 tool_result.result.error.code。
 *
 * <p>由 {@link ToolExecutor}、{@link WorkspaceSandbox} 与命令策略抛出。
 * {@code rule} 是触发拦截的具体策略名（可能为 null）。{@link com.tasknest.orchestrator.agent.AgentLoop}
 * 捕获后转为 ERROR 工具结果回传模型，让模型改换安全方案，而不是终止整个运行。
 */
public class ToolException extends RuntimeException {

    /** 机器可读错误码，进入 tool_result 的 error.code。 */
    private final String code;
    /** 触发的策略名，便于审计；非策略类错误为 null。 */
    private final String rule;

    public ToolException(String code, String message) {
        this(code, message, null);
    }

    public ToolException(String code, String message, String rule) {
        super(message);
        this.code = code;
        this.rule = rule;
    }

    /** 机器可读错误码。 */
    public String code() {
        return code;
    }

    /** 触发的策略名，可能为 null。 */
    public String rule() {
        return rule;
    }
}
