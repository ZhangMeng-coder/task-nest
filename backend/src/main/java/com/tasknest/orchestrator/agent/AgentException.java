package com.tasknest.orchestrator.agent;

/** Agent 循环错误，code 会进入 task_failed / error。 */
public class AgentException extends RuntimeException {

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
