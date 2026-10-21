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

/** 规划会话的事件出口；runId 使用 planSessionId。 */
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

    @Override
    public UserInput awaitInput(TaskRef task, EventPayload.WaitingInput waiting) {
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
