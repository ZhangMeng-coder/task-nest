package com.tasknest.event;

/**
 * 事件归属的任务引用。{@code parentTaskId} 为 {@code null} 表示主任务（计划根节点），
 * 非空且等于根节点 ID 表示直属子任务；界面固定只有这两层。
 */
public record TaskRef(
        String taskId,
        String parentTaskId,
        String title,
        String agentName
) {
}
