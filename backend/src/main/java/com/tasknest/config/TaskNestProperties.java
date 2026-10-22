package com.tasknest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code tasknest.*} 配置绑定。
 *
 * <p>当前只包含 {@code sse} 一组配置，控制 SSE 连接超时与心跳间隔。
 * 默认值与 {@code application.yml} 保持一致，配置缺失时仍可运行。</p>
 */
@ConfigurationProperties(prefix = "tasknest")
public class TaskNestProperties {

    private Sse sse = new Sse();

    public Sse getSse() {
        return sse;
    }

    public void setSse(Sse sse) {
        this.sse = sse;
    }

    /**
     * SSE 配置：{@code timeoutMs} 为连接超时（默认 1 小时），
     * {@code heartbeatMs} 为心跳间隔（默认 15 秒），心跳事件也占用 seq。
     */
    public static class Sse {
        private long timeoutMs = 3_600_000L;
        private long heartbeatMs = 15_000L;

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getHeartbeatMs() {
            return heartbeatMs;
        }

        public void setHeartbeatMs(long heartbeatMs) {
            this.heartbeatMs = heartbeatMs;
        }
    }
}
