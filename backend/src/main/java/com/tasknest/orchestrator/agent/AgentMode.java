package com.tasknest.orchestrator.agent;

/** 工具权限模式：执行、规划（只读）、审核（只读 + 受限测试 Shell）。 */
public enum AgentMode {
    EXECUTION,
    PLANNING,
    REVIEW
}
