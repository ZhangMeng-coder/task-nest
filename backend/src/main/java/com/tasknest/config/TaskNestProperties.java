package com.tasknest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "tasknest")
public class TaskNestProperties {

    private Sse sse = new Sse();
    private Mock mock = new Mock();

    public Sse getSse() {
        return sse;
    }

    public void setSse(Sse sse) {
        this.sse = sse;
    }

    public Mock getMock() {
        return mock;
    }

    public void setMock(Mock mock) {
        this.mock = mock;
    }

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

    public static class Mock {
        private List<String> scripts = new ArrayList<>(
                List.of("happy-path", "with-ambiguity", "with-failure", "with-noise"));
        private double delayScale = 1.0d;

        public List<String> getScripts() {
            return scripts;
        }

        public void setScripts(List<String> scripts) {
            this.scripts = scripts;
        }

        public double getDelayScale() {
            return delayScale;
        }

        public void setDelayScale(double delayScale) {
            this.delayScale = delayScale;
        }
    }
}
