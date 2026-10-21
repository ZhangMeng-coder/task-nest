package com.tasknest.event;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ToolStatus {

    SUCCESS("success"),
    ERROR("error");

    private final String wireName;

    ToolStatus(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public static ToolStatus fromWire(String wireName) {
        for (ToolStatus status : values()) {
            if (status.wireName.equals(wireName)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知工具状态: " + wireName);
    }
}
