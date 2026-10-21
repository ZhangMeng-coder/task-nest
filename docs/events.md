# TaskNest 事件契约

本文件是 **P3 软件开发 Agent 阶段**的**前后端唯一约定**。后端
`com.tasknest.event.*`、前端 `frontend/src/api/events.ts` 与本文件必须逐字段一致。
主任务/子任务状态与快照结构见第 5.2 节。

当前阶段：
- 规划会话（创建主任务前）与执行运行（确认后）各有一条 SSE 流，事件信封一致。
- `MockTaskOrchestrator` 仍用于演示脚本；真实模式由 `CodingTaskOrchestrator` 调用
  OpenAI 兼容模型与本地工具。两者的 SSE、REST 与前端类型完全一致。

---

## 1. 传输格式

SSE 端点：
- 执行运行：`GET /api/runs/{runId}/events?afterSeq={N}`
- 规划会话：`GET /api/main-tasks/plan-sessions/{planSessionId}/events?afterSeq={N}`

两者均为 `Content-Type: text/event-stream`。规划会话的 `runId` 等于 `planSessionId`，
根任务固定为 `plan_root`。

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
| `type` | string | 17 种事件类型之一，见第 3 节。 |
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
| `plan` | 规划完成 / 执行期重排 | 根任务 | 见 3.1 |
| `task_started` | 子任务开始/重试开始 | 有 | `{}` |
| `task_thought` | 思考增量或摘要 | 有 | `delta: string` |
| `prompt_assembled` | 提示词拼接完成 | 有 | 见 3.2 |
| `tool_call` | 工具调用发起 | 有 | `callId?: string`, `toolName: string`, `arguments: object` |
| `tool_result` | 工具返回 | 有 | `callId?: string`, `toolName: string`, `status: "success" \| "error"`, `result: any`, `durationMs: number` |
| `waiting_input` | 等待用户抉择，任务暂停 | 有 | 见 3.3 |
| `input_received` | 抉择已提交，任务恢复 | 有 | `selectedOptionId: string \| null`, `customInput?: string \| null` |
| `task_completed` | 子任务完成 | 有 | `summary: string` |
| `task_failed` | 子任务失败/等待重做 | 有 | `error: string`, `retryable: boolean` |
| `review_started` | 审核开始，子任务进入 REVIEWING | 有 | `attempt: number` |
| `review_completed` | 审核结论 | 有 | 见 3.5 |
| `git_checkpoint` | Git 分支/提交/合并/回滚 | 有 | 见 3.6 |
| `run_completed` | 运行完成 | null | `summary: string` |
| `error` | 系统级错误，运行终止 | null | `code: string`, `message: string` |
| `heartbeat` | 保活 | null | `{}` |

### 3.1 `plan`

执行运行开始时由编排器发送用户确认的计划；动态重排时再次发送，`revision` 递增。

```json
{
  "revision": 1,
  "reason": "执行期发现需要补充迁移脚本",
  "changedTaskIds": ["t_migrate"],
  "tasks": [
    {
      "taskId": "plan_root",
      "parentTaskId": null,
      "title": "为 CLI 增加离线缓存",
      "agentName": "coordinator",
      "dependsOn": [],
      "description": null,
      "acceptanceCriteria": null,
      "allowedTools": null,
      "testCommand": null
    },
    {
      "taskId": "t_research",
      "parentTaskId": "plan_root",
      "title": "梳理缓存读取路径",
      "agentName": "implementer",
      "dependsOn": [],
      "description": "阅读现有缓存实现并列出改动点",
      "acceptanceCriteria": ["列出所有读取入口", "说明兼容策略"],
      "allowedTools": ["fs.list", "fs.find", "fs.read"],
      "testCommand": "none"
    }
  ]
}
```

- 恰好一个根任务（`parentTaskId = null`）；`plan` 必须包含根任务本身；只允许两层。
- `taskId` 唯一；`dependsOn` 只能引用同一计划中的任务，且不得成环。
- `description`、`acceptanceCriteria`、`allowedTools`、`testCommand` 为 P3 字段：
  真实规划器必须提供前两项；`allowedTools` 缺省表示执行工具全集；`testCommand` 可为
  `"none"` 表示不需要测试。
- `revision` 从 1 开始；`reason` / `changedTaskIds` 只在动态重排时携带。动态重排只
  新增任务、修改或取消 `PENDING` 任务；被取消的任务不再出现在新计划中，子任务状态为
  `CANCELLED`。
- Mock 脚本的 `plan` 事件可以只提供 `taskId/parentTaskId/title/agentName/dependsOn`。

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

### 3.3 `waiting_input`

