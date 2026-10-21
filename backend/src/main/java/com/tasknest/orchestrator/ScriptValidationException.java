package com.tasknest.orchestrator;

public class ScriptValidationException extends RuntimeException {

    public ScriptValidationException(String message) {
        super(message);
    }

    public ScriptValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
