# TaskNest 事件契约

本文件是 P0–P2 展示层 + MySQL 持久化阶段的**前后端唯一约定**。后端
`com.tasknest.event.*`、前端 `frontend/src/api/events.ts` 与本文件必须逐字段一致。
主任务/子任务状态与快照结构见第 5.2 节。

当前阶段只运行 `MockTaskOrchestrator`，不会发起任何真实模型调用。

---

## 1. 传输格式

SSE 端点：`GET /api/runs/{runId}/events?afterSeq={N}`，`Content-Type: text/event-stream`。

每个 SSE 帧：

```
id: evt_000123
event: task_started
data: {"eventId":"evt_000123","runId":"run_...","seq":123,...}
```

- `event:` 与信封里的 `type` 完全相同（wire name，小写下划线）。
- `id:` 与信封里的 `eventId` 相同。
- `data:` 是**完整信封 JSON**，不是只含 payload。
- heartbeat 也走同样的帧格式，`type = "heartbeat"`。
- SSE 数据不包 `{code,message,data}`；REST 响应才包。

---

## 2. 信封

```json
{
  "eventId": "evt_000123",
  "runId": "run_20261021_072240_4983cb",
  "seq": 123,
  "type": "task_started",
  "timestamp": "2026-10-21T07:22:41.123Z",
  "task": {
    "taskId": "t_research",
    "parentTaskId": "t_root",
    "title": "收集竞品事实",
    "agentName": "研究员"
  },
  "payload": {}
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `eventId` | string | 默认 `evt_` + 6 位 seq；脚本可覆盖，用于重复 ID 演练。前端按它去重。 |
| `runId` | string | 运行 ID。 |
| `seq` | number | 单次运行内严格递增，从 1 开始；包括心跳。重连续传游标。 |
| `type` | string | 14 种事件类型之一，见第 3 节。 |
| `timestamp` | string | ISO-8601 UTC，毫秒精度。 |
| `task` | object \| null | 仅任务相关事件携带；`run_started` / `run_completed` / `error` / `heartbeat` 为 `null`。 |
| `payload` | object | 由 `type` 决定，永不为 `null`；无载荷时为 `{}`。 |

`task` 对象：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskId` | string | 任务唯一键。 |
| `parentTaskId` | string \| null | 根任务为 `null`。 |
| `title` | string | 展示标题。 |
| `agentName` | string | 执行者名称。 |

---

## 3. 事件类型与 payload

| `type` | 触发时机 | `task` | payload |
| --- | --- | --- | --- |
| `run_started` | 运行开始 | null | `goal: string` |
| `plan` | 规划完成 | 根任务 | `tasks: PlannedTask[]` |
| `task_started` | 子任务开始/重试开始 | 有 | `{}` |
| `task_thought` | 思考增量 | 有 | `delta: string` |
| `prompt_assembled` | 提示词拼接完成 | 有 | 见 3.2 |
| `tool_call` | 工具调用发起 | 有 | `callId?: string`, `toolName: string`, `arguments: object` |
| `tool_result` | 工具返回 | 有 | `callId?: string`, `toolName: string`, `status: "success" \| "error"`, `result: any`, `durationMs: number` |
| `waiting_input` | 等待用户抉择，任务暂停 | 有 | `question: string`, `options: DecisionOption[]` |
| `input_received` | 抉择已提交，任务恢复 | 有 | `selectedOptionId: string` |
| `task_completed` | 子任务完成 | 有 | `summary: string` |
| `task_failed` | 子任务失败 | 有 | `error: string`, `retryable: boolean` |
| `run_completed` | 运行完成 | null | `summary: string` |
| `error` | 系统级错误，运行终止 | null | `code: string`, `message: string` |
| `heartbeat` | 保活 | null | `{}` |

### 3.1 `plan.tasks[]`

```json
{
  "taskId": "t_research_web",
  "parentTaskId": "t_research",
  "title": "检索公开资料",
  "agentName": "检索员",
  "dependsOn": []
}
```

