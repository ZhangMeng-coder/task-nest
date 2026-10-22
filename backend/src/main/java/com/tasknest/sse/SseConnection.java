package com.tasknest.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 单条 SSE 连接：一条独立队列 + 单线程分发，保证单连接内按入队顺序发送。
 * 实时事件与补发事件走同一队列，交接处不会错序。
 */
public class SseConnection {

    private static final Logger log = LoggerFactory.getLogger(SseConnection.class);
    private static final Object CLOSE = new Object();

    private final SseEmitter emitter;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final ExecutorService dispatcher;
    private final ObjectMapper objectMapper;
    private final Consumer<SseConnection> onClosed;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean cleanupDone = new AtomicBoolean(false);

    private volatile long replayBoundarySeq = -1;

    public SseConnection(long timeoutMs,
                         ExecutorService dispatcher,
                         ObjectMapper objectMapper,
                         Consumer<SseConnection> onClosed) {
        this.emitter = new SseEmitter(timeoutMs);
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.onClosed = onClosed;
        this.emitter.onCompletion(this::closeAndNotify);
        this.emitter.onTimeout(this::closeAndNotify);
        this.emitter.onError(error -> closeAndNotify());
    }

    public SseEmitter getEmitter() {
        return emitter;
    }

    /**
     * 连接补发历史时记录边界：seq 不大于该边界的事件已经通过补发送达，
     * 后续延迟投递任务不应再重复入队。
     */
    public void setReplayBoundary(long replayBoundarySeq) {
        this.replayBoundarySeq = replayBoundarySeq;
    }

    /**
     * 在共享分发线程池上启动本连接的分发循环。每个连接只启动一次。
     */
    public void start() {
        dispatcher.submit(this::dispatchLoop);
    }

    /**
     * 入队实时事件。连接已关闭或事件 seq 不大于补发边界时丢弃：这些事件
     * 已经通过历史补发送达，重复入队会造成重复行。
     */
    public void enqueue(EventEnvelope event) {
        if (closed.get() || event.seq() <= replayBoundarySeq) {
            return;
        }
        queue.offer(event);
    }

    /**
     * 入队补发事件，不做 seq 边界过滤；调用方已按 {@code afterSeq} 筛选。
     */
    public void enqueueReplay(EventEnvelope event) {
        if (closed.get()) {
            return;
        }
        queue.offer(event);
    }

    /**
     * 在队列尾部放入关闭哨兵：分发线程先发完之前入队的所有事件，
     * 再完成 emitter，避免终止事件被截断。
     */
    public void closeAfterFlush() {
        if (closed.get()) {
            return;
        }
        queue.offer(CLOSE);
    }

    /**
     * 单线程消费队列并按入队顺序发送。轮询超时后继续等待，直到收到关闭哨兵、
     * 连接关闭或发送异常；退出时统一触发一次清理回调。
     */
    private void dispatchLoop() {
        try {
            while (!closed.get()) {
                Object item = queue.poll(1, TimeUnit.SECONDS);
                if (item == null) {
                    continue;
                }
                if (item == CLOSE) {
                    emitter.complete();
                    break;
                }
                EventEnvelope event = (EventEnvelope) item;
                String json = objectMapper.writeValueAsString(event);
                emitter.send(SseEmitter.event()
                        .id(event.eventId())
                        .name(event.type().wireName())
                        .data(json, MediaType.TEXT_PLAIN));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException exception) {
            log.debug("SSE connection closed while sending: {}", exception.getMessage());
        } finally {
            closeAndNotify();
        }
    }

    /**
     * 幂等清理：完成、超时、错误与发送异常可能重复触发，只执行一次订阅者移除。
     */
    private void closeAndNotify() {
        if (cleanupDone.compareAndSet(false, true)) {
            closed.set(true);
            onClosed.accept(this);
        }
    }
}
