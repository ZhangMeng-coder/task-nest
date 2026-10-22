# TaskNest P3 软件开发 Agent 平台设计

> 状态：**已实现**（2026-10-21）。本设计突破 P0–P2 的阶段边界（真实模型、文件写入、
> Shell 执行），阶段边界已同步更新到 `CLAUDE.md`、`docs/requirements.md` 与 `docs/events.md`。

## 1. 定位

P3 把 TaskNest 从「任务展示工作台」升级为本地软件开发 Agent 平台，交互与执行模式参照
Claude Code：

- 主 Agent 负责理解需求、分析代码库、拆分可执行的子任务。
- 子 Agent 按依赖顺序逐个子任务实现，使用受控的文件与 Shell 工具。
- 审核 Agent 在子任务实现后独立审查，决定通过或退回重做。
- 所有工具调用、思考、提示词、审核结论与文件变更都通过现有事件契约展示并落库。

## 2. 已确认决策

| 编号 | 决策 |
| --- | --- |
| P3-D01 | 先完成契约与架构设计，确认后再实现。 |
| P3-D02 | 文件与 Shell 工具采用工作目录沙箱 + 危险命令拦截；被拦截的命令返回工具错误，不执行。 |
| P3-D03 | 是否启用子任务审核 Agent 只做全局设置，不按主任务单独覆盖。 |
| P3-D04 | 审核不通过时退回原子任务重做，限制次数；超过上限则子任务与主任务失败。 |
| P3-D05 | 子任务串行、按依赖顺序执行；一期不做并行子 Agent。 |
| P3-D06 | 审核 Agent 复用主 Agent 的 Base URL、模型与 API Key。 |
| P3-D07 | 软件开发任务必须选择工作目录，所有文件与命令限制在该目录内。 |
| P3-D08 | 采用主任务临时分支 + 子任务临时分支：子任务审核通过后合并回主任务分支，失败/退回删除子任务分支；全部成功后主任务分支合并回原分支，整体失败删除主任务分支。 |
| P3-D09 | 工作目录必须是 Git 仓库根目录，且工作区无未提交改动（含未跟踪文件）；否则拒绝启动。 |
| P3-D10 | 创建前规划阶段也使用 SSE：独立规划会话推送思考、工具调用、问题与计划草案；确认后才创建主任务。 |
| P3-D11 | 主 Agent 与子 Agent 的歧义都通过 `waiting_input` 推送选项和自由输入框；用户可只选项、只输入或两者都填。 |
| P3-D12 | Git 操作由 Orchestrator 内部执行，不作为模型工具暴露；子 Agent 不得执行 Git 写操作。 |
| P3-D13 | 审核 Agent 独立执行测试，不只看子 Agent 的记录；测试失败必须 `REWORK`。 |
| P3-D14 | 主 Agent 允许在子任务边界动态重排计划；只能修改未开始任务，已完成/运行中任务不可变。 |
| P3-D15 | Shell 只执行本机命令，不提供远程 Shell、SSH 或容器执行；网络访问不单独限制。 |
| P3-D16 | 一期不增加 token / 费用预算配置，沿用固定超时与调用次数上限。 |

## 3. 目标与非目标

### 3.1 目标

1. 从自然语言需求生成结构化计划，并允许用户在创建前确认。
2. 为每个子任务启动独立 Agent 循环，支持工具调用、结果回传与多轮迭代。
3. 提供六类工具：目录查询、文件查询、文件读取、文件创建、文件写入、Shell 执行。
4. 可配置的审核 Agent，形成「实现 → 审核 → 退回重做」质量门禁。
5. 保留现有两层任务模型、SSE 事件契约、MySQL 档案与前端工作台。
6. 工具执行可审计、可追溯，密钥不进入提示词、工具输出与日志。
7. 用 Git 临时分支为每个主任务和子任务建立可回滚的版本边界，只操作本地仓库，不推送远端。
8. 主 Agent 与子 Agent 的歧义统一通过 SSE `waiting_input` 交互，支持选项与自由文本。
9. 审核 Agent 独立执行项目测试，把真实命令与结果作为审核证据。
10. 主 Agent 可在子任务边界根据新信息动态重排未开始的任务，计划版本可追溯。

### 3.2 非目标

- 并行子 Agent、多主 Agent 协同。
- 多模型路由、按任务选择模型、RAG、向量库。
- 用户体系、权限、登录、多租户。
- 消息队列、Redis、微服务拆分。
- OS 级容器/虚拟机沙箱；一期是进程内防护，不能替代真正的安全沙箱。
- 浏览器自动化、网络检索、MCP 工具市场。
- Git 远端操作、推送、PR、自动解决合并冲突；一期只做本地分支与本地合并。
- 非 Git 仓库、脏工作区、仓库子目录或 worktree 隔离；这些场景直接拒绝启动。
- 远程 Shell、SSH、容器或云端执行；Shell 只在本机进程内运行。
- token / 费用预算、按模型计费与配额控制；只保留固定超时和调用次数上限。
- 规划会话跨进程恢复、手工编辑计划行；一期只支持 SSE 规划与自然语言修订。

## 4. 角色模型

| 角色 | 职责 | 可用工具 | 是否直接面向用户 |
| --- | --- | --- | --- |
| Orchestrator | 调度主 Agent / 子 Agent / 审核 Agent，执行工具与 Git 分支操作，发布事件，落库 | 不调用模型 | 否 |
| 主 Agent | 需求分析、代码库调查、生成计划、边界处动态重排、最终汇总、必要时发起抉择 | 一期仅 `fs.list` / `fs.find` / `fs.read` | 是（通过事件） |
| 子 Agent | 实现单个子任务、运行测试、修复问题 | 六类工具，受计划限制 | 是（通过事件） |
| 审核 Agent | 对照验收标准审查实现，独立执行测试，输出 PASS / REWORK | 只读文件工具 + 受限 `shell.exec`（测试/校验命令） | 是（审核结论） |
| 用户 | 确认计划、处理抉择、查看审核结论 | 页面操作 | 是 |

主 Agent 与子 Agent 都是独立模型会话；审核 Agent 是同一模型配置下的独立会话。
三者不共享隐式上下文，只通过结构化输入与输出衔接。

## 5. 运行流程

### 5.1 创建前规划会话（SSE）

规划阶段不再使用同步 REST 草案，而是独立的规划会话；确认前**不创建主任务**：

1. 用户填写目标并选择工作目录；后端立即校验目录存在、是 Git 仓库根目录且工作区干净。
2. 前端调用 `POST /api/main-tasks/plan-sessions`，返回 `planSessionId`、`status` 与
   `lastSeq`，规划 Agent 在后台启动。
3. 前端订阅 `GET /api/main-tasks/plan-sessions/{id}/events?afterSeq=N`。事件信封与运行
   事件一致，`runId = planSessionId`，`task` 使用合成根任务
   `{taskId:"plan_root", parentTaskId:null, title:"规划", agentName:"planner"}`。
4. 规划 Agent 只用只读工具调查代码库，产生 `prompt_assembled` / `task_thought` /
   `tool_call` / `tool_result` 事件；事件写入独立规划会话表，不写 `tn_event`。
5. 需求有歧义时发送 `waiting_input`，带问题、选项和自由输入框；用户通过
   `POST /api/main-tasks/plan-sessions/{id}/inputs` 提交，后端推送 `input_received`
   后继续规划。
6. 计划就绪时发送 `plan` 事件，计划行包含 `description`、`acceptanceCriteria` 与
   `allowedTools`；会话进入 `READY`，SSE 保持连接并继续心跳。
7. 用户可用自然语言修订：`POST /api/main-tasks/plan-sessions/{id}/revisions` 携带
   `instruction`，会话回到 `RUNNING`，再次产生事件与新的 `plan` 事件；历史修订保留。
