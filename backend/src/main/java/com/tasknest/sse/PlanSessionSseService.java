package com.tasknest.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.config.TaskNestProperties;
import com.tasknest.event.EventEnvelope;
import com.tasknest.service.PlanSessionState;
import com.tasknest.service.PlanSessionStore;
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

/** 规划会话 SSE：与运行 SSE 相同的补发、心跳与关闭语义。 */
@Service
public class PlanSessionSseService {

    private static final Logger log = LoggerFactory.getLogger(PlanSessionSseService.class);

    private final PlanSessionStore store;
    private final TaskNestProperties properties;
    private final ExecutorService sseDispatcher;
    private final ScheduledExecutorService scheduler;
    private final ObjectMapper objectMapper;
    private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    public PlanSessionSseService(PlanSessionStore store,
                                 TaskNestProperties properties,
                                 @Qualifier("sseDispatcher") ExecutorService sseDispatcher,
                                 @Qualifier("taskNestScheduler") ScheduledExecutorService scheduler,
                                 ObjectMapper objectMapper) {
        this.store = store;
        this.properties = properties;
        this.sseDispatcher = sseDispatcher;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    public SseEmitter connect(String planSessionId, long afterSeq) {
        PlanSessionState state = store.require(planSessionId);
        SseConnection connection;
        synchronized (state.getLock()) {
            long lastSeq = state.getLastSeq();
            if (afterSeq < 0 || afterSeq > lastSeq) {
                throw new BusinessException(ErrorCode.INVALID_AFTER_SEQ,
                        "afterSeq 必须在 0 到 " + lastSeq + " 之间");
            }
            connection = new SseConnection(
                    properties.getSse().getTimeoutMs(),
                    sseDispatcher,
                    objectMapper,
                    closed -> removeConnection(state, closed));
            connection.setReplayBoundary(lastSeq);
            for (EventEnvelope event : state.getEventsCopy()) {
                if (event.seq() > afterSeq) {
                    connection.enqueueReplay(event);
                }
            }
            state.addSubscriber(connection);
            connection.start();
            store.publishHeartbeat(state);
            if (state.isTerminal()) {
                connection.closeAfterFlush();
            } else {
                ensureHeartbeat(state);
            }
        }
        return connection.getEmitter();
    }

    public void close(String planSessionId) {
        PlanSessionState state = store.require(planSessionId);
        synchronized (state.getLock()) {
            state.closeSubscribersAfterFlush();
            cancelHeartbeat(planSessionId);
        }
    }

    private void removeConnection(PlanSessionState state, SseConnection connection) {
        synchronized (state.getLock()) {
            state.removeSubscriber(connection);
            if (state.getSubscriberCount() == 0) {
                cancelHeartbeat(state.getPlanSessionId());
            }
        }
    }

    private void ensureHeartbeat(PlanSessionState state) {
        if (state.isTerminal()) {
            return;
        }
        long interval = properties.getSse().getHeartbeatMs();
        heartbeatTasks.computeIfAbsent(state.getPlanSessionId(), id ->
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        if (state.isTerminal() || state.getSubscriberCount() == 0) {
                            cancelHeartbeat(id);
                            return;
                        }
                        store.publishHeartbeat(state);
                    } catch (RuntimeException exception) {
                        log.warn("规划会话心跳失败 session={}", id, exception);
                    }
                }, interval, interval, TimeUnit.MILLISECONDS));
    }

    private void cancelHeartbeat(String planSessionId) {
        ScheduledFuture<?> task = heartbeatTasks.remove(planSessionId);
        if (task != null) {
            task.cancel(false);
        }
    }
}
