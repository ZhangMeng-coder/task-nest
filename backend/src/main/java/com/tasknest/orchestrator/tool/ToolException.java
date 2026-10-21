package com.tasknest.orchestrator.tool;

/** 工具执行错误。code 会原样写入 tool_result.result.error.code。 */
public class ToolException extends RuntimeException {

    private final String code;
    private final String rule;

    public ToolException(String code, String message) {
        this(code, message, null);
    }

    public ToolException(String code, String message, String rule) {
        super(message);
        this.code = code;
        this.rule = rule;
    }

    public String code() {
        return code;
    }

    public String rule() {
        return rule;
    }
}
