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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PlanFlowIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @Test
    @Timeout(20)
    void generatesMarkdownPlanDraftFromMockPlan() {
        JsonNode draft = generateDraft("happy-path", "为团队产出一页竞品分析。");

        assertEquals("交付竞品分析报告", draft.get("title").asText());
        assertEquals("为团队产出一页竞品分析。", draft.get("goal").asText());
        assertEquals("happy-path", draft.get("mockScript").asText());
        assertEquals(1, draft.get("version").asInt());
        assertTrue(draft.get("mainTaskId").isNull());
        assertEquals(5, draft.get("rows").size());
        assertEquals(0, draft.get("revisions").size());
        String markdown = draft.get("markdown").asText();
        assertTrue(markdown.startsWith("# 交付竞品分析报告"));
        assertTrue(markdown.contains("> 目标：为团队产出一页竞品分析。"));
        assertTrue(markdown.contains("| # | 子任务 | Agent | 依赖 | 预期产出 |"));
        assertTrue(markdown.contains("收集竞品事实"));
        assertEquals("研究员", draft.get("rows").get(0).get("agent").asText());
        assertEquals("第 2 步", draft.get("rows").get(2).get("dependency").asText());
    }

    @Test
    @Timeout(40)
    void revisesDraftAndPersistsConfirmedPlanWithMainTask() {
        JsonNode draft = generateDraft("happy-path", "整理一份竞品分析。");
        JsonNode revised = reviseDraft(draft, "增加一步风险复核");

        assertEquals(2, revised.get("version").asInt());
        assertEquals(6, revised.get("rows").size());
        assertEquals(1, revised.get("revisions").size());
        assertTrue(revised.get("revisions").get(0).asText().contains("风险复核"));
        assertTrue(revised.get("markdown").asText().contains("## 修订记录"));
        assertTrue(revised.get("markdown").asText().contains("补充风险复核"));
        assertEquals("补充风险复核", revised.get("rows").get(5).get("title").asText());

        JsonNode created = createWithPlan(revised);
        String mainTaskId = created.get("mainTaskId").asText();
        String runId = created.get("runId").asText();
        assertEquals(mainTaskId, created.get("plan").get("mainTaskId").asText());
        assertEquals(2, created.get("plan").get("version").asInt());
        assertEquals(6, created.get("plan").get("rows").size());

        awaitTerminal(runId);

        JsonNode detail = detail(mainTaskId);
        assertEquals(2, detail.get("mainTask").get("planVersion").asInt());
        assertEquals(6, detail.get("plan").get("rows").size());
        assertEquals(2, detail.get("plan").get("version").asInt());
        assertEquals("整理一份竞品分析。", detail.get("plan").get("goal").asText());
        assertTrue(detail.get("plan").get("markdown").asText().contains("补充风险复核"));

        JsonNode listed = findInList(mainTaskId);
        assertNotNull(listed);
        assertEquals(2, listed.get("planVersion").asInt());
        assertEquals(5, listed.get("subtaskCount").asInt(), "修订计划不会追加实际子任务");

        ResponseEntity<JsonNode> rejected = rest.postForEntity(
                url("/api/main-tasks/" + mainTaskId + "/plan/revisions"),
                Map.of("instruction", "再精简一步"),
                JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, rejected.getStatusCode());
        assertEquals(40904, rejected.getBody().get("code").asInt());
    }

    @Test
    @Timeout(40)
    void storedPlanCanBeRevisedWhileRunIsWaiting() {
        JsonNode created = create("with-ambiguity", null);
        String mainTaskId = created.get("mainTaskId").asText();
        String runId = created.get("runId").asText();

        awaitWaiting(runId);
        JsonNode before = detail(mainTaskId);
        assertEquals(1, before.get("plan").get("version").asInt());
        assertEquals(3, before.get("plan").get("rows").size());

        ResponseEntity<JsonNode> revised = rest.postForEntity(
                url("/api/main-tasks/" + mainTaskId + "/plan/revisions"),
                Map.of("instruction", "精简为两步"),
                JsonNode.class);
        assertEquals(HttpStatus.OK, revised.getStatusCode());
        JsonNode plan = revised.getBody().get("data");
        assertEquals(2, plan.get("version").asInt());
        assertEquals(2, plan.get("rows").size());
        assertEquals(1, plan.get("revisions").size());
        assertTrue(plan.get("revisions").get(0).asText().contains("移除步骤"));

        JsonNode after = detail(mainTaskId);
        assertEquals(2, after.get("plan").get("version").asInt());
        assertEquals(2, after.get("plan").get("rows").size());

        ResponseEntity<JsonNode> accepted = rest.postForEntity(
                url("/api/runs/" + runId + "/inputs"),
                Map.of("taskId", "t_scope", "selectedOptionId", "local-first"),
                JsonNode.class);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        awaitTerminal(runId);
    }

    @Test
    @Timeout(20)
    void rejectsUnknownScriptForPlanDraft() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                url("/api/main-tasks/plan-drafts"),
                Map.of("mockScript", "no-such-script", "goal", "测试目标"),
                JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(40003, response.getBody().get("code").asInt());
        assertFalse(response.getBody().get("message").asText().isBlank());
    }

    private JsonNode generateDraft(String script, String goal) {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                url("/api/main-tasks/plan-drafts"),
                Map.of("mockScript", script, "goal", goal),
                JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        assertEquals(0, response.getBody().get("code").asInt());
        return response.getBody().get("data");
    }

    private JsonNode reviseDraft(JsonNode draft, String instruction) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mockScript", draft.get("mockScript").asText());
        body.put("title", draft.get("title").asText());
        body.put("goal", draft.get("goal").asText());
        body.put("rows", rowsOf(draft));
        body.put("revisions", stringsOf(draft.get("revisions")));
        body.put("instruction", instruction);
        ResponseEntity<JsonNode> response = rest.postForEntity(
                url("/api/main-tasks/plan-drafts/revise"), body, JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        return response.getBody().get("data");
    }

    private JsonNode createWithPlan(JsonNode draft) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("title", draft.get("title").asText());
        plan.put("rows", rowsOf(draft));
        plan.put("revisions", stringsOf(draft.get("revisions")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mockScript", draft.get("mockScript").asText());
        body.put("goal", draft.get("goal").asText());
        body.put("plan", plan);
        ResponseEntity<JsonNode> response = rest.postForEntity(
                url("/api/main-tasks"), body, JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), String.valueOf(response.getBody()));
        return response.getBody().get("data");
    }

    private JsonNode create(String script, String goal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mockScript", script);
        if (goal != null) {
            body.put("goal", goal);
        }
        ResponseEntity<JsonNode> response = rest.postForEntity(
                url("/api/main-tasks"), body, JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), String.valueOf(response.getBody()));
        return response.getBody().get("data");
    }

    private List<Map<String, String>> rowsOf(JsonNode draft) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode row : draft.get("rows")) {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("title", row.get("title").asText());
            value.put("agent", row.get("agent").asText());
            value.put("dependency", row.get("dependency").asText());
            value.put("output", row.get("output").asText());
            rows.add(value);
        }
        return rows;
    }

    private List<String> stringsOf(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.asText());
        }
        return values;
    }

    private JsonNode detail(String mainTaskId) {
        ResponseEntity<JsonNode> response = rest.getForEntity(
                url("/api/main-tasks/" + mainTaskId), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        return response.getBody().get("data");
    }

    private JsonNode findInList(String mainTaskId) {
        ResponseEntity<JsonNode> response = rest.getForEntity(
                url("/api/main-tasks?limit=200"), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        for (JsonNode item : response.getBody().get("data")) {
            if (mainTaskId.equals(item.get("mainTaskId").asText())) {
                return item;
            }
        }
        return null;
    }

    private void awaitWaiting(String runId) {
        awaitSnapshot(runId, data -> {
            JsonNode main = data.get("mainTask");
            if (main != null && "WAITING_INPUT".equals(main.get("status").asText())) {
                return true;
            }
            for (JsonNode subtask : data.get("subtasks")) {
                if ("WAITING_INPUT".equals(subtask.get("status").asText())) {
                    return true;
                }
            }
            return false;
        }, "运行没有进入等待抉择状态");
    }

    private void awaitTerminal(String runId) {
        awaitSnapshot(runId, data -> {
            String status = data.get("run").get("status").asText();
            return "COMPLETED".equals(status) || "FAILED".equals(status);
        }, "运行未在超时前结束");
    }

    private JsonNode awaitSnapshot(String runId,
                                   java.util.function.Predicate<JsonNode> done,
                                   String message) {
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

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
