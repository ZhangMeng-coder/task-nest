package com.tasknest.orchestrator.llm;

/**
 * LLM 调用错误。code 会写入 error 事件或 tool_result。
 *
 * <p>由 {@link LlmClient} 抛出，主要错误码：LLM_NOT_CONFIGURED、LLM_REQUEST_FAILED、
 * LLM_TOOL_CALLING_UNSUPPORTED。Agent 层按 code 决定提示配置、重试或降级；
 * message 面向用户，可能包含模型服务返回的错误摘要。
 */
public class LlmException extends RuntimeException {

    /** 机器可读错误码，进入 error 事件或 tool_result。 */
    private final String code;

    public LlmException(String code, String message) {
        super(message);
        this.code = code;
    }

    public LlmException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 机器可读错误码。 */
    public String code() {
        return code;
    }
}
