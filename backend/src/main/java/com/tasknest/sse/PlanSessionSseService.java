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

/**
 * 规划会话 SSE：与运行 SSE 相同的补发、心跳与关闭语义。
 *
 * <p>在会话锁内校验 {@code afterSeq}、补发历史事件后再注册订阅者；每个连接
 * 独立队列保证顺序。会话进入终止态时刷完即关；没有订阅者时取消心跳。
 * 规划事件的 {@code runId} 即 {@code planSessionId}，因此复用同一套连接实现。</p>
 */
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

    /**
     * 建立规划 SSE 连接并补发 {@code seq > afterSeq} 的历史事件。
     * 已终止的会话在补发完成后关闭连接。
     *
     * @param afterSeq 客户端已收到的最后序号；传 0 表示从头补发
     * @throws BusinessException 会话不存在或 {@code afterSeq} 越界时抛出
     */
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

    /**
     * 请求所有连接刷完即关，并取消会话心跳。用于确认、取消与失败收尾。
     */
    public void close(String planSessionId) {
        PlanSessionState state = store.require(planSessionId);
        synchronized (state.getLock()) {
            state.closeSubscribersAfterFlush();
            cancelHeartbeat(planSessionId);
        }
    }

    /**
     * 连接关闭回调：移除订阅者，最后一个连接断开时取消心跳。
     */
    private void removeConnection(PlanSessionState state, SseConnection connection) {
        synchronized (state.getLock()) {
            state.removeSubscriber(connection);
            if (state.getSubscriberCount() == 0) {
                cancelHeartbeat(state.getPlanSessionId());
            }
        }
    }

    /**
     * 为会话注册唯一的心跳任务；已存在时复用。任务在会话终止或没有订阅者时
     * 自行取消。
     */
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

    /**
     * 取消并移除会话的心跳任务；不存在时为空操作。
     */
    private void cancelHeartbeat(String planSessionId) {
        ScheduledFuture<?> task = heartbeatTasks.remove(planSessionId);
        if (task != null) {
            task.cancel(false);
        }
    }
}
