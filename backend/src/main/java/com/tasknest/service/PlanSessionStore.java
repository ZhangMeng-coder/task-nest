package com.tasknest.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventEnvelope;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.persistence.entity.PlanSessionEntity;
import com.tasknest.persistence.entity.PlanSessionEventEntity;
import com.tasknest.persistence.service.PlanSessionEntityService;
import com.tasknest.persistence.service.PlanSessionEventEntityService;
import com.tasknest.store.WaitingInputState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规划会话的聚合存储：内存状态、事件持久化与 SSE 扇出。
 *
 * <p>会话对象保存在进程内的 {@link ConcurrentHashMap}，进程重启后不可续传；
 * 规划事件写入 {@code tn_plan_session_event}，会话摘要写入 {@code tn_plan_session}，
 * 不使用运行事件表 {@code tn_event}。确认前不创建主任务。</p>
 *
 * <p>事件发布顺序与运行侧一致：在会话锁内分配 seq → 组装信封 → 追加内存事件
 * → 应用状态语义 → 持久化事件与会话 → 投递订阅者。终止事件会在入队后要求
 * 连接“刷完即关”；持久化失败只记录日志，不阻断 SSE。</p>
 */
@Service
public class PlanSessionStore {

    private static final Logger log = LoggerFactory.getLogger(PlanSessionStore.class);

    private final Map<String, PlanSessionState> sessions = new ConcurrentHashMap<>();
    private final PlanSessionEntityService sessionService;
    private final PlanSessionEventEntityService eventService;
    private final ObjectMapper objectMapper;

