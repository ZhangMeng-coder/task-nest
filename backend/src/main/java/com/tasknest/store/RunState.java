package com.tasknest.store;

import com.tasknest.event.EventEnvelope;
import com.tasknest.sse.SseConnection;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class RunState {

    private final String runId;
    private final String mockScript;
    private final Instant createdAt;
    private final MainTaskState mainTask;
    private final Object lock = new Object();

    private final List<EventEnvelope> events = new ArrayList<>();
    private final Map<String, TaskNodeState> subtasks = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<SseConnection> subscribers = new CopyOnWriteArrayList<>();

    private long seq;
    private volatile RunStatus status = RunStatus.RUNNING;
    private volatile boolean terminal;
    private volatile Instant updatedAt;
    private WaitingInputState waitingInput;
    private int placeholderOrder = 10_000;

    public RunState(String runId, String mainTaskId, String mockScript, String title, String goal) {
        this.runId = runId;
        this.mockScript = mockScript;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        this.updatedAt = this.createdAt;
        this.mainTask = new MainTaskState(mainTaskId, title, goal);
    }

    public String getRunId() {
        return runId;
    }

    public String getMainTaskId() {
        return mainTask.getMainTaskId();
    }

    public MainTaskState getMainTask() {
        return mainTask;
    }

    public String getMockScript() {
        return mockScript;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Object getLock() {
        return lock;
    }

    public long getLastSeq() {
        return seq;
    }

    public long nextSeq() {
        return ++seq;
    }

    public List<EventEnvelope> getEventsCopy() {
        return List.copyOf(events);
    }

    public void appendEvent(EventEnvelope event) {
        events.add(event);
    }

    public Map<String, TaskNodeState> getSubtasks() {
        return subtasks;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public void setTerminal(boolean terminal) {
        this.terminal = terminal;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public WaitingInputState getWaitingInput() {
        return waitingInput;
    }

    public void setWaitingInput(WaitingInputState waitingInput) {
        this.waitingInput = waitingInput;
    }

    public int nextPlaceholderOrder() {
        return placeholderOrder++;
    }

    public void addSubscriber(SseConnection connection) {
        subscribers.add(connection);
    }

    public void removeSubscriber(SseConnection connection) {
        subscribers.remove(connection);
    }

    public int getSubscriberCount() {
        return subscribers.size();
    }

    public void enqueueSubscribers(EventEnvelope event) {
        for (SseConnection subscriber : subscribers) {
            subscriber.enqueue(event);
        }
    }

    public void closeSubscribersAfterFlush() {
        for (SseConnection subscriber : subscribers) {
            subscriber.closeAfterFlush();
        }
    }
}
