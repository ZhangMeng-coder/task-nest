package com.tasknest.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.config.TaskNestProperties;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockScriptLoaderTest {

    private final MockScriptLoader loader = new MockScriptLoader(new ObjectMapper(), properties());

    @Test
    void loadsEveryConfiguredScriptAndEndsWithTerminalEvent() {
        for (String name : List.of("happy-path", "with-ambiguity", "with-failure", "with-noise")) {
            MockScript script = loader.load(name);
            assertFalse(script.events().isEmpty(), name);
            assertEquals(EventType.RUN_COMPLETED, script.events().get(script.events().size() - 1).type(), name);
            assertTrue(script.events().stream().anyMatch(event -> event.type() == EventType.PLAN), name);
            assertTrue(script.events().stream()
                    .filter(event -> event.type() != EventType.RUN_STARTED
                            && event.type() != EventType.PLAN
                            && !event.type().isTerminal())
                    .allMatch(event -> event.task() != null), name);
        }
    }

    @Test
    void bindsTaskRefsFromPlanForEveryEvent() {
        MockScript script = loader.load("happy-path");
        MockEvent researchStart = script.events().stream()
                .filter(event -> event.type() == EventType.TASK_STARTED)
                .filter(event -> "t_research".equals(event.taskId()))
                .findFirst()
                .orElseThrow();
        assertNotNull(researchStart.task());
        assertEquals("t_root", researchStart.task().parentTaskId());
        assertEquals("收集竞品事实", researchStart.task().title());
        assertEquals("研究员", researchStart.task().agentName());
    }

    @Test
    void bindsBranchesToDecisionOptionIds() {
        MockScript script = loader.load("with-ambiguity");
        MockEvent waiting = script.events().stream()
                .filter(event -> event.type() == EventType.WAITING_INPUT)
                .findFirst()
                .orElseThrow();
        EventPayload.WaitingInput payload = (EventPayload.WaitingInput) waiting.payload();
        assertEquals(payload.options().stream().map(EventPayload.DecisionOption::id).toList(),
                List.copyOf(waiting.branches().keySet()));
        waiting.branches().values().forEach(branch -> assertTrue(
                branch.stream().allMatch(event -> event.task() != null)));
    }

    @Test
    void keepsNoiseMetadata() {
        MockScript script = loader.load("with-noise");
        List<String> ids = script.events().stream().map(MockEvent::eventId).filter(id -> id != null).toList();
        assertTrue(ids.contains("evt_dup_thought"));
        assertTrue(script.events().stream().anyMatch(MockEvent::dropDispatch));
        assertTrue(script.events().stream().anyMatch(event -> event.dispatchDelayMs() > 0));
    }

    @Test
    void rejectsNamesOutsideAllowList() {
        assertTrue(loader.isAllowed("happy-path"));
        assertFalse(loader.isAllowed("../happy-path"));
    }

    private TaskNestProperties properties() {
        TaskNestProperties properties = new TaskNestProperties();
        properties.getMock().setScripts(List.of("happy-path", "with-ambiguity", "with-failure", "with-noise"));
        properties.getMock().setDelayScale(1.0);
        return properties;
    }
}
