package com.tasknest.orchestrator;

import java.util.List;

public record MockScript(String name, String description, List<MockEvent> events) {
}