    public PlanSessionStore(PlanSessionEntityService sessionService,
                            PlanSessionEventEntityService eventService,
                            ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建内存会话并写入会话摘要记录（初始状态 {@code RUNNING}、seq 为 0）。
     * 主任务此时尚未创建。
     */
    public PlanSessionState create(String planSessionId,
                                   String goal,
                                   String workingDir) {
        PlanSessionState state = new PlanSessionState(
                planSessionId, goal, workingDir);
        LocalDateTime now = LocalDateTime.now();
        PlanSessionEntity entity = new PlanSessionEntity();
        entity.setPlanSessionId(planSessionId);
        entity.setGoal(goal);
        entity.setWorkingDir(workingDir);
        entity.setStatus(PlanSessionStatus.RUNNING.name());
        entity.setLastSeq(0L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        sessionService.save(entity);
        sessions.put(planSessionId, state);
        return state;
    }

    /**
     * 取回会话；不存在（含进程重启后的旧会话）时返回
     * {@link ErrorCode#PLAN_SESSION_NOT_FOUND}。
     */
    public PlanSessionState require(String planSessionId) {
        PlanSessionState state = sessions.get(planSessionId);
        if (state == null) {
            throw new BusinessException(ErrorCode.PLAN_SESSION_NOT_FOUND);
        }
        return state;
    }

    public boolean contains(String planSessionId) {
        return sessions.containsKey(planSessionId);
    }

    public void remove(String planSessionId) {
        sessions.remove(planSessionId);
    }

    /**
     * 发布一条规划事件并同步推进会话状态。
     *
     * <p>seq 在会话锁内分配，保证严格递增；{@code eventId} 由 seq 推导。
     * 事件会先追加到内存、应用语义、写库，再入队各 SSE 连接。终止事件
     * （{@code run_completed} / {@code error}）入队后立即请求连接刷完即关；
     * 非终止事件只入队。数据库异常被吞掉，不会阻止事件继续推送。</p>
     *
     * @return 已分配 seq 与时间戳的事件信封
     */
    public EventEnvelope publish(PlanSessionState state,
                                 EventType type,
                                 TaskRef task,
                                 EventPayload payload) {
        EventEnvelope event;
        synchronized (state.getLock()) {
            long seq = state.nextSeq();
            event = new EventEnvelope(
                    String.format("evt_%06d", seq),
                    state.getPlanSessionId(),
                    seq,
                    type,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    task,
                    payload == null ? new EventPayload.Empty() : payload);
            state.appendEvent(event);
            state.setUpdatedAt(event.timestamp());
            applySemantics(state, event);
            persistEvent(state, event);
            persistSession(state);
            if (event.type().isTerminal()) {
                state.enqueueSubscribers(event);
                state.closeSubscribersAfterFlush();
            } else {
                state.enqueueSubscribers(event);
            }
        }
        return event;
    }

    /**
     * 发布心跳。心跳同样占用 seq，确保 {@code afterSeq} 补发游标连续。
     */
    public EventEnvelope publishHeartbeat(PlanSessionState state) {
        return publish(state, EventType.HEARTBEAT, null, new EventPayload.Empty());
    }

    /**
     * 直接更新会话状态并持久化，不发布事件。用于不产生对外事件的内部状态同步。
     */
    public void updateStatus(PlanSessionState state,
                             PlanSessionStatus status,
                             String error) {
        synchronized (state.getLock()) {
            state.setStatus(status);
            if (error != null) {
                state.setError(error);
            }
            state.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            persistSession(state);
        }
    }

    /**
     * 记录当前等待抉择的问题，供 {@code submitInput} 校验 taskId 与选项。
     */
    public void setWaiting(PlanSessionState state, WaitingInputState waiting) {
        synchronized (state.getLock()) {
            state.setWaitingInput(waiting);
        }
    }

    /**
     * 清除等待态；仅当传入的问题正是当前问题时才清除，避免并发覆盖新问题。
     */
    public void clearWaiting(PlanSessionState state, WaitingInputState waiting) {
        synchronized (state.getLock()) {
            if (state.getWaitingInput() == waiting) {
                state.setWaitingInput(null);
            }
        }
    }

    private void applySemantics(PlanSessionState state, EventEnvelope event) {
        switch (event.type()) {
            case PLAN -> {
                if (event.payload() instanceof EventPayload.Plan plan) {
                    state.setPlan(plan);
                    state.setPlanJson(writeJson(plan));
                    state.setStatus(PlanSessionStatus.READY);
                    state.setError(null);
                }
            }
            case WAITING_INPUT -> state.setStatus(PlanSessionStatus.WAITING_INPUT);
            case INPUT_RECEIVED -> state.setStatus(PlanSessionStatus.RUNNING);
            case ERROR -> {
                state.setStatus(PlanSessionStatus.FAILED);
                if (event.payload() instanceof EventPayload.Error error) {
                    state.setError(error.message());
                }
            }
            case RUN_COMPLETED -> {
                // RUN_COMPLETED 只是终止信号；CONFIRMED / CANCELLED 由确认、取消流程先行写入。
            }
            default -> {
            }
        }
    }

    /**
     * 事件原文以 JSON 落库。持久化失败只记日志，内存状态与 SSE 投递仍然有效。
     */
    private void persistEvent(PlanSessionState state, EventEnvelope event) {
        try {
            PlanSessionEventEntity entity = new PlanSessionEventEntity();
            entity.setPlanSessionId(state.getPlanSessionId());
            entity.setEventId(event.eventId());
            entity.setSeq(event.seq());
            entity.setType(event.type().wireName());
            entity.setEventJson(writeJson(event));
            entity.setEventTime(toLocal(event.timestamp()));
            entity.setCreatedAt(toLocal(event.timestamp()));
            eventService.save(entity);
        } catch (RuntimeException exception) {
            log.error("规划事件持久化失败 session={} seq={}",
                    state.getPlanSessionId(), event.seq(), exception);
        }
    }

    /**
     * 回写会话摘要（状态、计划 JSON、最后 seq、错误）。记录不存在时静默跳过，
     * 数据库异常同样只记日志。
     */
    private void persistSession(PlanSessionState state) {
        try {
            PlanSessionEntity entity = sessionService.getOne(
                    Wrappers.<PlanSessionEntity>lambdaQuery()
                            .eq(PlanSessionEntity::getPlanSessionId,
                                    state.getPlanSessionId()));
            if (entity == null) {
                return;
            }
            entity.setStatus(state.getStatus().name());
            entity.setPlanJson(state.getPlanJson());
            entity.setLastSeq(state.getLastSeq());
            entity.setError(state.getError());
            entity.setUpdatedAt(toLocal(state.getUpdatedAt()));
            sessionService.updateById(entity);
        } catch (RuntimeException exception) {
            log.error("规划会话持久化失败 session={}", state.getPlanSessionId(), exception);
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.warn("无法序列化规划内容", exception);
            return null;
        }
    }

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
