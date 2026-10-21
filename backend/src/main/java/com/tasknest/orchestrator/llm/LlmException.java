package com.tasknest.orchestrator.llm;

/** LLM 调用错误。code 会写入 error 事件或 tool_result。 */
public class LlmException extends RuntimeException {

    private final String code;

    public LlmException(String code, String message) {
        super(message);
        this.code = code;
    }

    public LlmException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