- 恰好一个根任务（`parentTaskId = null`）；`plan` 必须包含根任务本身。
- 父任务必须存在于同一 `plan`；父子关系不得成环。
- `dependsOn` 可以引用同一 `plan` 中的任意任务，仅用于展示/排序，Mock 不强制调度。
- 事件只能引用 `plan` 中声明过的 `taskId`。

### 3.2 `prompt_assembled`

```json
{
  "systemPrompt": "你是一名严谨的技术产品研究员。",
  "userPrompt": "围绕 AI 编程助手收集事实。",
  "contextSnippets": [
    { "source": "brief.md#scope", "content": "只比较个人开发者订阅产品。" }
  ],
  "finalPrompt": "系统指令：……\n用户目标：……\n上下文：……"
}
```

- 三段展示：`systemPrompt` / `userPrompt` / `contextSnippets`。
- `finalPrompt` 是实际拼接结果，供调试对照。
- `contextSnippets[].source` 是来源标签，`content` 是片段正文。

### 3.3 `waiting_input.options[]`

```json
{
  "id": "local-first",
  "label": "本地优先",
  "description": "实现快、无账号依赖；多设备需手动迁移。"
}
```

- 至少两个选项，`id` 在单个事件内唯一。
- `id` 是提交抉择时 `selectedOptionId` 的取值。

### 3.4 `tool_call` / `tool_result` 配对

- 优先按 `callId` 配对；两者都可省略 `callId`，此时前端按同一 `taskId` 的 FIFO 顺序配对。
- `tool_result.status` 只有 `success` / `error`。
- `result` 形状由工具决定，可为基础类型、对象、数组或 `null`。

---

## 4. 顺序、重连与幂等

### 4.1 序号

- `seq` 由后端在**持久化事件时**分配，因此历史顺序永远是权威顺序。
- heartbeat 也持久化、也占用 `seq`。
- 前端不得依赖到达顺序建树；事件到达后按 `seq` 排序应用。

### 4.2 `afterSeq`

`GET /events?afterSeq=N`：

1. 校验 `0 <= N <= lastSeq`，否则返回 `40002`。
2. 先补发所有 `seq > N` 的历史事件。
3. 再接入实时事件；实时事件与补发共用同一连接队列，保证交接处不错序。
4. 连接建立后立即推送一个 heartbeat，之后约每 15s 一次。
5. 运行已终止时，补发/发送最后一个终止事件后关闭连接；前端**不要重连**已终止的运行。

同一条连接内按 `seq` 递增发送；噪声脚本的 `dispatchDelayMs` 只影响投递时机，不改变 `seq`。

### 4.3 去重与缺口

- 前端维护已应用的 `eventId` 集合：重复 `eventId` 跳过业务语义，但**仍然推进 seq 游标**。
- 到达事件的 `seq == lastSeq + 1` 时立即应用；`seq > lastSeq + 1` 时短暂缓冲（建议 2s）。
- 2s 后缺口未补齐，调用 `GET /api/runs/{runId}` 拉快照重建，再重新连接 `afterSeq = snapshot.run.lastSeq`。
- `task_thought` 是增量：同一任务的多条 `delta` 按 seq 追加，不覆盖。

### 4.4 终止

- `run_completed`：运行 `COMPLETED`；未终止任务在快照投影中补为 `FAILED`。
- `error`：运行 `FAILED`；未终止任务同样补为 `FAILED`。
- 两者都会让后端在 flush 后关闭 SSE。前端收到后标记连接为已断开且不再重连。

---

## 5. REST API

统一响应包装：

```json
{ "code": 0, "message": "ok", "data": {} }
```

失败时 `code != 0`，HTTP 状态码与 `code` 共同表达错误。

### 5.1 创建主任务

`POST /api/main-tasks` → `201`

```json
{ "mockScript": "happy-path", "goal": "可选目标" }
```

`data`：

```json
{
  "mainTaskId": "mt_20261021_072240_4983cb",
  "runId": "run_20261021_072240_4983cb",
  "mockScript": "happy-path",
  "status": "PENDING",
  "createdAt": "2026-10-21T07:22:40.615Z"
}
```

