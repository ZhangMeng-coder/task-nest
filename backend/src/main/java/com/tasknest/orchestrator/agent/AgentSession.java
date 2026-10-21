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

    void publish(EventType type, TaskRef task, EventPayload payload);

    boolean cancelled();

    /**
     * 发出 waiting_input 并阻塞等待。会话结束或运行终止时返回 null。
     * 实现负责设置 / 清理等待状态，并在收到输入后发出 input_received。
     */
    UserInput awaitInput(TaskRef task, EventPayload.WaitingInput waiting);
}
