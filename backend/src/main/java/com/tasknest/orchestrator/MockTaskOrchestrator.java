package com.tasknest.orchestrator;

import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.config.TaskNestProperties;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventPublisher;
import com.tasknest.event.EventType;
import com.tasknest.store.RunState;
import com.tasknest.store.RunStore;
import com.tasknest.store.WaitingInputState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

// P3: 替换为 SpringAiTaskOrchestrator；TaskOrchestrator 接口、SSE 层与事件契约保持不变。
@Component
public class MockTaskOrchestrator implements TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MockTaskOrchestrator.class);

    private final RunStore runStore;
    private final EventPublisher eventPublisher;
    private final MockScriptLoader scriptLoader;
    private final TaskNestProperties properties;
    private final ExecutorService orchestrationExecutor;

    public MockTaskOrchestrator(RunStore runStore,
                                EventPublisher eventPublisher,
                                MockScriptLoader scriptLoader,
                                TaskNestProperties properties,
                                @Qualifier("orchestrationExecutor") ExecutorService orchestrationExecutor) {
        this.runStore = runStore;
        this.eventPublisher = eventPublisher;
        this.scriptLoader = scriptLoader;
        this.properties = properties;
        this.orchestrationExecutor = orchestrationExecutor;
    }

    @Override
    public String start(String runId, RunRequest request) {
        MockScript script = scriptLoader.load(request.mockScript());
        RunState run = runStore.create(
                runId, request.mainTaskId(), request.mockScript(), request.title(), request.goal());
        try {
            orchestrationExecutor.submit(() -> execute(run, script.events(), request));
        } catch (RejectedExecutionException exception) {
            eventPublisher.publish(run, EventType.ERROR, null,
                    new EventPayload.Error("ORCHESTRATOR_REJECTED", "编排线程池拒绝执行"));
            throw new BusinessException(ErrorCode.INTERNAL, "无法启动编排线程");
        }
        return runId;
    }

    @Override
    public void submitInput(String runId, String taskId, String optionId) {
        RunState run = runStore.require(runId);
        synchronized (run.getLock()) {
            boolean mainTask = taskId.equals(run.getMainTask().getRootTaskId());
            if (!mainTask && !run.getSubtasks().containsKey(taskId)) {
                throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
            }
            if (run.isTerminal()) {
                throw new BusinessException(ErrorCode.RUN_ENDED);
            }
            WaitingInputState waiting = run.getWaitingInput();
            if (waiting == null || !waiting.taskId().equals(taskId)) {
                throw new BusinessException(ErrorCode.NOT_WAITING_INPUT);
            }
            boolean valid = waiting.options().stream()
                    .anyMatch(option -> option.id().equals(optionId));
            if (!valid) {
                throw new BusinessException(ErrorCode.INVALID_OPTION);
            }
            run.setWaitingInput(null);
            waiting.queue().offer(optionId);
        }
    }

    private void execute(RunState run, List<MockEvent> events, RunRequest request) {
        try {
            for (MockEvent event : events) {
                sleep(run, event.delayMs());
                if (event.type() == EventType.WAITING_INPUT) {
                    String optionId = pauseForInput(run, event);
                    publishInputReceived(run, event, optionId);
                    executeBranch(run, event.branches().get(optionId));
                } else {
                    publish(run, event, applyGoalOverride(event, request));
                }
            }
            if (!run.isTerminal()) {
                eventPublisher.publish(run, EventType.ERROR, null,
                        new EventPayload.Error("SCRIPT_INCOMPLETE", "脚本未产生终止事件"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!run.isTerminal()) {
                eventPublisher.publish(run, EventType.ERROR, null,
                        new EventPayload.Error("ORCHESTRATOR_INTERRUPTED", "编排线程被中断"));
            }
        } catch (RuntimeException exception) {
            log.error("Mock orchestration failed for run {}", run.getRunId(), exception);
            if (!run.isTerminal()) {
                eventPublisher.publish(run, EventType.ERROR, null,
                        new EventPayload.Error("ORCHESTRATOR_ERROR", "编排执行异常: " + exception.getMessage()));
            }
        }
    }

    private void executeBranch(RunState run, List<MockEvent> branch) throws InterruptedException {
        if (branch == null) {
            return;
        }
        for (MockEvent event : branch) {
            if (run.isTerminal()) {
                return;
            }
            sleep(run, event.delayMs());
            if (event.type() == EventType.WAITING_INPUT) {
                String optionId = pauseForInput(run, event);
                publishInputReceived(run, event, optionId);
                executeBranch(run, event.branches().get(optionId));
            } else {
                publish(run, event, event.payload());
            }
        }
    }

    private String pauseForInput(RunState run, MockEvent event) throws InterruptedException {
        if (!(event.payload() instanceof EventPayload.WaitingInput waitingInput)) {
            throw new IllegalStateException("waiting_input 载荷类型错误");
        }
        BlockingQueue<String> queue = new LinkedBlockingQueue<>(1);
        WaitingInputState state = new WaitingInputState(
                event.task().taskId(),
                event.task(),
                waitingInput.question(),
                waitingInput.options(),
                queue);
        synchronized (run.getLock()) {
            run.setWaitingInput(state);
        }
        publish(run, event, event.payload());
        return queue.take();
    }

    private void publishInputReceived(RunState run, MockEvent mockEvent, String optionId) {
        eventPublisher.publish(
                run,
                EventType.INPUT_RECEIVED,
                mockEvent.task(),
                new EventPayload.InputReceived(optionId));
    }

    private EventPayload applyGoalOverride(MockEvent event, RunRequest request) {
        if (event.type() == EventType.RUN_STARTED
                && request.goal() != null
                && !request.goal().isBlank()) {
            return new EventPayload.RunStarted(request.goal());
        }
        return event.payload();
    }

    private void publish(RunState run, MockEvent mockEvent, EventPayload payload) {
        eventPublisher.publish(
                run,
                mockEvent.type(),
                mockEvent.task(),
                payload,
                mockEvent.eventId(),
                mockEvent.dispatchDelayMs(),
                mockEvent.dropDispatch());
    }

    private void sleep(RunState run, long delayMs) throws InterruptedException {
        long scaled = Math.round(delayMs * properties.getMock().getDelayScale());
        if (scaled <= 0) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(scaled);
        while (true) {
            if (run.isTerminal()) {
                return;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
        }
    }
}
