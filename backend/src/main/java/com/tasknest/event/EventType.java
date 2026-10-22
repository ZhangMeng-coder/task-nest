package com.tasknest.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 事件类型枚举，共 17 种（含心跳）。
 *
 * <p>枚举名用于 Java 侧分支，{@link #wireName()} 是 SSE {@code event:} 与
 * 持久化中使用的下划线名称。{@link #isTerminal()} 只把 {@code run_completed}
 * 与 {@code error} 视为运行终止；子任务失败是否重试由 payload 决定。</p>
 */
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
    REVIEW_STARTED("review_started"),
    REVIEW_COMPLETED("review_completed"),
    GIT_CHECKPOINT("git_checkpoint"),
    RUN_COMPLETED("run_completed"),
    ERROR("error"),
    HEARTBEAT("heartbeat");

    private final String wireName;

    EventType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 线上名称（小写下划线），用于 SSE 事件名与数据库存储。
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * 按线上名称反序列化。
     *
     * @throws IllegalArgumentException 名称未知时抛出，由解析边界记录并丢弃坏事件
     */
    @JsonCreator
    public static EventType fromWire(String wireName) {
        for (EventType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知事件类型: " + wireName);
    }

    /**
     * 是否为运行终止事件。只有 {@code run_completed} 与 {@code error} 终止运行；
     * {@code task_failed(retryable=true)} 不是运行终止。
     */
    public boolean isTerminal() {
        return this == RUN_COMPLETED || this == ERROR;
    }
}
