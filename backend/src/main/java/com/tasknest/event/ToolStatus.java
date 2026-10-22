package com.tasknest.event;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 工具执行结果状态，线上取值为 {@code success} / {@code error}。
 */
public enum ToolStatus {

    SUCCESS("success"),
    ERROR("error");

    private final String wireName;

    ToolStatus(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 线上名称，用于 JSON 序列化。
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * 按线上名称反序列化。
     *
     * @throws IllegalArgumentException 名称未知时抛出
     */
    public static ToolStatus fromWire(String wireName) {
        for (ToolStatus status : values()) {
            if (status.wireName.equals(wireName)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知工具状态: " + wireName);
    }
}
