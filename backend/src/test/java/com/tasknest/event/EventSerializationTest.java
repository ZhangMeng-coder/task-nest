package com.tasknest.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSerializationTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void serializesEnvelopeWithWireNamesAndIsoTimestamp() throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                "evt_000001",
                "run_20260101_000000_abcdef",
                1,
                EventType.TOOL_CALL,
                Instant.parse("2026-01-01T00:00:00.123Z"),
                new TaskRef("t_child", "t_root", "收集资料", "研究员"),
                new EventPayload.ToolCall("call_1", "web_search", Map.of("query", "tasknest")));

        JsonNode node = mapper.readTree(mapper.writeValueAsString(envelope));

        assertEquals("evt_000001", node.get("eventId").asText());
        assertEquals(1, node.get("seq").asLong());
        assertEquals("tool_call", node.get("type").asText());
        assertEquals("2026-01-01T00:00:00.123Z", node.get("timestamp").asText());
        assertEquals("t_child", node.get("task").get("taskId").asText());
        assertEquals("t_root", node.get("task").get("parentTaskId").asText());
        assertEquals("web_search", node.get("payload").get("toolName").asText());
        assertEquals("tasknest", node.get("payload").get("arguments").get("query").asText());
    }

    @Test
    void serializesEmptyPayloadAsObject() throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                "evt_000002", "run_x", 2, EventType.HEARTBEAT,
                Instant.parse("2026-01-01T00:00:15Z"), null, new EventPayload.Empty());

        JsonNode node = mapper.readTree(mapper.writeValueAsString(envelope));

        assertEquals("heartbeat", node.get("type").asText());
        assertTrue(node.get("payload").isObject());
        assertTrue(node.get("payload").isEmpty());
    }

    @Test
    void rejectsUnknownWireType() {
        assertThrows(IllegalArgumentException.class, () -> EventType.fromWire("no_such_event"));
    }
}