```json
{
  "question": "缓存失效后如何回退？",
  "options": [
    {
      "id": "local-first",
      "label": "本地优先",
      "description": "实现快、无账号依赖；多设备需手动迁移。"
    }
  ],
  "allowCustom": true,
  "customPlaceholder": "也可以直接描述你希望的回退方式"
}
```

- `options` 可以为空，但 `allowCustom` 必须为 `true`；最多 8 个选项。
- `id` 在单个事件内唯一，是提交抉择时 `selectedOptionId` 的取值。
- `allowCustom` 缺省表示不允许自由输入（兼容 Mock）；P3 的 `ask_user` 默认允许。
- 提交 `customInput` 时 `selectedOptionId` 可以为 `null`；两者至少有一个非空。
- 规划会话与执行运行都可能发送该事件；`task` 指向提问的规划根任务或子任务。

### 3.4 `tool_call` / `tool_result` 配对

- 优先按 `callId` 配对；两者都可省略 `callId`，此时前端按同一 `taskId` 的 FIFO 顺序配对。
- `tool_result.status` 只有 `success` / `error`。
- `result` 形状由工具决定，可为基础类型、对象、数组或 `null`。

### 3.5 `review_completed`

```json
{
  "attempt": 1,
  "verdict": "REWORK",
  "summary": "缓存失效分支没有回退测试。",
  "issues": [
    {
      "severity": "MAJOR",
      "file": "src/cache.ts",
      "line": 42,
      "message": "缺少失效回退逻辑",
      "suggestion": "补充 stale 分支并使用旧值"
    }
  ],
  "testStatus": "PASSED",
  "testEvidence": [
    {
      "command": "npm test",
      "exitCode": 0,
      "timedOut": false,
      "summary": "42 passed"
    }
  ],
  "modelName": "gpt-4o",
  "durationMs": 18342
}
```

- `verdict`：`PASS` / `REWORK`。
- `severity`：`BLOCKER` / `MAJOR` / `MINOR`；存在 BLOCKER/MAJOR 时 verdict 必为 REWORK。
- `testStatus`：`PASSED` / `FAILED` / `UNAVAILABLE`；测试失败时 verdict 必为 REWORK。
- `file`、`line`、`suggestion` 可为 `null`；`exitCode` 在超时/不可用时可为 `null`。
- 平台会强制校验规则：模型输出与平台测试证据冲突时，以平台证据为准。

### 3.6 `git_checkpoint`

```json
{
  "action": "branch_created",
  "branch": "tasknest/subtasks/mt_.../t_impl-a1",
  "targetBranch": null,
  "commit": null,
  "attempt": 1,
  "noChanges": false,
  "message": "创建子任务分支（第 1 次尝试）"
}
```

- `action`：`branch_created` / `committed` / `merged` / `rolled_back` / `branch_deleted`。
- `targetBranch` 只在 `merged` / `rolled_back` 时填写。
- `commit` 在 `committed` / `merged` / 删除前填写短 SHA；其余可为 `null`。
- `attempt` 仅子任务分支事件携带；分支为主任务分支时可为 `null`。
- `noChanges=true` 表示没有文件变化，未创建空提交。
- Git 操作由编排器独占；子 Agent 只能通过 `shell.exec` 读取只读 Git。

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

### 5.1.1 规划会话（P3）

