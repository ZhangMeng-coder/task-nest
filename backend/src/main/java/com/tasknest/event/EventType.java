package com.tasknest.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EventType {

    RUN_STARTED("run_started"),
    PLAN("plan"),
    TASK_STARTED("task_started"),
    TASK_THOUGHT("task_thought"),
    PROMPT_ASSEMBLED("prompt_assembled"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    WAITING_INPUT("waiting_input"),
    INPUT_RECEIVED("input_received"),
    TASK_COMPLETED("task_completed"),
    TASK_FAILED("task_failed"),
    RUN_COMPLETED("run_completed"),
    ERROR("error"),
    HEARTBEAT("heartbeat");

    private final String wireName;

    EventType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static EventType fromWire(String wireName) {
        for (EventType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知事件类型: " + wireName);
    }

    public boolean isTerminal() {
        return this == RUN_COMPLETED || this == ERROR;
    }
}
