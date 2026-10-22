package com.tasknest.orchestrator.agent;

import com.tasknest.event.EventPayload;
import com.tasknest.event.EventPublisher;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.store.RunState;
import com.tasknest.store.UserInput;
import com.tasknest.store.WaitingInputState;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 执行运行的事件出口。
 *
 * <p>实现 {@link AgentSession}：事件交给 {@link EventPublisher} 同步持久化并推入 SSE；
 * 等待人工输入时把 {@link WaitingInputState} 挂到 {@link RunState}，运行进入终态后
 * 轮询退出并返回 {@code null}。清理等待状态时用身份比较，避免误清后续等待。
 */
public class RunAgentSession implements AgentSession {

    private final RunState run;
    private final EventPublisher eventPublisher;

    public RunAgentSession(RunState run, EventPublisher eventPublisher) {
        this.run = run;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void publish(EventType type, TaskRef task, EventPayload payload) {
        eventPublisher.publish(run, type, task, payload);
    }

    @Override
    public boolean cancelled() {
        return run.isTerminal();
    }

    /**
     * 发出 waiting_input 并阻塞等待用户输入。
     *
     * <p>运行终止、取消或线程中断时返回 {@code null}；收到输入后补发 input_received。
     * 等待状态通过 {@link RunState} 暴露给 REST 抉择接口。
     */
    @Override
    public UserInput awaitInput(TaskRef task, EventPayload.WaitingInput waiting) {
        BlockingQueue<UserInput> queue = new LinkedBlockingQueue<>(1);
        WaitingInputState state = new WaitingInputState(
                task.taskId(),
                task,
                waiting.question(),
                waiting.options(),
                waiting.allowCustom(),
                waiting.customPlaceholder(),
                queue);
        synchronized (run.getLock()) {
            run.setWaitingInput(state);
        }
        eventPublisher.publish(run, EventType.WAITING_INPUT, task, waiting);
        UserInput input = await(run, queue);
        synchronized (run.getLock()) {
            // 只清理本次登记的等待状态，避免覆盖运行期间新产生的 waiting_input。
            if (run.getWaitingInput() == state) {
                run.setWaitingInput(null);
            }
        }
        if (input != null) {
            eventPublisher.publish(run, EventType.INPUT_RECEIVED, task,
                    new EventPayload.InputReceived(input.selectedOptionId(), input.customInput()));
        }
        return input;
    }

    private UserInput await(RunState run, BlockingQueue<UserInput> queue) {
        try {
            while (!run.isTerminal()) {
                UserInput input = queue.poll(500, TimeUnit.MILLISECONDS);
                if (input != null) {
                    return input;
                }
            }
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
