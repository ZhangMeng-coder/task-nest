package com.tasknest.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.config.TaskNestProperties;
import com.tasknest.event.EventEnvelope;
import com.tasknest.event.EventPublisher;
import com.tasknest.store.RunState;
import com.tasknest.store.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 运行事件 SSE 接入层：建立连接、补发历史、维持心跳并在终止后关闭。
 *
 * <p>连接建立时会校验 {@code afterSeq} 边界（0 到当前 lastSeq），在运行锁内
 * 先补发 {@code seq > afterSeq} 的历史事件，再注册实时订阅，避免补发与实时
 * 流之间出现窗口。每个连接使用独立队列与单线程分发，保证连接内顺序；
 * 同一运行共享一个心跳任务（默认 15s），没有订阅者时自动取消。
 * 已终止的运行在补发完成后刷完即关。</p>
 */
@Service
public class SseService {

    private static final Logger log = LoggerFactory.getLogger(SseService.class);

    private final RunStore runStore;
    private final EventPublisher eventPublisher;
    private final TaskNestProperties properties;
    private final ExecutorService sseDispatcher;
    private final ScheduledExecutorService scheduler;
    private final ObjectMapper objectMapper;
    private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    public SseService(RunStore runStore,
                      EventPublisher eventPublisher,
                      TaskNestProperties properties,
                      @Qualifier("sseDispatcher") ExecutorService sseDispatcher,
                      @Qualifier("taskNestScheduler") ScheduledExecutorService scheduler,
                      ObjectMapper objectMapper) {
        this.runStore = runStore;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.sseDispatcher = sseDispatcher;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    /**
     * 建立运行 SSE 连接并完成历史补发。
     *
     * @param afterSeq 客户端已收到的最后序号；传 0 表示从头补发
     * @throws BusinessException 运行不存在或 {@code afterSeq} 越界时抛出
     *                           {@link ErrorCode#INVALID_AFTER_SEQ} / {@code RUN_NOT_FOUND}
     */
    public SseEmitter connect(String runId, long afterSeq) {
        RunState run = runStore.require(runId);
        SseConnection connection;
        synchronized (run.getLock()) {
            long lastSeq = run.getLastSeq();
            if (afterSeq < 0 || afterSeq > lastSeq) {
                throw new BusinessException(ErrorCode.INVALID_AFTER_SEQ,
                        "afterSeq 必须在 0 到 " + lastSeq + " 之间");
            }
            connection = new SseConnection(
                    properties.getSse().getTimeoutMs(),
                    sseDispatcher,
                    objectMapper,
                    closed -> removeConnection(run, closed));
            connection.setReplayBoundary(lastSeq);
            for (EventEnvelope event : run.getEventsCopy()) {
                if (event.seq() > afterSeq) {
                    connection.enqueueReplay(event);
                }
            }
            run.addSubscriber(connection);
            connection.start();

            // D-01: every connection gets an immediate heartbeat, then the run-wide 15s cadence.
            eventPublisher.publishHeartbeat(run);

            if (run.isTerminal()) {
                connection.closeAfterFlush();
            } else {
                ensureHeartbeat(run);
            }
        }
        return connection.getEmitter();
    }

    /**
     * 连接关闭回调：移除订阅者，最后一个连接断开时停止该运行的心跳。
     */
    private void removeConnection(RunState run, SseConnection connection) {
        synchronized (run.getLock()) {
            run.removeSubscriber(connection);
            if (run.getSubscriberCount() == 0) {
                cancelHeartbeat(run.getRunId());
            }
        }
    }

    /**
     * 为运行注册唯一的心跳任务；已存在时复用。任务在运行终止或没有订阅者时
     * 自行取消，避免空转。
     */
    private void ensureHeartbeat(RunState run) {
        if (run.isTerminal()) {
            return;
        }
        long interval = properties.getSse().getHeartbeatMs();
        heartbeatTasks.computeIfAbsent(run.getRunId(), id -> scheduler.scheduleAtFixedRate(() -> {
            try {
                if (run.isTerminal() || run.getSubscriberCount() == 0) {
                    cancelHeartbeat(id);
                    return;
                }
                eventPublisher.publishHeartbeat(run);
            } catch (RuntimeException exception) {
                log.warn("Heartbeat failed for run {}", id, exception);
            }
        }, interval, interval, TimeUnit.MILLISECONDS));
    }

    /**
     * 取消并移除运行的心跳任务；不存在时为空操作。
     */
    private void cancelHeartbeat(String runId) {
        ScheduledFuture<?> task = heartbeatTasks.remove(runId);
        if (task != null) {
            task.cancel(false);
        }
    }
}