创建主任务前先建立规划会话；确认前不产生主任务。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/main-tasks/plan-sessions` | 创建会话，body `{goal, workingDir, mockScript?}` |
| GET | `/api/main-tasks/plan-sessions/{id}` | 会话状态、计划、最后 seq |
| GET | `/api/main-tasks/plan-sessions/{id}/events?afterSeq=N` | 规划 SSE |
| POST | `/api/main-tasks/plan-sessions/{id}/inputs` | 提交抉择 `{taskId, selectedOptionId?, customInput?}` |
| POST | `/api/main-tasks/plan-sessions/{id}/revisions` | 按提示修订计划 `{instruction}` |
| POST | `/api/main-tasks/plan-sessions/{id}/confirm` | 确认计划并创建主任务 |
| POST | `/api/main-tasks/plan-sessions/{id}/cancel` | 取消会话并释放工作区锁 |

创建响应：

```json
{ "planSessionId": "ps_...", "status": "RUNNING", "lastSeq": 1 }
```

会话状态：`RUNNING` / `WAITING_INPUT` / `READY` / `CONFIRMED` / `CANCELLED` / `FAILED`。
`READY` 时 `POST .../revisions` 可按提示重新生成；重排后 `revision` 递增。
`POST .../confirm` 成功返回：

```json
{ "mainTaskId": "mt_...", "runId": "run_...", "workingDir": "/repo", "planVersion": 2 }
```

确认前重新校验 Git 与工作目录；失败时会话保持 `READY`，不会产生半成品主任务。
规划会话从创建到确认期间持有该仓库的工作区锁。

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
- 子任务 `SubtaskStatus`：`PENDING` / `RUNNING` / `WAITING_INPUT` / `RETRYING` /
  `REVIEWING` / `SUCCESS` / `FAILED` / `CANCELLED`。
- `summary`、`error`、`waitingInput` 可能为 `null`；`selectedOptionId` 可能为 `null`
  （仅提交了自由输入）。
- `subtasks[].dependsOn` 来自计划；动态重排后新任务与仍为 `PENDING` 的依赖会更新，
  已开始任务的定义不可修改。

### 5.3 提交抉择

`POST /api/runs/{runId}/inputs` → `202`

```json
{ "taskId": "t_scope", "selectedOptionId": "local-first", "customInput": null }
```

- `selectedOptionId` 与 `customInput` 至少一个非空；自由输入最多 2000 字。
- 若选项不允许自由输入，`customInput` 必须为空；未知选项返回 `40902`。

`data`：

```json
{
  "runId": "run_...",
  "taskId": "t_scope",
  "selectedOptionId": "local-first",
  "customInput": null
}
```

后端校验通过后清空等待态、推送 `input_received`，再继续脚本或 Agent 循环。
同一运行同时最多只有一个等待输入的任务。

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
| 40004 | 400 | 工作目录不存在或不可访问 |
| 40006 | 400 | 抉择提交不合法（选项与自由输入均空） |
| 40010 | 400 | 本机找不到可执行的 git |
| 40011 | 400 | 工作目录不是 Git 仓库 |
| 40012 | 400 | 工作目录不是 Git 仓库根目录 |
| 40013 | 400 | Git 处于 detached HEAD |
| 40014 | 400 | Git 工作区存在未提交改动 |
| 40401 | 404 | 运行不存在 |
| 40402 | 404 | 任务不存在 |
| 40403 | 404 | 主任务档案不存在 |
| 40405 | 404 | 规划会话不存在 |
| 40901 | 409 | 任务当前不处于等待输入状态 |
| 40902 | 409 | 抉择选项无效 |
| 40903 | 409 | 运行已结束 |
| 40905 | 409 | 规划会话正在运行 |
| 40906 | 409 | 规划会话尚未就绪 |
| 40907 | 409 | 计划重排不合法 |
| 40910 | 409 | 同一仓库已有活动运行（`WORKSPACE_BUSY`） |
| 40911 | 409 | 运行期间 Git 分支被外部切换 |
| 40912 | 409 | Git 合并冲突 |
| 50001 | 500 | 服务内部错误 |
| 50002 | 500 | 内置 Mock 脚本无效或缺失 |
| 50010 | 500 | Git 分支创建失败 |
| 50011 | 500 | Git 提交失败 |
| 50012 | 500 | Git 回滚失败 |
| 50201 | 502 | 模型请求失败 |
| 50202 | 502 | 模型不支持工具调用 |
| 50301 | 503 | 规划 Agent 不可用 |

Agent / 编排器还会在 `error.payload.code` 中使用以下字符串码（HTTP 层不一定出现）：
`SUBAGENT_FAILED`、`REVIEW_REJECTED`、`REVIEW_INVALID_OUTPUT`、`REVIEW_UNAVAILABLE`、
`REVIEW_SIDE_EFFECT`、`AGENT_MAX_ITERATIONS`、`AGENT_TIMEOUT`、`ORCHESTRATOR_ERROR`、
`ORCHESTRATOR_REJECTED`。

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
| `type` | 必填 | 17 种类型之一；脚本不得定义 `input_received` / `heartbeat` / `review_started` / `review_completed` / `git_checkpoint`。 |
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
  | 'task_failed' | 'review_started' | 'review_completed' | 'git_checkpoint'
  | 'run_completed' | 'error' | 'heartbeat';

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

- P3 使用自研的 OpenAI 兼容 `/chat/completions` 客户端（`LlmClient`），不引入
  Spring AI；模型必须支持 function tool calling。
- 工具只有本地文件读写、目录/文件查询与本地 Shell；没有远端执行、浏览器或 MCP。
- Git 只做本地分支、提交、合并与删除；不 fetch / pull / push / 创建 PR。
- 实时 `RunState` 与 SSE 游标在内存中；主任务、子任务、计划、提示词、中间结果、
  审核记录和事件原文写入 MySQL（MyBatis-Plus）。进程重启后实时运行失效，但档案仍可打开。
- 不引入 MQ / Redis / 用户体系 / 鉴权；不做 token / 费用预算。
- 本文档定义的 SSE、REST 与前端类型是唯一契约；`MockTaskOrchestrator` 与
  `CodingTaskOrchestrator` 必须产生相同形状的事件。
