package com.tasknest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SseIntegrationTest {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(25);

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @Timeout(60)
    void streamsHeartbeatAndResumesAfterManualDisconnect() throws Exception {
        String runId = createAmbiguousRun();

        HttpResponse<InputStream> firstConnection = openSse(runId, 0);
        assertTrue(firstConnection.headers().firstValue("content-type").orElse("")
                .contains("text/event-stream"));
        List<SseFrame> firstFrames = readFrames(firstConnection,
                frame -> "waiting_input".equals(frame.event()), READ_TIMEOUT);

        assertTrue(firstFrames.stream().anyMatch(frame -> "heartbeat".equals(frame.event())),
                "连接建立后应立即收到心跳");
        assertTrue(firstFrames.stream().anyMatch(frame -> "run_started".equals(frame.event())));
        assertTrue(firstFrames.stream().anyMatch(frame -> "plan".equals(frame.event())));
        assertTrue(firstFrames.stream().anyMatch(frame -> "waiting_input".equals(frame.event())));
        assertStrictlyIncreasing(firstFrames);

        long lastSeq = seq(firstFrames.get(firstFrames.size() - 1));
        SseFrame waiting = firstFrames.get(firstFrames.size() - 1);
        String waitingTaskId = waiting.json().get("task").get("taskId").asText();

        // Manual kill: close the client stream, then let the run advance.
        firstConnection.body().close();

        ResponseEntity<JsonNode> submit = rest.postForEntity(
                url("/api/runs/" + runId + "/inputs"),
                Map.of("taskId", waitingTaskId, "selectedOptionId", "cloud-sync"),
                JsonNode.class);
        assertEquals(HttpStatus.ACCEPTED, submit.getStatusCode());

        HttpResponse<InputStream> secondConnection = openSse(runId, lastSeq);
        List<SseFrame> secondFrames = readFrames(secondConnection,
                frame -> "run_completed".equals(frame.event()), READ_TIMEOUT);

        assertTrue(secondFrames.stream().allMatch(frame -> seq(frame) > lastSeq),
                "补发必须从 afterSeq 之后开始");
        assertTrue(secondFrames.stream().anyMatch(frame -> "input_received".equals(frame.event())));
        assertTrue(secondFrames.stream().anyMatch(frame -> "heartbeat".equals(frame.event())));
        assertTrue(secondFrames.get(secondFrames.size() - 1).event().equals("run_completed"));
        assertStrictlyIncreasing(secondFrames);

        Set<String> eventIds = new HashSet<>();
        for (SseFrame frame : secondFrames) {
            assertTrue(eventIds.add(frame.json().get("eventId").asText()),
                    "补发不得产生重复 eventId");
        }

        SseFrame inputReceived = secondFrames.stream()
                .filter(frame -> "input_received".equals(frame.event()))
                .findFirst()
                .orElseThrow();
        assertEquals("cloud-sync",
                inputReceived.json().get("payload").get("selectedOptionId").asText());
        assertTrue(runId.startsWith("run_"));
    }

    @Test
    @Timeout(30)
    void rejectsIllegalAfterSeq() throws Exception {
        String runId = createAmbiguousRun();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url("/api/runs/" + runId + "/events?afterSeq=999999")))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("40002"), response.body());
    }

    private String createAmbiguousRun() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                url("/api/runs"), Map.of("mockScript", "with-ambiguity"), JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        JsonNode body = response.getBody();
        assertNotNull(body);
        return body.get("data").get("runId").asText();
    }

    private HttpResponse<InputStream> openSse(String runId, long afterSeq) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url("/api/runs/" + runId + "/events?afterSeq=" + afterSeq)))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return response;
    }

    private List<SseFrame> readFrames(HttpResponse<InputStream> response,
                                      Predicate<SseFrame> stop,
                                      Duration timeout) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-test-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<List<SseFrame>> future = executor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    return readUntil(reader, stop);
                }
            });
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            response.body().close();
            throw new AssertionError("SSE 读取超时", exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private List<SseFrame> readUntil(BufferedReader reader, Predicate<SseFrame> stop) throws Exception {
        List<SseFrame> frames = new ArrayList<>();
        String event = null;
        String id = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    SseFrame frame = new SseFrame(event, id, data.toString());
                    frames.add(frame);
                    if (stop.test(frame)) {
                        return frames;
                    }
                }
                event = null;
                id = null;
                data.setLength(0);
            } else if (line.startsWith(":")) {
                // comment / keep-alive
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("id:")) {
                id = line.substring("id:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
        }
        throw new AssertionError("SSE 流提前结束，未满足停止条件；已收到 " + frames.size() + " 帧");
    }

    private void assertStrictlyIncreasing(List<SseFrame> frames) {
        long previous = 0;
        for (SseFrame frame : frames) {
            long seq = seq(frame);
            assertTrue(seq > previous, "SSE 帧 seq 必须严格递增");
            previous = seq;
        }
    }

    private long seq(SseFrame frame) {
        return frame.json().get("seq").asLong();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private final class SseFrame {
        private final String event;
        private final String id;
        private final String data;

        private SseFrame(String event, String id, String data) {
            this.event = event;
            this.id = id;
            this.data = data;
        }

        private String event() {
            return event;
        }

        private JsonNode json() {
            try {
                return mapper.readTree(data);
            } catch (Exception exception) {
                throw new AssertionError("无法解析 SSE 数据: " + data, exception);
            }
        }
    }
}
