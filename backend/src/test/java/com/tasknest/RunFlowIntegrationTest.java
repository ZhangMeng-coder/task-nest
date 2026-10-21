package com.tasknest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RunFlowIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @Test
    @Timeout(40)
    void happyPathBuildsTwoLevelStructureAndCompletes() {
        String runId = create("happy-path");
        JsonNode snapshot = awaitTerminal(runId);

        assertEquals("COMPLETED", snapshot.get("run").get("status").asText());
        JsonNode mainTask = snapshot.get("mainTask");
        assertEquals("COMPLETED", mainTask.get("status").asText());
        assertEquals("交付竞品分析报告", mainTask.get("title").asText());
        assertNotNull(mainTask.get("taskId").asText());

        JsonNode subtasks = snapshot.get("subtasks");
        assertEquals(5, subtasks.size(), "主任务下应有 5 个直属子任务");
        assertTrue(subtasks.isArray());
        for (JsonNode subtask : subtasks) {
            assertEquals("SUCCESS", subtask.get("status").asText());
        }

        JsonNode events = snapshot.get("events");
        assertEquals("run_started", events.get(0).get("type").asText());
        assertEquals("run_completed", events.get(events.size() - 1).get("type").asText());
        assertStrictlyIncreasing(events);
    }

    @Test
    @Timeout(40)
    void ambiguityWaitsForInputAndResumesOnSelectedBranch() {
        String runId = create("with-ambiguity");
        JsonNode waitingSnapshot = awaitTaskWithStatus(runId, "WAITING_INPUT");
        JsonNode waitingNode = findSubtask(waitingSnapshot, "t_scope");
        assertNotNull(waitingNode);
        assertEquals("等待输入", "等待输入");
        assertNotNull(waitingNode.get("waitingInput"));
        assertEquals("首发版本的缓存策略选哪条？",
                waitingNode.get("waitingInput").get("question").asText());

        // A valid task that is not the one waiting must be rejected.
        ResponseEntity<JsonNode> wrongTask = postInput(runId, "t_root", "local-first");
        assertEquals(HttpStatus.CONFLICT, wrongTask.getStatusCode());
        assertEquals(40901, wrongTask.getBody().get("code").asInt());

        ResponseEntity<JsonNode> invalidOption = postInput(runId, "t_scope", "no-such-option");
        assertEquals(HttpStatus.CONFLICT, invalidOption.getStatusCode());
        assertEquals(40902, invalidOption.getBody().get("code").asInt());

        ResponseEntity<JsonNode> accepted = postInput(runId, "t_scope", "local-first");
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        assertEquals(0, accepted.getBody().get("code").asInt());

        JsonNode snapshot = awaitTerminal(runId);
        assertEquals("COMPLETED", snapshot.get("run").get("status").asText());
        JsonNode completedScope = findSubtask(snapshot, "t_scope");
        assertEquals("SUCCESS", completedScope.get("status").asText());
        assertEquals("local-first", completedScope.get("selectedOptionId").asText());
        assertTrue(completedScope.get("summary").asText().contains("本地优先"));

        JsonNode inputReceived = lastEventOfType(snapshot.get("events"), "input_received");
        assertNotNull(inputReceived);
        assertEquals("local-first", inputReceived.get("payload").get("selectedOptionId").asText());
        assertTrue(snapshot.get("events").toString().contains("单机冲突只有一种形态"));
    }

    @Test
    @Timeout(40)
    void retryableFailureIsRetriedAndSucceeds() {
        String runId = create("with-failure");
        JsonNode snapshot = awaitTerminal(runId);

        assertEquals("COMPLETED", snapshot.get("run").get("status").asText());
        assertEquals("COMPLETED", snapshot.get("mainTask").get("status").asText());
        for (JsonNode subtask : snapshot.get("subtasks")) {
            assertEquals("SUCCESS", subtask.get("status").asText());
        }
        assertEquals("SUCCESS", findSubtask(snapshot, "t_fetch_auth").get("status").asText());

        int failedEvents = 0;
        int startedAttempts = 0;
        boolean sawErrorResult = false;
        boolean sawSuccessResult = false;
        for (JsonNode event : snapshot.get("events")) {
            if ("task_failed".equals(event.get("type").asText())) {
                failedEvents++;
                assertTrue(event.get("payload").get("retryable").asBoolean());
            }
            if ("task_started".equals(event.get("type").asText())
                    && "t_fetch_auth".equals(event.get("task").get("taskId").asText())) {
                startedAttempts++;
            }
            if ("tool_result".equals(event.get("type").asText())
                    && "t_fetch_auth".equals(event.get("task").get("taskId").asText())) {
                String status = event.get("payload").get("status").asText();
                sawErrorResult |= "error".equals(status);
                sawSuccessResult |= "success".equals(status);
            }
        }
        assertEquals(1, failedEvents);
        assertEquals(2, startedAttempts, "失败后应重新开始同一任务");
        assertTrue(sawErrorResult && sawSuccessResult);
    }

    @Test
    @Timeout(40)
    void noiseStillConvergesWithMonotonicSeqAndPersistedDroppedEvent() {
        String runId = create("with-noise");
        JsonNode snapshot = awaitTerminal(runId);

        assertEquals("COMPLETED", snapshot.get("run").get("status").asText());
        assertStrictlyIncreasing(snapshot.get("events"));
        for (JsonNode subtask : snapshot.get("subtasks")) {
            assertEquals("SUCCESS", subtask.get("status").asText());
        }

        Set<String> ids = new HashSet<>();
        int duplicates = 0;
        for (JsonNode event : snapshot.get("events")) {
            if (!ids.add(event.get("eventId").asText())) {
                duplicates++;
            }
        }
        assertTrue(duplicates > 0, "脚本应包含重复 eventId");
        assertTrue(snapshot.get("events").toString().contains("故意丢弃"),
                "dropDispatch 的事件仍应持久化在历史中");
    }

    @Test
    @Timeout(40)
    void mainTaskIsPersistedWithPromptsAndIntermediateResults() {
        String runId = createWithGoal("happy-path", "给团队做一份可复核的竞品分析。");
        JsonNode snapshot = awaitTerminal(runId);
        String mainTaskId = snapshot.get("run").get("mainTaskId").asText();
        assertTrue(mainTaskId.startsWith("mt_"));

        ResponseEntity<JsonNode> listResponse = rest.getForEntity(
                url("/api/main-tasks"), JsonNode.class);
        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        JsonNode mainTasks = listResponse.getBody().get("data");
        boolean found = false;
        for (JsonNode item : mainTasks) {
            if (mainTaskId.equals(item.get("mainTaskId").asText())) {
                found = true;
                assertEquals("COMPLETED", item.get("status").asText());
                assertEquals(5, item.get("subtaskCount").asInt());
                assertEquals(5, item.get("completedSubtaskCount").asInt());
            }
        }
        assertTrue(found, "主任务列表应包含刚完成的主任务");

        ResponseEntity<JsonNode> detailResponse = rest.getForEntity(
                url("/api/main-tasks/" + mainTaskId), JsonNode.class);
        assertEquals(HttpStatus.OK, detailResponse.getStatusCode());
        JsonNode detail = detailResponse.getBody().get("data");
        assertEquals("给团队做一份可复核的竞品分析。",
                detail.get("mainTask").get("goal").asText());
        assertEquals("COMPLETED", detail.get("mainTask").get("status").asText());
        assertEquals(5, detail.get("subtasks").size());
        assertEquals(3, detail.get("prompts").size(), "三个提示词拼装都应持久化");
        assertTrue(detail.get("artifacts").size() > 5, "思考与工具调用结果都应持久化");
        Set<String> artifactKinds = new HashSet<>();
        for (JsonNode artifact : detail.get("artifacts")) {
            artifactKinds.add(artifact.get("kind").asText());
        }
        assertTrue(artifactKinds.contains("THOUGHT"));
        assertTrue(artifactKinds.contains("TOOL_CALL"));
        boolean mergedToolResult = false;
        for (JsonNode artifact : detail.get("artifacts")) {
            if ("TOOL_CALL".equals(artifact.get("kind").asText())
                    && artifact.get("status") != null
                    && !artifact.get("status").isNull()) {
                mergedToolResult = true;
            }
        }
        assertTrue(mergedToolResult, "工具结果应回填到对应的工具调用记录");
        assertTrue(detail.get("events").size() > 20);
        assertTrue(detail.get("events").toString().contains("\"type\":\"task_thought\""));
    }

    @Test
    @Timeout(20)
    void validatesScriptsAndUnknownRunsAndMainTasks() {
        ResponseEntity<JsonNode> unknownScript = rest.postForEntity(
                url("/api/runs"), Map.of("mockScript", "nope"), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, unknownScript.getStatusCode());
        assertEquals(40003, unknownScript.getBody().get("code").asInt());

        ResponseEntity<JsonNode> blankScript = rest.postForEntity(
                url("/api/runs"), Map.of("mockScript", " "), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, blankScript.getStatusCode());
        assertEquals(40001, blankScript.getBody().get("code").asInt());

        ResponseEntity<JsonNode> missingRun = rest.getForEntity(
                url("/api/runs/run_does_not_exist"), JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND, missingRun.getStatusCode());
        assertEquals(40401, missingRun.getBody().get("code").asInt());

        ResponseEntity<JsonNode> unknownTask = postInput("run_does_not_exist", "t_x", "option");
        assertEquals(HttpStatus.NOT_FOUND, unknownTask.getStatusCode());
        assertEquals(40401, unknownTask.getBody().get("code").asInt());

        ResponseEntity<JsonNode> missingMainTask = rest.getForEntity(
                url("/api/main-tasks/mt_does_not_exist"), JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND, missingMainTask.getStatusCode());
        assertEquals(40403, missingMainTask.getBody().get("code").asInt());
    }

    private String create(String script) {
        JsonNode data = createData(script, null);
        return data.get("runId").asText();
    }

    private String createWithGoal(String script, String goal) {
        JsonNode data = createData(script, goal);
        return data.get("runId").asText();
    }

    private JsonNode createData(String script, String goal) {
        Map<String, Object> body = goal == null
                ? Map.of("mockScript", script)
                : Map.of("mockScript", script, "goal", goal);
        ResponseEntity<JsonNode> response = rest.postForEntity(
                url("/api/main-tasks"), body, JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), String.valueOf(response.getBody()));
        JsonNode data = response.getBody().get("data");
        assertNotNull(data);
        return data;
    }

    private ResponseEntity<JsonNode> postInput(String runId, String taskId, String optionId) {
        return rest.postForEntity(
                url("/api/runs/" + runId + "/inputs"),
                Map.of("taskId", taskId, "selectedOptionId", optionId),
                JsonNode.class);
    }

    private JsonNode awaitTerminal(String runId) {
        return awaitSnapshot(runId, data -> {
            String status = data.get("run").get("status").asText();
            return "COMPLETED".equals(status) || "FAILED".equals(status);
        }, "运行未在超时前结束");
    }

    private JsonNode awaitTaskWithStatus(String runId, String status) {
        return awaitSnapshot(runId, data -> {
            JsonNode main = data.get("mainTask");
            if (main != null && status.equals(main.get("status").asText())) {
                return true;
            }
            for (JsonNode subtask : data.get("subtasks")) {
                if (status.equals(subtask.get("status").asText())) {
                    return true;
                }
            }
            return false;
        }, "没有任务进入状态 " + status);
    }

    private JsonNode awaitSnapshot(String runId, java.util.function.Predicate<JsonNode> done, String message) {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<JsonNode> response = rest.getForEntity(
                    url("/api/runs/" + runId), JsonNode.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                last = response.getBody().get("data");
                if (done.test(last)) {
                    return last;
                }
            }
            try {
                Thread.sleep(80);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail("等待快照时被中断");
            }
        }
        fail(message + "，最后一次快照: " + last);
        return null;
    }

    private void assertStrictlyIncreasing(JsonNode events) {
        long previous = 0;
        for (JsonNode event : events) {
            long seq = event.get("seq").asLong();
            assertTrue(seq > previous, "seq 必须严格递增");
            previous = seq;
        }
    }

    private JsonNode lastEventOfType(JsonNode events, String type) {
        JsonNode found = null;
        for (JsonNode event : events) {
            if (type.equals(event.get("type").asText())) {
                found = event;
            }
        }
        return found;
    }

    private JsonNode findSubtask(JsonNode snapshot, String taskId) {
        for (JsonNode subtask : snapshot.get("subtasks")) {
            if (taskId.equals(subtask.get("taskId").asText())) {
                return subtask;
            }
        }
        return null;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
