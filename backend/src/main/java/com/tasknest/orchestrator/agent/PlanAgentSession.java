package com.tasknest.orchestrator.agent;

import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.service.PlanSessionState;
import com.tasknest.service.PlanSessionStore;
import com.tasknest.store.UserInput;
import com.tasknest.store.WaitingInputState;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 规划会话的事件出口；runId 使用 planSessionId。
 *
 * <p>实现 {@link AgentSession}，把 Agent 事件写入 {@link PlanSessionStore} 并由规划 SSE
 * 推送给前端。等待人工输入时登记 {@link WaitingInputState}，会话进入终态则停止等待；
 * 收到输入后清理等待状态并补发 input_received。
 */
public class PlanAgentSession implements AgentSession {

    private final PlanSessionStore store;
    private final PlanSessionState state;

    public PlanAgentSession(PlanSessionStore store, PlanSessionState state) {
        this.store = store;
        this.state = state;
    }

    @Override
    public void publish(EventType type, TaskRef task, EventPayload payload) {
        store.publish(state, type, task, payload);
    }

    @Override
    public boolean cancelled() {
        return state.isTerminal();
    }

    /**
     * 发出 waiting_input 并阻塞等待用户输入。
     *
     * <p>每 500ms 轮询一次会话终态，会话结束、取消或线程中断时返回 {@code null}。
     * 等待状态在返回前清理，成功收到输入时补发 input_received。
     */
    @Override
    public UserInput awaitInput(TaskRef task, EventPayload.WaitingInput waiting) {
        // 有界队列容量 1：每次等待只接受一次提交，避免重复点击堆积输入。
        BlockingQueue<UserInput> queue = new LinkedBlockingQueue<>(1);
        WaitingInputState waitingState = new WaitingInputState(
                task.taskId(),
                task,
                waiting.question(),
                waiting.options(),
                waiting.allowCustom(),
                waiting.customPlaceholder(),
                queue);
        store.setWaiting(state, waitingState);
        store.publish(state, EventType.WAITING_INPUT, task, waiting);
        UserInput input = await(queue);
        store.clearWaiting(state, waitingState);
        if (input != null) {
            store.publish(state, EventType.INPUT_RECEIVED, task,
                    new EventPayload.InputReceived(input.selectedOptionId(), input.customInput()));
        }
        return input;
    }

    private UserInput await(BlockingQueue<UserInput> queue) {
        try {
            while (!state.isTerminal()) {
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
