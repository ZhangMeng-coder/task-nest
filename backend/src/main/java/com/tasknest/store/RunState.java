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

/**
 * 一次运行的内存聚合：主任务、直属子任务、事件列表、seq 与 SSE 订阅者。
 *
 * <p>{@link #getLock()} 保护 seq 分配、事件追加、投影更新与快照构建；
 * 发布事件时必须先持锁再分配 seq，避免快照出现空洞。运行状态与终止标记
 * 一旦写入终态便不再回退。进程重启后本对象丢失，档案需从 MySQL 查询。</p>
 */
public class RunState {

    private final String runId;
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

    public RunState(String runId, String mainTaskId, String title, String goal) {
        this.runId = runId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Object getLock() {
        return lock;
    }

    public long getLastSeq() {
        return seq;
    }

    /**
     * 分配下一个事件序号。调用方必须持有 {@link #getLock()}；seq 从 1 严格递增。
     */
    public long nextSeq() {
        return ++seq;
    }

    /**
     * 返回事件列表的不可变副本，供补发与快照使用。
     */
    public List<EventEnvelope> getEventsCopy() {
        return List.copyOf(events);
    }

    /**
     * 追加事件；调用方需持锁，并保证与 seq 分配顺序一致。
     */
    public void appendEvent(EventEnvelope event) {
        events.add(event);
    }

    /**
     * 返回可变的子任务映射（保持插入顺序）。投影与快照读取时需持运行锁。
     */
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

    /**
     * 为“事件先于 plan 抵达”而临时创建的占位子任务分配排序号，
     * 从 10000 起递增，保证其排在正常计划行之后。
     */
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

    /**
     * 把事件投递到所有当前 SSE 连接各自的队列。
     */
    public void enqueueSubscribers(EventEnvelope event) {
        for (SseConnection subscriber : subscribers) {
            subscriber.enqueue(event);
        }
    }

    /**
     * 请求所有连接在已入队事件发送完毕后关闭，不丢弃尾部事件。
     */
    public void closeSubscribersAfterFlush() {
        for (SseConnection subscriber : subscribers) {
            subscriber.closeAfterFlush();
        }
    }
}
