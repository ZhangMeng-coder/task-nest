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

/** 执行运行的事件出口。 */
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