`POST /api/runs` 保留为兼容入口，响应额外带 `mainTaskId`。`goal` 可选，最多 2000 字。
`mockScript` 允许值由 `application.yml` 的 `tasknest.mock.scripts` 决定，默认：
`happy-path`、`with-ambiguity`、`with-failure`、`with-noise`。

`GET /api/main-tasks?limit=50` 返回持久化主任务列表；`GET /api/main-tasks/{mainTaskId}`
返回档案详情（主任务、子任务、提示词、中间结果、事件原文），未知 ID 返回 `40403`。

### 5.2 全量快照

`GET /api/runs/{runId}` → `200`

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "run": {
      "runId": "run_...",
      "mainTaskId": "mt_...",
      "mockScript": "with-ambiguity",
      "goal": "为 CLI 工具设计离线缓存方案……",
      "status": "RUNNING",
      "createdAt": "2026-10-21T07:22:40.615Z",
      "updatedAt": "2026-10-21T07:22:42.007Z",
      "lastSeq": 18
    },
    "mainTask": {
      "mainTaskId": "mt_...",
      "taskId": "t_root",
      "title": "确定缓存方案",
      "agentName": "协调者",
      "status": "RUNNING",
      "summary": null,
      "error": null,
      "selectedOptionId": null,
      "waitingInput": null,
      "startedAt": "2026-10-21T07:22:40.900Z",
      "completedAt": null
    },
    "subtasks": [
      {
        "taskId": "t_scope",
        "title": "评估同步策略",
        "agentName": "架构师",
        "status": "WAITING_INPUT",
        "summary": null,
        "error": null,
        "dependsOn": [],
        "order": 0,
        "selectedOptionId": null,
        "waitingInput": {
          "question": "首发版本的缓存策略选哪条？",
          "options": [ /* DecisionOption[] */ ]
        },
        "startedAt": "2026-10-21T07:22:41.000Z",
        "completedAt": null
      }
    ],
    "events": [ /* 完整信封数组，从 seq=1 起 */ ]
  }
}
```

- 快照固定为 `run + mainTask + subtasks + events`，`subtasks` 只包含直属子任务。
- `plan` 到达前主任务仍存在，状态为 `PENDING`；`subtasks` 为空数组。
- `RunStatus`：`RUNNING` / `COMPLETED` / `FAILED`。
- 主任务 `MainTaskStatus`：`PENDING` / `RUNNING` / `WAITING_INPUT` / `COMPLETED` / `FAILED`。
- 子任务 `SubtaskStatus`：`PENDING` / `RUNNING` / `WAITING_INPUT` / `RETRYING` / `SUCCESS` / `FAILED`。
- `summary`、`error`、`waitingInput` 可能为 `null`。

### 5.3 提交抉择

`POST /api/runs/{runId}/inputs` → `202`

```json
{ "taskId": "t_scope", "selectedOptionId": "local-first" }
```

`data`：

```json
{ "runId": "run_...", "taskId": "t_scope", "selectedOptionId": "local-first" }
```

后端校验通过后清空等待态、推送 `input_received`，再继续脚本后续事件。
同一运行在 Mock 实现中最多只有一个等待输入的任务。

### 5.4 SSE 事件流

`GET /api/runs/{runId}/events?afterSeq=N` → `200 text/event-stream`

- `afterSeq` 缺省为 `0`。
- 见第 4 节。

### 5.5 错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| 0 | 2xx | 成功 |
| 40001 | 400 | 参数校验失败 |
| 40002 | 400 | `afterSeq` 非法（负数或大于 `lastSeq`） |
| 40003 | 400 | 不支持的 `mockScript` |
| 40401 | 404 | 运行不存在 |
| 40402 | 404 | 任务不存在 |
| 40403 | 404 | 主任务档案不存在 |
| 40901 | 409 | 任务当前不处于等待输入状态 |
| 40902 | 409 | 抉择选项无效 |
| 40903 | 409 | 运行已结束 |
| 50001 | 500 | 服务内部错误 |
| 50002 | 500 | 内置 Mock 脚本无效或缺失 |

注意：对 SSE 端点做参数校验失败时，后端在建立流之前返回 JSON 包装错误，不使用 SSE 帧。

---

## 6. Mock 脚本

脚本位置：`backend/src/main/resources/mock/{name}.json`。

```json
{
  "name": "happy-path",
  "description": "可选说明",
  "events": [
    {
      "delayMs": 250,
      "type": "task_thought",
      "taskId": "t_research",
      "payload": { "delta": "..." },
      "eventId": "evt_custom_1",
      "dispatchDelayMs": 0,
      "dropDispatch": false
    }
  ]
}
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `delayMs` | 0 | 相对于上一条事件执行完成的等待时间，0–60000。 |
| `type` | 必填 | 14 种类型之一；脚本不得定义 `input_received` / `heartbeat`。 |
| `taskId` | 任务事件必填 | 必须出现在 `plan` 中；`run_*` / `error` 禁止携带。 |
| `task` | 自动填充 | 可选；若显式给出，必须与 `plan` 中同 `taskId` 的四个字段一致。 |
| `payload` | 必填 | 按类型校验。 |
| `eventId` | 自动 | `evt_[A-Za-z0-9_]{1,64}`；允许重复以演练前端幂等。 |
| `dispatchDelayMs` | 0 | 仅影响**推送**延迟，0–1000；事件仍按原 `seq` 持久化。用于乱序演练。 |
| `dropDispatch` | false | 不推送给实时连接，但事件仍持久化。用于缺失/快照恢复演练。 |
| `branches` | 无 | 仅 `waiting_input` 可定义；键必须与 `options[].id` 一一对应。 |

