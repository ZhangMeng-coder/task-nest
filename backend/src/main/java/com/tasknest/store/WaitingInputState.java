package com.tasknest.store;

import com.tasknest.event.EventPayload;
import com.tasknest.event.TaskRef;

import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * 当前等待人抉择的问题及其唤醒队列。
 *
 * <p>{@code taskId} 用于校验提交归属；{@code queue} 是规划 / 执行线程阻塞等待
 * 用户输入的单元素交接点，提交方在会话或运行锁内 offer。</p>
 */
public record WaitingInputState(
        String taskId,
        TaskRef task,
        String question,
        List<EventPayload.DecisionOption> options,
        Boolean allowCustom,
        String customPlaceholder,
        BlockingQueue<UserInput> queue
) {
}