8. 用户确认：`POST /api/main-tasks/plan-sessions/{id}/confirm`。后端重新校验 Git 前置
   条件，创建主任务与执行运行，保存最终计划，返回 `{mainTaskId, runId, workingDir}`；
   规划会话发送 `run_completed` 并关闭 SSE。
9. 用户取消：`POST /api/main-tasks/plan-sessions/{id}/cancel`，会话 `CANCELLED`，
   发送 `run_completed` 后关闭；不创建主任务。
10. 规划失败或超时：发送 `error(PLAN_AGENT_UNAVAILABLE / PLAN_AGENT_FAILED)`，
    会话 `FAILED`；已产生的规划事件仍可查询。

规划会话是 P3 唯一的创建入口；创建主任务必须来自已确认的规划会话。

### 5.2 启动与执行

1. Orchestrator 创建运行，先发送 `plan` 事件（内容为用户确认的计划）。
2. 再次校验 Git 前置条件，捕获原分支与起始提交，创建主任务临时分支；发送
   `git_checkpoint(branch_created)` 后再发送根任务 `task_started`。
3. 按依赖拓扑顺序执行直属子任务；一期只允许串行。
4. 每个子任务进入 5.3 的执行与 5.4 的审核流程；审核通过后合并回主任务分支。
5. 每个子任务成功合并后、下一个子任务开始前是**重排窗口**。主 Agent 可根据测试结果、
   代码调查或用户输入发布新的 `plan` 事件，重排未开始的任务。
6. 重排规则：
   - 只能新增任务、修改 `PENDING` 任务或取消 `PENDING` 任务；`RUNNING` /
     `WAITING_INPUT` / `REVIEWING` / `SUCCESS` / `FAILED` 任务不可修改。
   - 被取消的任务进入 `CANCELLED`；已完成任务及其 Git 提交保持不变。
   - 新计划仍须满足两层结构、任务 ID 唯一、依赖无环、验收标准完整；新任务不得依赖
     `CANCELLED` 任务。
   - 计划事件携带递增的 `revision` 与 `reason`；每次重排更新计划版本并追加修订记录。
   - 重排不额外要求用户确认；若主 Agent 不确定，先发 `waiting_input`。
7. 主 Agent 在协调或汇总阶段遇到歧义时，同样以根任务发送 `waiting_input`，由用户选择
   或输入，不自行假设。
8. 任一环节失败按第 7 节回滚：子任务失败删除子任务分支；主任务终态失败删除主任务
   分支，原分支保持不变。

### 5.3 子任务执行（串行）

1. Orchestrator 从主任务临时分支 HEAD 创建子任务分支
   `tasknest/subtasks/<mainTaskId>/<taskId>-a<attempt>`，发送 `git_checkpoint(branch_created)`，
   再发送该子任务 `task_started`（首次或重做）。
2. 子 Agent 组装提示词并发送 `prompt_assembled`。
3. 模型输出思考与工具调用；Orchestrator 校验 `allowedTools` 后执行并回传 `tool_result`；
   未授权工具返回 `TOOL_NOT_ALLOWED`，不执行。Git 写操作由 Orchestrator 独占，子 Agent
   通过 Shell 执行 Git 写命令会被 `COMMAND_BLOCKED` 拦截。
4. 模型继续迭代，直到给出完成摘要或触发失败。
5. 子 Agent 遇到无法自行决定的歧义、需要放行被拦截命令或确认破坏性操作时，发送
   `waiting_input`（选项 + 自由输入）暂停该子任务；用户提交后按 `input_received` 恢复。
6. 完成摘要后，Orchestrator 在子任务分支执行 `git add -A` 并提交 checkpoint；无文件
   变化时记录 `noChanges=true`，不创建空提交。
7. 如果开启审核，进入 5.4；否则把子任务分支合并回主任务分支、删除子任务分支，再发送
   `task_completed`，子任务 `SUCCESS`。
8. `task_failed(retryable=true)`：先删除当前子任务分支，子任务进入 `RETRYING`，下一次
   `task_started` 从主任务分支重建；不终止主任务，但重试次数计入总上限。
9. 任一子任务最终失败：删除该子任务分支，停止后续子任务，按第 7 节回滚主任务分支，
   发送 `error` 终止运行，主任务 `FAILED`。

### 5.4 审核阶段

1. 发送 `review_started`，子任务进入 `REVIEWING`。
2. 审核 Agent 读取原始需求、验收标准、变更文件与子 Agent 的工具记录。
3. 审核 Agent 在子任务分支上**独立执行测试**：优先使用计划行的 `testCommand`，
   未提供时使用项目自动探测的测试命令。命令、退出码、stdout/stderr 摘要写入审核证据。
4. 测试结束检查工作区：若出现新的已跟踪文件改动或未忽略的未跟踪文件，判定
   `REVIEW_SIDE_EFFECT`，本次审核失败并按 `REWORK` 处理，删除子任务分支。
5. 审核 Agent 输出结构化结论，发送 `review_completed`（含 `testEvidence`）。
6. `PASS`：把子任务分支合并回主任务临时分支，删除子任务分支，发送
   `git_checkpoint(merged)` / `git_checkpoint(branch_deleted)`，再发送 `task_completed`，
   子任务 `SUCCESS`，继续下一个子任务。
7. `REWORK`：删除本次子任务分支以丢弃该次尝试，发送
   `git_checkpoint(rolled_back)` / `git_checkpoint(branch_deleted)`，记录意见并进入
   `RETRYING`；下一次 `task_started` 从主任务分支重新建分支，提示词携带审核意见与
   测试失败输出。
8. 超过最大重做次数：删除子任务分支，发送 `task_failed(retryable=false)` 与
   `error(REVIEW_REJECTED)`，按第 7 节回滚主任务分支，运行终止。

### 5.5 最终汇总

1. 所有子任务成功后，主 Agent 汇总变更、测试结果与遗留风险。
2. Orchestrator 切回原分支，把主任务临时分支合并回原分支；合并成功后删除主任务
   分支并发送 `git_checkpoint(merged)` / `git_checkpoint(branch_deleted)`。
3. 只有合并成功后才发送根任务 `task_completed` 与 `run_completed`，主任务与运行进入
   `COMPLETED`。
4. 合并冲突时中止合并、保留主任务分支与提交现场，发送 `error(GIT_MERGE_CONFLICT)`，
   主任务 `FAILED`；不自动删除分支，避免丢失可恢复的工作。

## 6. 工具契约（规范性）

所有工具由后端 `ToolExecutor` 执行，模型只能通过事件契约中的 `tool_call` 请求调用。
工具名称使用小写点号格式，参数与结果均为 JSON。

### 6.1 通用规则

```json
// tool_call
{
  "callId": "call_01",
  "toolName": "fs.read",
  "arguments": { "path": "src/main.ts" }
}

// tool_result 成功
{
  "callId": "call_01",
  "toolName": "fs.read",
  "status": "success",
  "result": { "path": "src/main.ts", "content": "..." },
  "durationMs": 12
}

// tool_result 失败
{
  "callId": "call_01",
  "toolName": "fs.read",
  "status": "error",
  "result": {
    "error": { "code": "FILE_NOT_FOUND", "message": "文件不存在: src/main.ts" }
  },
  "durationMs": 3
}
```

- `callId` 由模型提供；缺失时 Orchestrator 生成，并按 `taskId + toolName` FIFO 配对
  `tool_result`。
- 参数必须是 JSON 对象；未知字段忽略并告警，必填字段缺失返回 `INVALID_ARGUMENT`。
- 所有路径参数可以是相对工作目录的路径，也可以是工作目录内的绝对路径；返回结果统一
  使用工作目录相对路径（工作目录本身为 `.`）。
- 单个工具结果建议不超过 64KB；超出必须标记 `truncated=true`，不得静默截断。

