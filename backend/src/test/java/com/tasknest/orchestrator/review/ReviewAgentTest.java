package com.tasknest.orchestrator.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.event.EventPayload;
import com.tasknest.event.TaskRef;
import com.tasknest.orchestrator.agent.AgentSession;
import com.tasknest.orchestrator.llm.LlmClient;
import com.tasknest.service.SecretProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReviewAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void cancellationSkipsTestExecution() {
        LlmClient llm = mock(LlmClient.class);
        SecretProvider secrets = mock(SecretProvider.class);
        ReviewTestRunner testRunner = mock(ReviewTestRunner.class);
        AgentSession session = mock(AgentSession.class);
        when(session.cancelled()).thenReturn(true);
        ReviewAgent agent = new ReviewAgent(llm, new ObjectMapper(), secrets, testRunner);
        ReviewContext context = new ReviewContext(
                "目标", tempDir.toString(),
                new EventPayload.PlannedTask("t1", "root", "任务", "developer",
                        List.of(), "说明", List.of(), null, "mvn test"),
                "小结", List.of("src/Main.java"), "1 file changed", null, 1);

        ReviewResult result = agent.review(
                session, new TaskRef("t1", "root", "任务", "developer"), tempDir, context);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.testStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.testEvidence()).isEmpty();
        verifyNoInteractions(testRunner);
    }
}