约束：

- 必须包含且只包含一个 `plan`；`plan` 之前只允许 `run_started`。
- 最后一个事件必须是 `run_completed` 或 `error`；终止事件之后不得有事件。
- 终止事件禁止 `dispatchDelayMs` / `dropDispatch`；`waiting_input` 禁止 `dropDispatch`。
- `branches` 中不得出现 `run_started` / `plan` / 终止事件，但允许嵌套 `waiting_input`。

内置脚本：

| 名称 | 用途 |
| --- | --- |
| `happy-path` | 两层结构（1 主 + 5 直属子任务），全成功，含思考、提示词、工具调用。 |
| `with-ambiguity` | 一个 `waiting_input`、两个选项，选择后进入不同分支，再继续主脚本。 |
| `with-failure` | 凭据刷新失败 `retryable=true`，重新 `task_started` 后成功，最终全部 SUCCESS。 |
| `with-noise` | 重复 `eventId`、`dispatchDelayMs` 乱序、`dropDispatch` 丢投。 |

---

## 7. 前端类型定义

TypeScript 类型位于 `frontend/src/api/events.ts`，核心形状：

```ts
export type EventType =
  | 'run_started' | 'plan' | 'task_started' | 'task_thought'
  | 'prompt_assembled' | 'tool_call' | 'tool_result'
  | 'waiting_input' | 'input_received' | 'task_completed'
  | 'task_failed' | 'run_completed' | 'error' | 'heartbeat';

export interface TaskRef {
  taskId: string;
  parentTaskId: string | null;
  title: string;
  agentName: string;
}

export interface TaskNestEvent<T extends EventType = EventType> {
  eventId: string;
  runId: string;
  seq: number;
  type: T;
  timestamp: string;
  task: TaskRef | null;
  payload: EventPayloadMap[T];
}
```

前端必须：

- 严格模式，不使用 `any` 承载 payload；
- 对未知 `type` 只记录日志并推进 seq 游标，不影响其他事件；
- 对解析失败的单条事件记录日志，不中断连接。

---

## 8. 本阶段边界

- 不调用真实模型、Spring AI ChatClient 或 TaskTool。
- 实时 `RunState` 与 SSE 游标在内存中；主任务、子任务、提示词、中间结果和事件原文同步写入
  MySQL（MyBatis-Plus），可在主任务库中只读查询。进程重启后实时运行失效，但档案仍可打开。
- 不引入 MQ / Redis / 用户体系 / 鉴权。
- P3 替换为 `SpringAiTaskOrchestrator` 时，本文档定义的 SSE、REST、前端类型保持不变。
