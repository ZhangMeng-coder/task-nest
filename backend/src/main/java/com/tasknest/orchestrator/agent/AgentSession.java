package com.tasknest.orchestrator.agent;

import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.store.UserInput;

/**
 * Agent 循环的事件出口与人工输入等待入口。执行运行与规划会话分别实现，
 * AgentLoop 不直接依赖 RunState 或规划会话存储。
 */
public interface AgentSession {

    /**
     * 发布一条 Agent 事件。实现决定 runId、持久化与 SSE 推送方式，AgentLoop 不感知存储细节。
     */
    void publish(EventType type, TaskRef task, EventPayload payload);

    /** 运行 / 规划会话是否已终止；AgentLoop 在每轮开始和每次工具调用前轮询。 */
    boolean cancelled();

    /**
     * 发出 waiting_input 并阻塞等待。会话结束或运行终止时返回 null。
     * 实现负责设置 / 清理等待状态，并在收到输入后发出 input_received。
     */
    UserInput awaitInput(TaskRef task, EventPayload.WaitingInput waiting);
}