### 6.2 `fs.list`（目录查询）

参数：

```json
{ "path": ".", "includeHidden": false, "maxEntries": 200 }
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `path` | 否 | 默认 `.`；必须是目录。 |
| `includeHidden` | 否 | 默认 `false`；不返回点文件/点目录。 |
| `maxEntries` | 否 | 默认 200，最大 500。 |

结果：

```json
{
  "path": "src",
  "entries": [
    { "name": "main.ts", "path": "src/main.ts", "type": "file", "size": 1234 },
    { "name": "agent", "path": "src/agent", "type": "directory" }
  ],
  "truncated": false
}
```

只列一层，不递归；排序按目录优先、名称字典序。`type` 为 `file` / `directory` /
`other`。

### 6.3 `fs.find`（文件查询）

参数：

```json
{ "pattern": "**/*.ts", "path": ".", "maxResults": 200, "includeHidden": false }
```

- `pattern` 必填，支持 `*`、`**`、`?` 的 glob，不执行正则。
- 默认忽略 `.git`、`node_modules`、`target`、`dist`、`build`、`.venv`、`venv`。
- `maxResults` 默认 200，最大 500。

结果：

```json
{
  "pattern": "**/*.ts",
  "matches": [
    { "path": "src/main.ts", "type": "file", "size": 1234 }
  ],
  "truncated": false
}
```

### 6.4 `fs.read`（文件读取）

参数：

```json
{ "path": "src/main.ts", "startLine": 1, "endLine": 200 }
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `path` | 是 | 只能是普通文件。 |
| `startLine` | 否 | 从 1 开始，默认 1。 |
| `endLine` | 否 | 含端点；默认到文件末尾。 |

结果：

```json
{
  "path": "src/main.ts",
  "content": "...",
  "startLine": 1,
  "endLine": 120,
  "totalLines": 340,
  "size": 12345,
  "truncated": true
}
```

限制：

- 单次最多 2000 行 / 256KB，超出时截断并标记 `truncated=true`。
- 只读取 UTF-8 文本；含 NUL 或明显二进制内容返回 `BINARY_FILE`。
- 敏感文件默认拒绝读取，见 6.8。

### 6.5 `fs.create`（文件创建）

参数：

```json
{ "path": "src/agent/tool.ts", "content": "export {};\n", "createParents": true }
```

- 目标已存在时返回 `FILE_EXISTS`，不覆盖。
- `createParents` 默认 `true`，在沙箱内创建缺失的父目录。
- 单文件内容最大 1MB；写入采用临时文件 + 原子替换。

结果：

```json
{ "path": "src/agent/tool.ts", "bytesWritten": 13, "created": true }
```

### 6.6 `fs.write`（文件写入）

参数：

```json
{ "path": "src/main.ts", "content": "..." }
```

- 覆盖已有文件；文件不存在时创建（与 `fs.create` 的区别是不拒绝覆盖）。
- 单文件内容最大 1MB；写入采用临时文件 + 原子替换。
- 不提供局部 diff/patch 工具；子 Agent 必须写入完整内容。

结果：

```json
{
  "path": "src/main.ts",
  "bytesWritten": 2048,
  "created": false,
  "previousBytes": 1900
}
```

### 6.7 `shell.exec`（Shell 命令执行）

参数：

