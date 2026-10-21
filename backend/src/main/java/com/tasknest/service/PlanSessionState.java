package com.tasknest.service;

import com.tasknest.event.EventEnvelope;
import com.tasknest.event.EventPayload;
import com.tasknest.sse.SseConnection;
import com.tasknest.store.WaitingInputState;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 内存中的规划会话；确认前不创建主任务。 */
public class PlanSessionState {

    private final String planSessionId;
    private final String goal;
    private final String workingDir;
    private final String mockScript;
    private final Instant createdAt;
    private final Object lock = new Object();
    private final List<EventEnvelope> events = new ArrayList<>();
    private final CopyOnWriteArrayList<SseConnection> subscribers = new CopyOnWriteArrayList<>();

    private long seq;
    private volatile PlanSessionStatus status = PlanSessionStatus.RUNNING;
    private volatile Instant updatedAt;
    private volatile EventPayload.Plan plan;
    private volatile String planJson;
    private volatile int revision = 1;
    private final List<String> revisions = new ArrayList<>();
    private volatile String error;
    private WaitingInputState waitingInput;

    public PlanSessionState(String planSessionId, String goal, String workingDir,
                            String mockScript) {
        this.planSessionId = planSessionId;
        this.goal = goal;
        this.workingDir = workingDir;
        this.mockScript = mockScript;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        this.updatedAt = this.createdAt;
    }

    public String getPlanSessionId() {
        return planSessionId;
    }

    public String getGoal() {
        return goal;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public String getMockScript() {
        return mockScript;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
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

    public PlanSessionStatus getStatus() {
        return status;
    }

    public void setStatus(PlanSessionStatus status) {
        this.status = status;
    }

    public boolean isTerminal() {
        return status.terminal();
    }

    public EventPayload.Plan getPlan() {
        return plan;
    }

    public void setPlan(EventPayload.Plan plan) {
        this.plan = plan;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public List<String> getRevisions() {
        return List.copyOf(revisions);
    }

    public void addRevision(String revision) {
        if (revision != null && !revision.isBlank()) {
            revisions.add(revision);
        }
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public WaitingInputState getWaitingInput() {
        return waitingInput;
    }

    public void setWaitingInput(WaitingInputState waitingInput) {
        this.waitingInput = waitingInput;
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
