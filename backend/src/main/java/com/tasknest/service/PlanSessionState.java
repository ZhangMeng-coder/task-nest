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

/**
 * 内存中的规划会话聚合：标识、目标、工作目录、计划版本、事件与 SSE 订阅者。
 *
 * <p>状态与 seq 的写入由 {@link #getLock()} 保护；{@link #getEventsCopy()}
 * 返回不可变快照，供补发与快照读取。确认前不创建主任务，进程重启后会话丢失。</p>
 */
public class PlanSessionState {

    private final String planSessionId;
    private final String goal;
    private final String workingDir;
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

    public PlanSessionState(String planSessionId, String goal, String workingDir) {
        this.planSessionId = planSessionId;
        this.goal = goal;
        this.workingDir = workingDir;
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

    /**
     * 分配下一个事件序号。调用方必须持有 {@link #getLock()}，以保证严格递增。
     */
    public long nextSeq() {
        return ++seq;
    }

    /**
     * 返回事件列表的不可变快照，避免补发遍历时与写入线程互相影响。
     */
    public List<EventEnvelope> getEventsCopy() {
        return List.copyOf(events);
    }

    /**
     * 追加事件到内存列表；调用方需持有会话锁并保持与 seq 分配一致的顺序。
     */
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

    /**
     * 把事件投递到当前所有 SSE 连接各自的队列。
     */
    public void enqueueSubscribers(EventEnvelope event) {
        for (SseConnection subscriber : subscribers) {
            subscriber.enqueue(event);
        }
    }

    /**
     * 请求所有连接在已入队事件发送完毕后关闭；不会丢弃队列中的尾部事件。
     */
    public void closeSubscribersAfterFlush() {
        for (SseConnection subscriber : subscribers) {
            subscriber.closeAfterFlush();
        }
    }
}