```json
{ "command": "npm test", "cwd": ".", "timeoutMs": 120000 }
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `command` | 是 | 非空命令字符串；由系统 Shell 执行。 |
| `cwd` | 否 | 默认工作目录；解析后必须仍在沙箱内。 |
| `timeoutMs` | 否 | 默认 120000，范围 1000–600000。 |

结果：

```json
{
  "command": "npm test",
  "cwd": ".",
  "exitCode": 0,
  "stdout": "...",
  "stderr": "",
  "timedOut": false,
  "truncated": false
}
```

规则：

- 进程成功启动并正常结束时，无论退出码是否为 0，均返回 `status=success`；`exitCode` /
  `stdout` / `stderr` 是模型判断命令是否成功的主要依据。
- 只有命令被拦截、超时或进程无法启动时返回 `status=error`。
- stdout、stderr 各最多 64KB；超限截断并标记 `truncated=true`。
- 返回前对当前 LLM 密钥、MySQL 密码等已知敏感值做脱敏。
- 超时后终止整个进程树，返回 `COMMAND_TIMEOUT`。
- 不分配交互式 TTY；子进程不得等待键盘输入。
- 子进程环境变量使用白名单，不继承 `TASKNEST_*`、`MYSQL_*`、`OPENAI_API_KEY`、
  `*_KEY`、`*_SECRET`、`*_TOKEN` 等敏感变量。
- 工作目录为沙箱根目录；`cwd` 不允许逃逸。
- Windows 使用 `cmd.exe /c`，Unix 使用 `/bin/sh -c`；平台信息注入 Agent 提示词。
- 只执行本机进程：不提供 SSH、远程 Shell、容器或云端执行入口。
- 不单独限制网络访问；命令是否联网由项目命令本身决定，P4 如需离线再增加网络策略。
- 审核 Agent 的 `shell.exec` 使用更窄的白名单：测试、lint、build、类型检查与只读 Git
  子命令；文件写工具仍不可用。计划行的 `testCommand` 也必须命中该白名单，否则按
  `UNAVAILABLE` 处理。
- 命令被策略拦截时不执行，返回 `COMMAND_BLOCKED` 与命中的规则说明。

### 6.8 路径沙箱

1. 主任务必须提供工作目录；后端保存 `toRealPath()` 后的绝对路径。
2. 相对路径基于工作目录解析；绝对路径必须位于工作目录内。
3. 已存在路径使用 `toRealPath()` 校验；新建路径校验最近的已存在父目录，再拼接目标名。
4. 拒绝 `..` 逃逸、符号链接逃逸、UNC/设备路径与跨盘符路径。
5. 默认拒绝敏感路径：
   - `.env`、`.env.*`（`.env.example` 除外）
   - `*.pem`、`*.key`、`id_rsa*`、`*.p12`
   - `.ssh/**`、`.aws/**`、`.git/**`
6. 越界或敏感路径返回 `PATH_OUT_OF_SANDBOX` / `SENSITIVE_PATH`，不得读取或写入任何内容。

### 6.9 危险命令策略

一期采用规则匹配 + 人工可读原因，属于防护栏，不是不可绕过的安全边界。默认至少拦截：

| 类别 | 示例 |
| --- | --- |
| 磁盘/系统破坏 | `format`、`diskpart`、`shutdown`、`reboot`、`bcdedit`、`reg delete` |
| 根目录/用户目录递归删除 | `rm -rf /`、`rm -rf ~`、`del /s /q C:\`、`Remove-Item -Recurse` 指向沙箱外 |
| 提权 | `sudo`、`runas`、`su` |
| 破坏工作区 | `git reset --hard`、`git clean -fd`、`git push --force` |
| 下载后执行 | `curl ... \| sh`、`wget ... \| sh`、`Invoke-Expression` 下载内容 |
| 数据库破坏 | `DROP DATABASE`、无 WHERE 的 `DELETE`/`TRUNCATE` |
| 进程炸弹 | `:(){ :|:& };:` 等 fork bomb 模式 |
| 敏感文件读取 | 命令文本中出现 `.env`、`*.pem`、`.ssh`、`.aws`、`.git/config` 等路径 |

- 命中后返回：

```json
{
  "error": {
    "code": "COMMAND_BLOCKED",
    "message": "命令命中危险规则：递归删除根目录",
    "rule": "rm-root"
  }
}
```

- 主 Agent 可改用安全命令，或通过 `waiting_input` 请求用户决策；系统不自动放行。
- 所有命令及其结果写入事件与档案，便于审计。
- 规则匹配是尽力而为：Shell 仍可能通过脚本、编码或绝对路径访问沙箱外文件。P3 一期
  依赖命令规则、工作目录约束与输出脱敏，不承诺 OS 级隔离；P4 再引入容器/WSL 沙箱。

## 7. Git 版本管理

Git 是执行层的版本边界，不是模型工具。所有分支、提交、合并和回滚由 Orchestrator
通过 `GitService` 执行；子 Agent 只能读取 Git 状态，不能改写分支或提交历史。

### 7.1 前置条件

启动规划会话和执行运行前都要校验，执行前再校验一次：

1. `git` 可执行，否则 `GIT_UNAVAILABLE`。
2. `workingDir` 存在、是目录，且等于 `git rev-parse --show-toplevel` 的真实路径，
   否则 `GIT_NOT_REPOSITORY` / `GIT_NOT_REPO_ROOT`。
3. 不能处于 detached HEAD，否则 `GIT_DETACHED_HEAD`。
4. `git status --porcelain` 为空，包含未跟踪文件；否则 `GIT_DIRTY_WORKTREE`。
5. 同一仓库根目录同时只允许一个活动运行；已被占用时返回 `WORKSPACE_BUSY`。
6. 运行期间要求用户不要手动切换分支或修改工作区；每个 checkpoint 校验当前分支，
   不符合预期时 `GIT_BRANCH_DRIFT` 并停止，不自动 reset。

### 7.2 分支模型

- 原分支 `originalBranch`：执行开始时所在的本地分支，必须是普通分支。
- 主任务临时分支：`tasknest/<mainTaskId>`，从原分支起始提交创建。
- 子任务临时分支：`tasknest/subtasks/<mainTaskId>/<taskId>-a<attempt>`，从当前主任务
  分支 HEAD 创建。必须使用与主任务分支同级的命名空间，不能写成
  `tasknest/<mainTaskId>/...`，因为 Git ref 不能同时是文件与目录。
- 分支名只使用 ASCII、短 ID 和连字符，避免平台差异。
- 所有 Git 命令使用 `git -C <workingDir>` 与显式 ref，不依赖当前目录的隐式状态。
- 内部提交使用 `-c user.name=TaskNest -c user.email=tasknest@local` 与
  `--no-verify`，不修改用户的 Git 配置，不执行用户钩子。
- **不执行任何远端操作**：不 fetch、不 pull、不 push、不创建 PR。

### 7.3 生命周期

1. 执行开始：校验前置条件，记录 `originalBranch` 与起始提交，创建主任务分支，
   发送 `git_checkpoint(branch_created)`。
2. 每个子任务尝试前：从主任务分支 HEAD 创建子任务分支，发送
   `git_checkpoint(branch_created)`。
3. 子 Agent 给出完成摘要后：在子任务分支执行 `git add -A` 与提交；无变化时记录
   `noChanges=true`，不创建空提交。
4. 审核通过：在子任务分支与主任务分支之间执行 `git merge --no-ff`，成功后用
   `git branch -d` 删除子任务分支，发送 `merged` 与 `branch_deleted`。
5. 审核退回或子任务失败：切回主任务分支，用 `git branch -D` 删除子任务分支，
   发送 `rolled_back` 与 `branch_deleted`；重做时重新建分支。
6. 全部子任务成功：切回原分支，执行 `git merge --no-ff <主任务分支>`；成功后用
   `git branch -d` 删除主任务分支，发送 `merged` 与 `branch_deleted`。
7. 主任务终态失败：切回原分支，用 `git branch -D` 删除主任务分支及残留子任务分支，
   发送 `rolled_back` / `branch_deleted`。原分支在成功合并前始终不变。
8. 合并冲突：执行 `git merge --abort`，**保留**主任务分支与提交现场，发送
   `error(GIT_MERGE_CONFLICT)`，主任务失败。冲突不属于自动回滚，避免丢失可恢复工作。
9. 删除分支失败：发送 `error(GIT_ROLLBACK_FAILED)`，停止后续操作，不静默继续。

### 7.4 回滚语义

| 场景 | 回滚范围 |
| --- | --- |
| 子任务退回重做 | 只删除该子任务分支；此前已通过的子任务保留在主任务分支。 |
| 子任务最终失败 | 删除该子任务分支，随后主任务失败，删除主任务分支，整次运行回滚。 |
| 主任务审核耗尽 | 删除当前子任务分支，主任务失败，删除主任务分支。 |
| 主任务系统/模型错误 | 删除主任务分支，整次运行回滚。 |
| 最终合并冲突 | 保留主任务分支与提交现场，不自动删除；用户手动处理。 |
| 原分支 | 成功合并前不修改；不做 `reset --hard`，不强制推送。 |

删除分支前把 commit SHA、分支名和动作写入 `git_checkpoint` 事件，作为事后审计线索。

### 7.5 `git_checkpoint` 事件

新增事件类型，payload：

```json
{
  "action": "branch_created",
  "branch": "tasknest/subtasks/run_123/t_impl-a1",
  "targetBranch": null,
  "commit": null,
  "attempt": 1,
  "noChanges": false,
  "message": "创建子任务分支"
}
```

- `action`：`branch_created` / `committed` / `merged` / `rolled_back` / `branch_deleted`。
- `targetBranch` 只在 `merged` 时填写，例如 `tasknest/run_123` 或原分支名。
- `commit` 在 `committed` / `merged` / 删除前填写短 SHA；其余可为 null。
- `task`：主任务分支操作引用根任务，子任务分支操作引用对应子任务。
- 事件在操作成功后发送；Git 操作失败发送 `error`，错误码见 7.6。
- 前端在子任务详情与账本中展示 Git 时间线；不新增独立 Git 表，事件原文已落
  `tn_event`。

### 7.6 Git 错误码

| 错误码 | 含义 |
| --- | --- |
| `GIT_UNAVAILABLE` | 本机找不到可执行的 git。 |
| `GIT_NOT_REPOSITORY` | 工作目录不是 Git 仓库。 |
| `GIT_NOT_REPO_ROOT` | 工作目录不是仓库根目录。 |
| `GIT_DETACHED_HEAD` | 当前处于 detached HEAD。 |
| `GIT_DIRTY_WORKTREE` | 存在未提交或未跟踪改动。 |
| `GIT_BRANCH_DRIFT` | 运行期间当前分支被外部切换。 |
| `GIT_BRANCH_CREATE_FAILED` | 临时分支创建失败。 |
| `GIT_COMMIT_FAILED` | checkpoint 提交失败。 |
| `GIT_MERGE_CONFLICT` | 合并冲突，已中止合并并保留分支。 |
| `GIT_ROLLBACK_FAILED` | 回滚或删除分支失败。 |
| `WORKSPACE_BUSY` | 同一仓库已有活动运行。 |

### 7.7 与 Shell 工具的边界

`shell.exec` 对 Git 使用只读子命令白名单：仅允许 `status`、`diff`、`log`、`show`、
`rev-parse`、`ls-files`。其余 Git 子命令（包括 `commit`、`checkout`、`switch`、
`branch`、`merge`、`rebase`、`reset`、`restore`、`clean`、`stash`、`add`、`config`、
`remote`、`fetch`、`pull`、`push`、`tag`、`worktree`、`cherry-pick`、`revert`）
一律由 `COMMAND_BLOCKED` 拦截，避免绕过 Orchestrator。

子 Agent 需要版本信息时读取状态即可；需要回滚或提交时由 Orchestrator 在任务边界执行。

## 8. 审核 Agent 契约

### 8.1 触发条件

- 全局设置 `reviewEnabled=true` 时，每个子任务实现结束后触发。
- `reviewEnabled=false` 时跳过审核，直接 `task_completed`。
- 主 Agent 的规划阶段与最终汇总不审核。

### 8.2 输入

审核 Agent 只接收结构化上下文，不继承子 Agent 的隐式会话：

- 原始需求与确认后的计划。
- 当前子任务标题、描述、验收标准、依赖与允许工具。
- 子 Agent 的工具调用记录、命令输出与完成摘要。
- 变更文件列表与文件内容（只读）；列表由子任务分支相对主任务分支的 Git diff 汇总，
  避免 Shell 修改的文件漏记。
- 子 Agent 的测试记录与失败信息，用作对照；审核 Agent 必须自己再执行一次测试。
- 计划行的 `testCommand`（可选）与项目测试命令探测结果。

审核 Agent 使用 `fs.list` / `fs.find` / `fs.read` 与受限 `shell.exec`，不使用文件写工具。
受限 Shell 只允许测试、lint、build、类型检查与只读 Git 子命令；命令仍需经过危险命令
策略与工作目录沙箱。审核测试在已提交的子任务分支上执行，默认超时 10 分钟，不得使用
watch / 交互模式，测试后校验工作区无源码副作用。

### 8.3 输出

审核 Agent 必须输出严格 JSON：

```json
{
  "verdict": "PASS",
  "summary": "实现满足验收标准，测试覆盖主要分支。",
  "issues": [
    {
      "severity": "MINOR",
      "file": "src/main.ts",
      "line": 42,
      "message": "错误分支缺少日志。",
      "suggestion": "补充失败日志。"
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
  ]
}
```

- `verdict`：`PASS` / `REWORK`。
- `severity`：`BLOCKER` / `MAJOR` / `MINOR`。
- 存在 `BLOCKER` 或 `MAJOR` 时必须为 `REWORK`；只有 `MINOR` 时可以 `PASS`。
- `testStatus`：`PASSED` / `FAILED` / `UNAVAILABLE`。
- `testStatus=FAILED` 时必须 `REWORK`，且至少有一条 `BLOCKER` 引用失败命令。
- `testStatus=UNAVAILABLE` 且存在源码变更时必须 `REWORK`；纯文档/配置变更可以静态审核。
- 测试命令被策略拦截时按 `UNAVAILABLE` 处理并写明原因；测试超时按 `FAILED` 处理。
  两种情况都不得伪造通过。
- JSON 解析失败时重试一次；仍失败按 `REVIEW_INVALID_OUTPUT` 终止运行，不默认放行。

### 8.4 重做

- 全局设置 `reviewMaxReworks`，默认 2，范围 0–5；表示最多退回重做次数。
- 每次重做把上一轮 `issues` 与 `summary` 加入子 Agent 提示词。
- 达到上限仍为 `REWORK`：子任务 `FAILED`，主任务 `FAILED`，发送
  `error(REVIEW_REJECTED)`，停止后续子任务。
- 审核 Agent 自身调用失败（模型/网络）重试一次；仍失败发送
  `error(REVIEW_UNAVAILABLE)`，不得静默通过。

### 8.5 审核记录

每次审核写入 `tn_task_review`，保留 attempt、verdict、summary、issues、`test_status`、
`test_evidence`（命令、退出码、输出摘要）、模型名与时间；事件账本同时保留
`review_started` / `review_completed` 原文。

## 9. 配置契约

### 9.1 Agent 设置

```
GET /api/settings/agent
PUT /api/settings/agent
```

响应：

```json
{
  "reviewEnabled": true,
  "reviewMaxReworks": 2,
  "updatedAt": "2026-10-21T12:00:00.000Z"
}
```

请求：

```json
{ "reviewEnabled": true, "reviewMaxReworks": 2 }
```

- `reviewEnabled` 必填 boolean。
- `reviewMaxReworks` 必填整数，0–5。
- 保存到单例表 `tn_agent_setting`（`id=1`），启动自动加载，保存后立即替换运行时快照。
- 与 LLM 设置相同，采用独立表，避免给已有表补列。

### 9.2 工作目录与 Git 前置条件

- 规划会话与 `POST /api/main-tasks` 的 `workingDir` 在 P3 均为**必填**。
- 必须是 Git 仓库根目录且工作区干净；规划会话创建、确认和执行启动三处都校验。
- 历史档案允许 `workingDir=null`，但只读且不能重新执行。
- 前端未选择目录、目录不是仓库根目录或工作区不干净时禁止提交，并显示具体原因。
- 校验失败返回 `GIT_NOT_REPOSITORY` / `GIT_NOT_REPO_ROOT` / `GIT_DIRTY_WORKTREE` /
  `GIT_DETACHED_HEAD` 等错误码，不创建规划会话或主任务。

### 9.3 规划会话 API

```text
POST   /api/main-tasks/plan-sessions
GET    /api/main-tasks/plan-sessions/{planSessionId}
GET    /api/main-tasks/plan-sessions/{planSessionId}/events?afterSeq=N
POST   /api/main-tasks/plan-sessions/{planSessionId}/inputs
POST   /api/main-tasks/plan-sessions/{planSessionId}/revisions
POST   /api/main-tasks/plan-sessions/{planSessionId}/confirm
POST   /api/main-tasks/plan-sessions/{planSessionId}/cancel
```

创建请求：

```json
{
  "goal": "为任务详情增加审核面板",
  "workingDir": "E:\\workspace\\demo"
}
```

- `goal`、`workingDir` 必填；工作目录必须是已存在的本地 Git 仓库。
- 返回 `{planSessionId, status: "RUNNING", lastSeq: 0}`；不创建主任务。
- 快照返回 `{planSessionId, status, goal, workingDir, plan, lastSeq, error}`。
- 状态：`RUNNING` / `WAITING_INPUT` / `READY` / `CONFIRMED` / `CANCELLED` / `FAILED`。
- 只有 `READY` 可修订或确认；`WAITING_INPUT` 只接受 `inputs`；`RUNNING` 拒绝其他操作，
  返回 `409 PLAN_SESSION_BUSY`。
- SSE 事件信封与运行事件一致，`runId = planSessionId`；`seq` 从 1 递增，心跳占号，
  历史补发与重连语义与 `/api/runs/{runId}/events` 相同。
- 规划期间的合成根任务固定为 `plan_root`，最终计划的根任务继续使用该 ID；主任务 ID
  在确认时另行生成，二者不复用。
- 规划事件持久化到 `tn_plan_session_event`，不写 `tn_event`；确认后主任务运行事件
  才写 `tn_event`。
- 规划会话复用现有事件类型，但语义是会话级：`plan` 表示草案就绪，`run_completed`
  表示确认或取消后关闭，`error` 表示规划失败；心跳同样占 `seq`。
- `inputs` 请求：

```json
{ "taskId": "plan_root", "selectedOptionId": "option-a", "customInput": "补充说明" }
```

- `revisions` 请求：`{ "instruction": "把测试任务拆成前后端两步" }`，会话回到
  `RUNNING`，重新产生规划事件与新的 `plan` 事件。
- `confirm` 重新校验计划结构与 Git 前置条件，创建主任务、保存计划并启动执行运行；
  返回 `{mainTaskId, runId, workingDir, planVersion}`。确认后规划会话发送
  `run_completed` 并关闭 SSE。
- `cancel` 将会话置为 `CANCELLED`，发送 `run_completed` 后关闭；不创建主任务。
- 规划失败或超时发送 `error`（`PLAN_AGENT_UNAVAILABLE` / `PLAN_AGENT_FAILED`），
  会话 `FAILED`，已产生的事件仍可查询。
- 创建主任务只能走 `confirm`；前端不直接调用任何创建接口。

### 9.4 LLM 配置

- 主 Agent、子 Agent、审核 Agent 共用 `GET/PUT /api/settings/llm` 中的 Base URL、
  模型名称与 API Key。
- 一期不增加独立审核模型配置。

## 10. 事件与状态契约变更

### 10.1 新增事件类型

在现有 14 种事件基础上新增 3 种，共 17 种：

| 类型 | payload | 说明 |
| --- | --- | --- |
| `review_started` | `{ "attempt": 1 }` | 审核开始；`attempt` 从 1 开始。 |
| `review_completed` | `{ "attempt": 1, "verdict": "PASS", "summary": "...", "issues": [], "testStatus": "PASSED", "testEvidence": [], "modelName": "gpt-4o", "durationMs": 1200 }` | 审核结束；可能多次出现，`testEvidence` 记录独立测试命令与结果。 |
| `git_checkpoint` | `{ "action": "branch_created", "branch": "...", "targetBranch": null, "commit": null, "attempt": 1, "noChanges": false, "message": "..." }` | Git 分支/提交/合并/回滚审计；动作见 7.5。 |

`issues[]` 元素：

```json
{
  "severity": "MAJOR",
  "file": "src/main.ts",
  "line": 42,
  "message": "未处理空输入。",
  "suggestion": "增加空值分支。"
}
```

`file` / `line` / `suggestion` 可为 null。

### 10.2 计划 payload 扩展

`plan.tasks[]` 增加以下可选字段：

```json
{
  "taskId": "t_impl",
  "parentTaskId": "t_root",
  "title": "实现目录查询工具",
  "agentName": "implementer",
  "dependsOn": [],
  "description": "实现 fs.list，包含沙箱校验与单元测试。",
  "acceptanceCriteria": [
    "只返回工作目录内的条目",
    "隐藏目录默认不返回",
    "单元测试覆盖路径逃逸"
  ],
  "allowedTools": ["fs.list", "fs.read", "fs.create", "fs.write", "shell.exec"],
  "testCommand": "npm test"
}
```

- `description`、`acceptanceCriteria` 由规划 Agent 生成，确认前必填。
- `allowedTools` 可选；缺省为六类工具全集，审核 Agent 始终只有只读文件工具与受限
  测试 Shell。
- `testCommand` 可选；审核 Agent 优先执行它，缺省时按项目类型自动探测。纯文档任务可
  显式设为 `"none"`。
- `dependsOn` 在一期参与串行调度：被依赖任务成功后才开始。
- 规划会话中 `plan` 可多次出现，每次代表最新草案；执行运行中首次 `plan` 是确认的
  最终计划，之后可在重排窗口再次出现。
- 执行阶段的 `plan` payload 增加可选字段：

```json
{
  "tasks": [ /* ... */ ],
  "revision": 2,
  "reason": "测试发现需要补充数据库迁移",
  "changedTaskIds": ["t_migration"]
}
```

- `revision` 从 1 递增；`reason` 用于前端提示与账本；`changedTaskIds` 可选。
- 重排只允许修改 `PENDING` 任务；被移除的 `PENDING` 任务状态置为 `CANCELLED`。

### 10.3 子任务状态扩展

`SubtaskStatus` 新增 `REVIEWING`（中文“审核中”）与 `CANCELLED`（中文“已取消”）：

| 当前状态 | 事件 | 新状态 |
| --- | --- | --- |
| `PENDING` | 新的 `plan` 移除了该任务 | `CANCELLED` |
| `PENDING` | `task_started` | `RUNNING` |
| `RUNNING` | `review_started` | `REVIEWING` |
| `REVIEWING` | `review_completed(PASS)` | `REVIEWING`（保持，随后必须发送 `task_completed`） |
| `REVIEWING` | `task_completed` | `SUCCESS` |
| `REVIEWING` | `review_completed(REWORK)` | `RETRYING` |
| `REVIEWING` | `review_completed(REWORK)` 且达到上限 | `REVIEWING`（随后发送 `task_failed`） |
| `REVIEWING` | `task_failed` / `error` | `FAILED` |
| `REVIEWING` | `run_completed` 但未完成 | `FAILED`（现有修复规则） |

`RETRYING` 后的下一次 `task_started` 继续沿用现有迁移到 `RUNNING`。
`CANCELLED` 是终态，不能被后续重排复活；需要重新执行时由主 Agent 创建新任务 ID。
审核关闭时不出现 `REVIEWING`，`task_completed` 直接置 `SUCCESS`。
`review_completed(PASS)` 只表示审核结论，任务完成仍由 `task_completed` 表达，保持现有
完成事件、通知与持久化语义不变。

### 10.4 工具错误码

工具结果中的 `error.code` 至少包括：

| 错误码 | 含义 |
| --- | --- |
| `INVALID_ARGUMENT` | 参数缺失、类型错误或越界。 |
| `PATH_OUT_OF_SANDBOX` | 路径解析后不在工作目录内。 |
| `SENSITIVE_PATH` | 命中敏感文件拒绝列表。 |
| `FILE_NOT_FOUND` | 文件或目录不存在。 |
| `FILE_EXISTS` | `fs.create` 目标已存在。 |
| `NOT_A_DIRECTORY` / `NOT_A_FILE` | 类型不符。 |
| `FILE_TOO_LARGE` | 超过读取/写入大小限制。 |
| `BINARY_FILE` | 非 UTF-8 文本或二进制文件。 |
| `TOOL_NOT_ALLOWED` | 计划行 `allowedTools` 未授权该工具。 |
| `COMMAND_BLOCKED` | 命中危险命令规则。 |
| `COMMAND_TIMEOUT` | 命令超时。 |
| `EXEC_FAILED` | 进程启动失败。 |
| `TOOL_INTERNAL_ERROR` | 工具内部异常。 |

审核与计划类系统错误：

| 错误码 | 含义 |
| --- | --- |
| `REVIEW_INVALID_OUTPUT` | 审核输出无法解析，重试后仍失败。 |
| `REVIEW_UNAVAILABLE` | 审核模型/网络不可用。 |
| `REVIEW_REJECTED` | 重做次数耗尽。 |
| `REVIEW_SIDE_EFFECT` | 审核测试修改了工作区源码。 |
| `PLAN_REVISION_INVALID` | 动态重排违反两层结构、依赖或状态约束。 |

### 10.5 `waiting_input` / `input_received` 扩展

主 Agent、规划 Agent 与子 Agent 共用同一套提问契约。`waiting_input` payload 扩展为：

```json
{
  "question": "登录失败时应该返回哪种错误提示？",
  "options": [
    { "id": "generic", "label": "通用提示", "description": "不暴露账号是否存在。" },
    { "id": "specific", "label": "具体提示", "description": "分别提示账号或密码错误。" }
  ],
  "allowCustom": true,
  "customPlaceholder": "也可以直接描述你想要的行为"
}
```

- `options` 允许为空数组，最多 8 个；为空时 `allowCustom` 必须为 `true`。
- Agent 可发送 0–8 个选项。
- `allowCustom` 缺省 `false`，兼容旧事件；P3 Agent 默认使用 `true`。
- 单选，不提供多选；选项 `id` 在单个事件内唯一。
- `question` 最长 2000 字，`label` 最长 200 字，`description` 最长 500 字。
- 自由输入最长 4000 字，首尾空白去除后不能为空。

`input_received` payload 扩展为：

```json
{
  "selectedOptionId": "generic",
  "customInput": "同时记录一次审计日志"
}
```

- `selectedOptionId` 与 `customInput` 至少一个非空；可同时提交。
- 旧事件只有 `selectedOptionId` 时保持兼容；前端解析把缺失的 `customInput` 视为 null。
- `selectedOptionId` 必须命中当前 `waiting_input.options[].id`。
- `allowCustom=false` 时提交 `customInput` 返回 `INVALID_INPUT`。
- 提交接口：运行使用 `POST /api/runs/{runId}/inputs`，规划使用
  `POST /api/main-tasks/plan-sessions/{id}/inputs`，请求体均为
  `{taskId, selectedOptionId?, customInput?}`。
- 后端把选中项文案与自由输入一起写入下一轮提示词；事件账本保留原始输入。
- 前端抉择面板显示选项与输入框；无选项时只显示输入框；两者都为空时禁用提交。
- 规划会话与执行运行复用同一套前端抉择组件、通知与 `eventId` 去重逻辑。

## 11. 后端架构

```text
orchestrator/
  CodingTaskOrchestrator        实现 TaskOrchestrator，只负责执行
  PlanningAgent                 规划会话中的模型循环，只使用只读工具
  AgentLoop                     单次模型会话循环：提示词 → 工具调用 → 结果 → 再调用
  MainAgent / SubAgent / ReviewAgent
  PlanRevisionService           执行期计划重排校验、版本递增与任务取消
  GitService                    分支创建、提交、合并、删除与状态校验
  ReviewTestRunner              审核 Agent 独立执行测试并收集证据
  ToolRegistry                  工具注册、参数校验、结果序列化
  ToolExecutor                  路径沙箱、命令策略、超时与输出限制
  CommandPolicy                 危险命令规则（含 Git 写操作拦截）
  ReviewCommandPolicy           审核测试命令白名单
  WorkspaceSandbox              工作目录解析与路径校验
