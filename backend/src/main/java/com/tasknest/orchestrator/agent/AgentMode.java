package com.tasknest.orchestrator.agent;

/**
 * 工具权限模式：执行、规划（只读）、审核（只读 + 受限测试 Shell）。
 *
 * <p>由 {@link AgentSpec} 携带并贯穿 {@link AgentLoop}、
 * {@code ToolRegistry} 与 {@code ToolExecutor}，决定可用工具集合与 Shell 命令策略。
 * 模式是编排器为不同 Agent 角色设定的能力边界，不是用户权限；
 * 模型无法通过工具参数提升模式，调用方也不应凭模型输出切换模式。
 */
public enum AgentMode {
    /** 子任务执行：可读写文件，并可执行受危险命令策略约束的本地 Shell。 */
    EXECUTION,
    /** 规划：仅 fs.list / fs.find / fs.read 与 ask_user，不写文件、不执行 Shell。 */
    PLANNING,
    /** 审核：只读文件 + 受限测试 Shell（由审核命令白名单二次收紧）。 */
    REVIEW
}