service/
  PlanningSessionService        规划会话生命周期、问题提交、修订、确认
  PlanSessionStore              规划会话内存状态与 afterSeq 补发
  AgentSettingsService          Agent 设置启动加载与热更新
  WorkspaceLockService          按仓库根目录加锁，避免并发运行
sse/
  PlanSessionSseService         规划会话 SSE、心跳与历史补发
persistence/
  tn_plan_session               规划会话（目标、工作目录、状态、最后 seq）
  tn_plan_session_event         规划事件原文（每会话 seq 唯一）
  tn_agent_setting              审核开关与最大重做次数（单例）
  tn_task_review                审核记录
```

- 执行阶段继续使用单一 `TaskOrchestrator` Bean；规划会话是独立于主任务的轻量运行，
  确认后才创建主任务和执行运行。
- 内部 `RunRequest` 增加 `workingDir`、`originalBranch` 与 `mainTaskBranch`，由
  `RunService` 传入；工作目录仍以主任务档案为准。
- `CodingTaskOrchestrator` 使用 Spring AI `ChatClient`（P3 允许新增依赖）；工具调用通过
  自定义 `ToolCallback` 进入 `ToolExecutor`，不直接使用不受控的本地工具。
- 规划会话复用同一模型配置与只读工具，但使用独立的 `PlanningAgent`、事件存储与 SSE
  服务；不创建 `RunState` 或主任务投影。
- 模型必须支持 OpenAI 兼容的工具调用；不支持时以 `LLM_TOOL_CALLING_UNSUPPORTED` 失败。
- 主 Agent、子 Agent、审核 Agent 共用 `LlmSettingsService` 的当前配置。
- 子 Agent 循环上限：默认 30 次工具调用 / 15 分钟；运行总上限 60 分钟。规划会话单轮
  模型调用超时 120 秒，整体 10 分钟；超限发送 `error` 终止。
- 模型/网络临时错误重试 1–2 次；工具错误不重试，原样回传模型。
- 每个模型调用前发送 `prompt_assembled`；流式文本映射为 `task_thought.delta`。
- 执行事件先进入内存、更新投影、落库，再进入 SSE 队列；规划事件写入规划会话表并进入
  规划 SSE 队列，两者顺序语义一致。
- `PlanningSessionService.confirm` 在创建主任务前重新校验计划和 Git 状态；确认失败时
  会话保持 `READY`，不产生半成品主任务。
- `GitService` 所有操作使用显式 refs；执行前记录原分支，运行结束后切回。同一仓库根目录
  由 `WorkspaceLockService` 加锁。
- 用户的自由输入作为结构化 `input_received` 写入事件，并拼接进下一轮提示词；不直接作为
  系统指令执行。
- 审核 Agent 通过 `ReviewTestRunner` 在已提交的子任务分支上执行测试；测试命令优先取
  计划行 `testCommand`，否则自动探测。测试结束校验工作区，出现源码副作用则
  `REVIEW_SIDE_EFFECT` 并按 `REWORK` 处理。
- `PlanRevisionService` 只允许修改 `PENDING` 任务；校验两层结构、ID 唯一、依赖无环，
  将被移除的 `PENDING` 任务置为 `CANCELLED`，并递增计划 revision。
- 一期不实现 token / 费用预算；只保留已有的调用次数、单次超时与运行总超时。

## 12. 前端

- 设置入口改为带页签的“设置”：LLM 连接、执行与审核。
  - 执行与审核页：`reviewEnabled` 开关、`reviewMaxReworks` 数字输入（0–5）。
- 创建主任务：工作目录改为必填，未选择、非 Git 仓库根目录或工作区不干净时禁用提交，
  并显示后端返回的具体原因。
- 创建流程改为规划工作区：
  - 提交后进入规划会话，订阅规划 SSE，展示思考、工具调用与时间线。
  - 计划就绪后展示 Markdown 计划表与“确认并执行 / 继续修订”操作。
  - 歧义通过抉择面板弹出：选项单选 + 自由输入框；无选项时只显示输入框。
  - 规划会话取消或失败后可返回创建面板，不产生主任务目录条目。
- 执行阶段的主任务与子任务共用同一抉择面板；等待抉择的通知可点击定位到对应任务。
- 子任务状态新增“审核中”和“已取消”；审核通过后显示通过标记。
- 计划表展示 revision；执行期重排时提示“计划已更新”，并标出新增、修改与取消的任务。
- 子任务详情新增审核区：
  - 当前/历史审核轮次、verdict、summary。
  - issues 按严重级别分组，显示文件与行号。
  - 独立测试的命令、退出码、耗时与输出摘要；失败时可直接展开完整截断输出。
  - 重做次数与剩余次数。
- 子任务详情与账本新增 Git 时间线：分支创建、commit、合并、回滚、删除；显示分支名、
  短 SHA 与目标分支。
- 工具区展示文件创建/写入结果与 Shell stdout/stderr；超长输出折叠。
- 通知：
  - 规划或执行中的 `waiting_input` → 粘性抉择通知，点击打开对应面板。
  - 执行期计划重排 → 信息通知，点击定位计划表。
  - 审核退回 → 警告通知（可定位子任务）。
  - 审核次数耗尽 → 粘性失败通知。
  - Git 合并冲突或回滚失败 → 粘性错误通知。
  - 审核通过不单独弹通知，避免噪音。
- 事件账本支持 `review_started` / `review_completed` / `git_checkpoint` 过滤。
- 只读档案重建审核与 Git 时间线，不建立 SSE。

## 13. 持久化变更

| 表 | 内容 |
| --- | --- |
| `tn_plan_session` | 规划会话：目标、工作目录、状态、最后 seq、错误、创建 / 更新时间。 |
| `tn_plan_session_event` | 规划事件原文 JSON、seq、event_id、类型；每会话 `(seq)` 唯一。 |
| `tn_agent_setting` | 单例：`review_enabled`、`review_max_reworks`、时间。 |
| `tn_task_review` | 每次审核：主任务、子任务、attempt、verdict、summary、issues JSON、test_status、test_evidence JSON、模型名、时间。 |

- 规划会话不写 `tn_main_task` / `tn_event`；确认后才创建主任务并保存最终计划到
  `tn_main_task_plan`。
- 规划事件与执行事件都保留原文，分别存 `tn_plan_session_event` 与 `tn_event`。
- 工具调用与结果继续写 `tn_task_artifact`（`TOOL_CALL` / `TOOL_RESULT`）。
- `git_checkpoint` 作为执行事件写入 `tn_event`，不新增 Git 表；commit SHA 与分支名可从
  事件原文恢复。
- 计划行扩展字段写入 `tn_main_task_plan.plan_rows` 的 JSON，不新增列。
- 审核事件原文继续写 `tn_event`。
- 新表继续使用 `CREATE TABLE IF NOT EXISTS`，不改已有表结构。

## 14. 安全与审计

1. 模型提示词中不得包含 API Key、MySQL 密码或其他环境变量值。
2. Shell 子进程使用白名单环境变量；工具输出写入日志/数据库前对已知密钥值做脱敏。
3. 所有文件路径经过真实路径校验；符号链接逃逸按越界处理。
4. 所有工具调用与审核结论保留完整事件原文，支持事后审计。
5. 危险命令策略是防护栏，不是安全边界；P4 可增加 Docker/WSL 隔离。
6. 审核 Agent 无文件写工具；其 Shell 仅允许测试、lint、build、类型检查与只读 Git
   子命令，测试后校验工作区无源码副作用。
7. 单个文件读写上限 1MB，命令输出上限 64KB，防止上下文与存储被撑爆。
8. Git 只操作本地仓库：不 fetch / pull / push，不修改用户 Git 配置，不执行用户钩子；
   原分支在成功合并前保持不变。
9. 子 Agent 的 Git 写命令一律拦截；分支创建、提交、合并、删除只能由 `GitService`
   执行，并写入 `git_checkpoint` 事件。
10. 同一仓库根目录只允许一个活动运行；规划会话与执行运行都校验仓库根目录与工作区干净，
   规划确认时再次校验，避免规划期间仓库被改动。
11. 自由输入最长 4000 字，作为用户决策内容注入下一轮提示词，不提升为系统指令；提交时
   校验选项 ID 与 `allowCustom`，不合法输入不改变运行状态。
12. 规划会话与执行运行的事件都按 `seq` 持久化；重连使用 `afterSeq`，不信任前端游标。
13. Shell 只启动本机进程，不提供 SSH/远程/容器入口；网络访问不单独拦截，需在提示词与
    UI 中明确告知用户。
14. 动态重排必须经过 `PlanRevisionService` 校验；已开始或已完成的任务不可修改，违规
   重排返回 `PLAN_REVISION_INVALID` 并保持原计划。
15. 一期不实现 token / 费用预算；固定超时与调用次数是唯一成本边界。

## 15. 验收标准

### 15.1 工具

- 目录查询只返回沙箱目录内容；隐藏目录默认不返回。
- 文件查询支持 glob，忽略依赖与构建目录，结果有上限。
- 文件读取支持行范围、256KB/2000 行截断、二进制拒绝。
- 文件创建拒绝覆盖；文件写入可覆盖并可创建；两者原子落盘。
- 路径逃逸、符号链接逃逸、敏感路径均被拒绝且无副作用。
- Shell 正常返回退出码与输出；超时杀进程树；输出超限截断。
- 危险命令不执行并返回 `COMMAND_BLOCKED`。
- 计划未授权的工具返回 `TOOL_NOT_ALLOWED`，不产生文件或进程副作用。

### 15.2 审核

- 审核关闭时子任务直接成功，不产生审核事件。
- 审核通过时状态经历 `RUNNING → REVIEWING → SUCCESS`，且 `SUCCESS` 前已完成子分支合并。
- 审核 Agent 在子任务分支上独立执行测试，`review_completed` 包含真实命令、退出码与
  输出摘要；测试失败必须 `REWORK`。
- 测试修改源码时返回 `REVIEW_SIDE_EFFECT` 并按 `REWORK` 处理，不进入合并。
- 审核退回时状态经历 `REVIEWING → RETRYING → RUNNING`，子分支已删除，下一轮提示词包含
  上轮意见与测试失败输出。
- 达到最大重做次数后子任务与主任务失败，后续子任务不再执行。
- 审核输出非法时不会默认通过。

### 15.3 编排、规划与交互

- 主 Agent 生成的计划满足两层结构、依赖无环、验收标准完整。
- 规划会话通过 SSE 推送思考、工具调用、问题与计划；确认前不创建主任务、不写
  `tn_event`。
- 规划与执行中的 `waiting_input` 都能展示选项和自由输入框；只选项、只输入、两者都填
  三种提交都可用，非法提交不恢复运行。
- 规划修订会重新产生事件并更新计划，历史修订不丢失。
- 确认规划后创建主任务并启动执行；取消或失败不产生主任务目录条目。
- 子任务严格按依赖串行执行。
- 主 Agent 可在子任务边界重排未开始任务；`plan` 事件 revision 递增，已开始/已完成任务
  不可修改，被移除的 `PENDING` 任务变为 `CANCELLED`。
- 非法重排（改已完成任务、依赖成环、任务 ID 重复）被拒绝并保持原计划。
- 工作目录缺失、非仓库根目录或工作区不干净时，规划会话与创建请求均被拒绝。
- Agent 设置保存后立即生效，重启后自动加载。
- 全程密钥不出现在提示词、事件、日志与工具输出中。

### 15.4 Git 版本管理

- 非 Git 仓库、非仓库根目录、detached HEAD、脏工作区均拒绝启动。
- 主任务分支从原分支起始提交创建；每个子任务从主任务分支创建独立分支。
- 子任务审核通过后合并回主任务分支并删除子任务分支；退回/失败删除子任务分支，
  此前成功子任务的提交不受影响。
- 全部成功后才把主任务分支合并回原分支；整体失败删除主任务分支，原分支保持不变。
- 运行期间不执行任何远端 Git 操作。
- 合并冲突时保留主任务分支与提交现场，任务失败，不自动删除可恢复工作。
- 子 Agent 的 Git 写命令被拦截；`git_checkpoint` 事件可按任务与动作查询。

## 16. 实施阶段

| 阶段 | 内容 | 前置条件 |
| --- | --- | --- |
| P3.0 | 确认本设计，更新 `CLAUDE.md` 阶段边界与契约文档 | 用户批准 |
| P3.1 | Agent 设置表/API、Git 前置校验与 `GitService`、仓库锁、`waiting_input` 自由输入契约 | P3.0 |
| P3.2 | 规划会话 SSE、`PlanningAgent`、问题提交、修订与确认 | P3.1 |
| P3.3 | `CodingTaskOrchestrator`、工具执行器与沙箱、子 Agent 循环、Git 分支生命周期、动态重排 | P3.2 |
| P3.4 | 审核 Agent、独立测试执行、合并/回滚联动、新事件与状态、审核与规划持久化 | P3.3 |
| P3.5 | 前端规划工作区、统一抉择面板、Git 时间线、审核面板与设置页 | P3.4 |
| P3.6 | 安全加固、端到端验收、档案回放 | P3.5 |

每个阶段仍遵循契约先行：先更新 `docs/events.md`、`docs/requirements.md`、后端 record /
DTO 与前端 TypeScript 类型，再写实现。

## 17. 已确认的简化默认与后续增强

一期按以下简单默认执行：

1. 审核 Agent 独立执行测试；测试失败必须 `REWORK`；没有可用测试且存在源码变更时也
   `REWORK`，纯文档/配置变更允许静态审核。
2. 主 Agent 可在子任务边界动态重排未开始任务，不额外要求用户确认；非法重排直接拒绝。
3. Shell 只执行本机进程，不提供 SSH、远程 Shell 或容器执行；网络访问不单独限制。
4. 不实现 token / 费用预算；固定超时与调用次数是唯一成本边界。
5. 规划会话不做进程重启恢复；已产生的事件可查询，但不能继续规划。
6. 不支持手工编辑计划行；只支持自然语言修订，避免破坏两层结构。

后续增强（不在 P3 一期）：

- 离线/网络白名单与更细的 Shell 隔离。
- 审核测试命令的用户配置、缓存与并行执行。
- 动态重排的人工确认开关与计划 diff 视图。
- token / 费用预算与模型配额。
- 规划会话跨进程恢复与计划行手工编辑。
