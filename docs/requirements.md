# TaskNest 详细需求文档（P0–P2 展示层 + MySQL 持久化）

> 文档性质：需求基线（SRS），用于指导 `backend/`、`frontend/` 与 `docs/events.md` 的实现和验收。
>
> 来源：`todo.md`。本文对 `todo.md` 进行展开与补全；两者冲突时，以 `todo.md` 的“阶段范围锁”为准，并回改本文。
>
> 本文中的“必须 / 应当 / 可以 / 禁止”为规范性用语：
> - **必须（MUST）**：本阶段验收必需，缺失即视为未完成。
> - **应当（SHOULD）**：默认实现，除非有明确理由并在评审中说明。
> - **可以（MAY）**：可选增强，不影响验收。
> - **禁止（MUST NOT）**：本阶段不得实现或引入。

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.2 |
| 日期 | 2026-10-21 |
| 依据 | `todo.md` v1.0 + MySQL 持久化 + 计划表工作台交互 |
| 状态 | 已实现 |
| 适用阶段 | P0–P2（展示层 + MySQL 持久化） |
| 读者 | 前端、后端、测试、评审人 |
| 契约同步文件 | `docs/events.md`（实现时创建，与本文第 6 章一致） |

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [用户与使用场景](#2-用户与使用场景)
3. [阶段范围与边界](#3-阶段范围与边界)
4. [总体设计](#4-总体设计)
5. [功能需求总表](#5-功能需求总表)
6. [事件契约（核心）](#6-事件契约核心)
7. [REST / SSE 接口规格](#7-rest--sse-接口规格)
8. [Mock 编排器与脚本](#8-mock-编排器与脚本)
9. [前端工作台需求](#9-前端工作台需求)
10. [数据模型](#10-数据模型)
11. [非功能需求](#11-非功能需求)
12. [测试与验收](#12-测试与验收)
13. [交付物与完成定义](#13-交付物与完成定义)
14. [设计决策、风险与待确认问题](#14-设计决策风险与待确认问题)
15. [附录](#15-附录)

---

## 1. 背景与目标

### 1.1 产品定位

TaskNest（任务巢）是一个**本地运行的 Agent 任务工作台**。它把一次 Agent 运行可视化为任务树，并实时展示：

- 主任务到子任务的拆解关系与执行状态；
- Agent 的思考过程、拼接后的提示词、工具调用与返回；
- 运行过程中需要用户裁决的岔路，用户通过鼠标点选完成选择；
- 完整的事件流，便于调试与复盘。

### 1.2 本阶段目标（P0–P2 + 持久化）

本阶段做“展示层 + 事件协议 + MySQL 档案”的打磨：

1. 用代码定义一套稳定的 SSE 事件契约；
2. 提供一个可编程的 Mock 编排器，按脚本推送事件；
3. 前端仅依赖事件流与快照，还原两层任务结构、详情与抉择交互；
4. 用 4 个刻意“不完美”的 Mock 脚本验证协议与前端健壮性；
5. 用 MyBatis-Plus 把主任务、计划表、直属子任务、提示词、思考与工具调用、事件原文写入 MySQL；
6. 支持创建多个主任务，并通过左侧主任务目录查询任一主任务的历史详情；
7. 创建主任务前强制生成 Markdown 计划表，允许用提示词修订，确认后随主任务持久化。

**本阶段结束时应达到的状态：** 把 `MockTaskOrchestrator` 替换为真实的 Spring AI 编排器时，
SSE 层、事件契约、持久化映射与前端无需改动。

### 1.3 成功标准

- 验收清单（第 12 章）全部通过；
- 事件契约在 Java、TypeScript、`docs/events.md` 三处逐字段一致；
- 断线、乱序、重复、丢投递等异常下，UI 不崩溃且最终状态一致；
- 任务层级严格为两层：一个主任务 + 直属子任务；
- 重启进程后，MySQL 中的主任务档案仍可列表与查看；
- 全程没有任何真实大模型调用。

### 1.4 术语表

| 术语 | 定义 |
| --- | --- |
| Run（运行） | 一次完整的 Mock 编排执行，具有唯一 `runId` 与独立事件序列。 |
| Main Task（主任务） | plan 中唯一没有 `parentTaskId` 的根任务；有独立的 `mainTaskId` 与状态枚举。 |
| Subtask（子任务） | 直接挂在主任务下的任务；只允许一层，使用独立状态枚举。 |
| Archive（档案） | 从 MySQL 读取的只读主任务详情，不建立 SSE，也不会重启编排。 |
| Event（事件） | 通过 SSE 传输的结构化数据，统一使用信封（Envelope）。 |
| seq | 同一 `runId` 内事件的严格单调递增序号，从 1 开始，是排序、去重、续传的游标。 |
| eventId | 事件的唯一标识；Mock 噪声脚本可故意制造重复，消费端必须幂等。 |
| Snapshot（快照） | 服务端为一次运行生成的完整状态：运行信息 + 主任务 + 扁平子任务列表 + 全量事件。 |
| Orchestrator（编排器） | 产生事件、驱动任务状态推进的抽象；本阶段只有 Mock 实现。 |
| Mock 脚本 | 描述事件序列、延迟、等待输入与分支的 JSON 文件。 |
| Decision（抉择） | `waiting_input` 事件引发的用户选择闭环。 |
| 投影（Projection） | 由事件序列推导出的主任务 / 子任务状态与任务详情。 |

---

## 2. 用户与使用场景

### 2.1 用户画像

本阶段只有一个角色：**本地开发者 / 调试者**。

- 在本机启动后端与前端，单用户使用；
- 不登录、不鉴权、无多租户；
- 关注协议是否正确、UI 是否能还原运行过程，而不是生产效率。

### 2.2 使用场景

| 编号 | 场景 | 触发 | 期望结果 |
| --- | --- | --- | --- |
| UC-01 | 观察一次顺利运行 | 选择 `happy-path` 并开始 | 主任务与 5 个直属子任务逐级变为完成，详情面板展示思考、提示词、工具调用，事件日志完整。 |
| UC-02 | 回答 Agent 的追问 | 运行中出现 `waiting_input` | 详情区弹出抉择面板，点选并提交后，`input_received` 到达，任务继续。 |
| UC-03 | 网络中断后恢复 | 断开/恢复 SSE 连接 | 前端自动重连，先拉快照再按 `afterSeq` 续传，任务树无重复、无丢失。 |
| UC-04 | 处理失败与重试 | 运行 `with-failure` | 任务短暂显示“重试中”，随后重试成功，最终状态一致。 |
| UC-05 | 验证异常健壮性 | 运行 `with-noise` | 重复事件、乱序到达、延迟、丢投递下 UI 不崩溃，最终状态与脚本预期一致。 |
| UC-06 | 调试事件流 | 打开事件日志 | 可按类型过滤，查看 seq、type、时间、taskId，重复/缺口有明确提示。 |
| UC-07 | 查询历史主任务 | 打开顶栏「主任务库」 | 列出 MySQL 中多个主任务；打开任一记录后以只读方式展示其子任务、提示词、中间结果与事件账本。 |

---

## 3. 阶段范围与边界

### 3.1 本阶段必须包含

| 模块 | 内容 |
| --- | --- |
| 事件契约 | 信封、14 种事件类型、payload 结构、序号/去重/续传规则、状态机。 |
| 后端编排 | `TaskOrchestrator` 接口 + `MockTaskOrchestrator`；内存事件表；4 个两层 Mock 脚本。 |
| SSE 出口 | `SseEmitter` 管理、1 小时超时、15 秒心跳、`afterSeq` 补发、断连清理。 |
| REST API | 创建主任务、计划草案 / 修订、主任务列表 / 详情、提交抉择、获取快照、SSE 事件流。 |
| 持久化 | MySQL + MyBatis-Plus；`IService` / `ServiceImpl` / `BaseMapper` 管理实体；事件同步落库。 |
| 数据表 | `tn_main_task`、`tn_main_task_plan`、`tn_subtask`、`tn_prompt`、`tn_task_artifact`、`tn_event`。 |
| 前端工作台 | 上下页头 + 左右目录布局、强制 Markdown 计划表、两层子任务折叠详情、抉择面板、日志弹窗、连接状态、主任务档案。 |
| 前端状态机 | `taskTree` store：事件 → 主任务 + 子任务状态，幂等、排序、增量拼接、快照恢复、只读档案。 |
| SSE 客户端 | `EventSource` 封装、指数退避重连、`afterSeq` 续传、连接状态暴露。 |
| 文档 | `docs/events.md` 与前后端类型定义同步。 |

### 3.2 本阶段明确禁止（范围锁）

- **禁止**接入真实大模型、Spring AI `ChatClient`、`TaskTool` 或任何模型 SDK；
- **禁止**多模型路由、RAG、向量库、Embedding；
- **禁止**用户体系、权限、登录、鉴权；
- **禁止**消息队列、Redis、微服务拆分、服务注册发现；
- **禁止**为等待输入另开 SSE 连接、使用轮询或 WebSocket；
- **禁止**三层及以上任务层级；脚本出现非直属子任务时必须在加载阶段失败；
- **禁止**把实时运行恢复成可继续执行的进程状态；进程重启后只允许查询已落库档案；
- **禁止**任务取消、任务编辑等 P4/P5 功能。

数据库持久化已从范围锁中移除：允许使用 MySQL + MyBatis-Plus，但只保存主任务、计划表、
直属子任务、提示词、中间结果与事件原文，不引入 JPA、Flyway、消息队列或分布式事务。
计划表持久化与修订只影响档案展示，不改变 Mock 脚本的任务结构，也不接入真实模型规划。

### 3.3 后续阶段（仅记录，不实现）

| 阶段 | 内容 | 对本期的影响 |
| --- | --- | --- |
| P3 | 用 Spring AI + `spring-ai-agent-utils` 的 `TaskTool` 实现 `SpringAiTaskOrchestrator`，替换 Mock | `TaskOrchestrator` 接口、SSE 层、持久化映射与前端零改动；必须预留 `// P3:` 注释位。 |
| P4 | 运行中断点续传、跨进程恢复与更完整的历史检索 | 当前档案为只读；断点续传不在本期。 |
| P5 | 多模型路由、子 Agent 隔离、失败降级 | 事件契约保留扩展空间。 |

### 3.4 假设与约束

- 单个后端实例、单用户、本机运行；不考虑水平扩展与多副本一致性。
- 实时运行状态保存在进程内存，后端重启后不可续传；MySQL 中的主任务档案仍可查询，
  前端必须能识别“运行不存在”并停止重连。
- MySQL 8，库名默认 `task_nest`；连接信息通过 `TASKNEST_MYSQL_*` / `MYSQL_*` 环境变量提供，
  密码不得写入仓库。
- 表由 `schema.sql` 在启动时以 `CREATE TABLE IF NOT EXISTS` 创建；测试使用 H2（MODE=MySQL）。
- 时间统一使用 UTC，事件时间戳仅用于展示，排序与续传**只信任 seq**。
- 前端 UI 文案使用中文，代码与契约标识使用英文。
- 浏览器目标：最新版 Chrome / Edge（需支持 `EventSource`、ES2020）。
- 开发环境：Windows 10、Java 17+、Maven 3.9+、Node.js 20+、npm 10+。

---

## 4. 总体设计

### 4.1 组件架构

```
┌──────────────────────────────────────────────────────────────────┐
│ Browser (Vue 3 + TS + Pinia)                                     │
│                                                                  │
│  WorkbenchView                                                   │
│   ├─ RunToolbar        创建主任务、脚本、目标、运行总览           │
│   ├─ TaskTree          主任务 + 直属子任务（两层）                │
│   ├─ TaskDetail        思考 / 提示词 / 工具调用                  │
│   ├─ DecisionPanel     waiting_input 抉择（档案中禁用）          │
│   ├─ MainTaskLibrary   查询多个已持久化主任务                     │
│   └─ EventLog          seq / type / time / taskId                │
│            ▲                              │                      │
│            │ 读取状态                      │ POST /inputs         │
│      Pinia taskTree store ◄── SSE Client ─┼── REST Client        │
└────────────┼──────────────────────────────┼──────────────────────┘
             │ 事件信封                      │ JSON
┌────────────┼──────────────────────────────┼──────────────────────┐
│ Backend (Spring Boot 3.x)                 ▼                      │
│  MainTaskController / RunController ──► RunService                │
│       │                                   └─► TaskOrchestrator     │
│       │                                       └─ MockTaskOrchestrator│
│       │                                          │ publish(event) │
│       │                                          ▼                │
│       │                 RunStore (内存事件表 + 两层任务投影)      │
│       │                        │                                  │
│       ▼                        ▼                                  │
│  SseService ◄──────── EventPublisher ──► Subscriber queues        │
│   ├─ 历史补发 (afterSeq)          │                               │
│   ├─ 15s 心跳                     ▼                               │
│   ├─ 1h 超时              RunPersistenceService (事务)            │
│   └─ 断连清理                    │                                │
│                                  ▼                                │
│                    MySQL: tn_main_task / tn_subtask /             │
│                    tn_prompt / tn_task_artifact / tn_event        │
│                                  ▲                                │
│                    MainTaskQueryService（列表 / 详情）            │
└──────────────────────────────────────────────────────────────────┘
```

### 4.2 模块职责

| 模块 | 职责 | 禁止事项 |
| --- | --- | --- |
| `api` | 参数校验、调用编排器/查询服务、包装响应 | 禁止写业务逻辑、禁止直接操作 `SseEmitter`。 |
| `orchestrator` | 解析 Mock 脚本、按时序产生事件、等待/恢复抉择 | 禁止直接写 SSE；只能通过 `EventPublisher`。 |
| `event` | 事件类型、信封、payload 模型、序列化 | 禁止裸字符串表示事件类型。 |
| `sse` | 连接生命周期、补发、心跳、超时、断连清理 | 禁止因客户端断开而中断编排。 |
| `store` | 运行状态、事件表、两层任务投影、快照 | 禁止把 Mock 专有语义泄漏到契约。 |
| `persistence` | MyBatis-Plus 实体 / Mapper / IService、事件落库、主任务档案查询 | 禁止持久化非直属任务；禁止把持久化失败抛给 SSE 调用链。 |
| 前端 `stores` | 所有运行状态的唯一写入点；实时状态与只读档案分开 | 组件禁止直接改 store 状态。 |
| 前端 `api` | REST 与 SSE 传输、重连、解析 | 禁止在组件内直接 new `EventSource`。 |
| 前端 `components` | 展示与用户交互 | 禁止持有业务状态副本并自行改写。 |

### 4.3 核心流程

#### 4.3.1 正常流程

```
Client                              Backend
  │ POST /api/main-tasks {script,goal}│
  │──────────────────────────────────►│ 生成 mainTaskId/runId，写入 tn_main_task
  │                                   │ 创建 RunState，注册脚本
  │◄──────────────────────────────────│ 201 { mainTaskId, runId }
  │  GET /events?afterSeq=0           │
  │──────────────────────────────────►│ 注册订阅者 → 补发历史 → 接入实时
  │◄══════════════════════════════════│ run_started / plan / ...
  │                                   │ 每条事件同步写 tn_event 与领域表
  │                                   │
  │  （waiting_input，连接保持）       │
  │  POST /inputs {taskId, selectedOptionId} │
  │──────────────────────────────────►│ 校验 → 记录选择
  │◄──────────────────────────────────│ 202 Accepted
  │◄══════════════════════════════════│ input_received + 后续事件
  │◄══════════════════════════════════│ run_completed → 关闭连接
```

#### 4.3.2 断线重连流程（设计决策 D-04）

```
SSE onerror
  → 关闭当前 EventSource
  → 状态置为 RECONNECTING
  → 指数退避等待
  → GET /api/runs/{runId} 拉快照
      ├─ 404：后端重启/运行丢失 → 停止重连，提示“运行已失效”
      └─ 200：清空 store → 用快照重建 → 得到 snapshot.run.lastSeq
  → GET /events?afterSeq={lastSeq}
  → 补发历史 + 接入实时
  → 若仍检测到 seq 缺口，重复拉快照（二级兜底）
```

#### 4.3.3 设计原则

1. **事件是唯一事实来源**：任务树、详情、日志均由事件或快照投影得到。
2. **seq 是唯一排序依据**：时间戳、到达顺序都不可信。
3. **幂等优先**：重复 `eventId`、重复提交、重复补发都不能改变最终状态。
4. **空连接不阻塞编排**：客户端断开只影响推送，不影响事件产生与存储。
5. **契约先行**：新增事件类型必须同步 Java 枚举 / TS 类型 / `docs/events.md`。

---

## 5. 功能需求总表

> 编号规则：`FR-<领域>-<序号>`。每条需求均可在第 12 章找到验收方式。

### 5.1 运行管理（RUN）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-RUN-01 | 提供 `POST /api/main-tasks`，按 `mockScript` 与可选 `goal` 创建主任务并启动运行，返回 `mainTaskId` 与 `runId`。 | MUST |
| FR-RUN-02 | `mockScript` 必须属于允许列表：`happy-path`、`with-ambiguity`、`with-failure`、`with-noise`；未知值返回 400。 | MUST |
| FR-RUN-03 | 创建主任务必须异步启动编排；HTTP 响应不得等待脚本执行完成。 | MUST |
| FR-RUN-04 | 实时运行状态与事件保存在内存；主任务、子任务、提示词、中间结果与事件原文同步写入 MySQL。 | MUST |
| FR-RUN-05 | 同一后端进程内，多次创建主任务互不干扰；`mainTaskId` 与 `runId` 全局唯一；可保存多个主任务并分别查询详情。 | MUST |
| FR-RUN-06 | 运行具有 `RUNNING / COMPLETED / FAILED` 三种对外状态；首个终态事件生效后，后续终态事件忽略。 | MUST |
| FR-RUN-07 | 提供 `GET /api/main-tasks?limit=N` 与 `GET /api/main-tasks/{mainTaskId}`，从 MySQL 返回主任务列表与详情；未知主任务返回 40403。 | MUST |

### 5.2 SSE 通道（SSE）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-SSE-01 | 提供 `GET /api/runs/{runId}/events?afterSeq=N`，返回 `text/event-stream`。 | MUST |
| FR-SSE-02 | 连接建立后，先补发 `seq > afterSeq` 的全部历史事件，按 seq 升序，再接实时流，交接处不得丢失或重复。 | MUST |
| FR-SSE-03 | 单连接内实时事件按 seq 升序推送（`with-noise` 的 `dispatchDelayMs` 模拟乱序除外）。 | MUST |
| FR-SSE-04 | 连接建立后立即发送一次 `heartbeat`，此后每 15 秒发送一次；至少有一个订阅者时才产生心跳。 | MUST |
| FR-SSE-05 | `SseEmitter` 超时设为 1 小时（3,600,000 ms）；超时后清理连接，编排不受影响。 | MUST |
| FR-SSE-06 | 客户端断开时清理对应 emitter/队列，但不得停止编排，也不得停止写入事件表。 | MUST |
| FR-SSE-07 | 运行进入终态（`run_completed` 或 `error`）后，已推送终态事件的连接应在冲刷完成后关闭；新连接补发历史 + 一次心跳后关闭。 | MUST |
| FR-SSE-08 | 同一运行允许多个订阅者，每个订阅者独立收到完整事件；事件产生一次、共享序号。 | SHOULD |
| FR-SSE-09 | 响应头必须包含 `Cache-Control: no-cache`、`X-Accel-Buffering: no`，防止代理缓冲。 | MUST |
| FR-SSE-10 | 运行不存在返回 404；`afterSeq` 非法（负数、非整数、大于 `lastSeq`）返回 400；均使用 JSON 包装体，不建立 SSE 流。 | MUST |
| FR-SSE-11 | 心跳事件必须进入事件表并占用 seq，使 `afterSeq` 续传不会误判缺口。 | MUST |

### 5.3 事件契约（EVT）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-EVT-01 | 所有事件使用统一信封：`eventId / runId / seq / type / timestamp / task / payload`。 | MUST |
| FR-EVT-02 | `seq` 在同一运行内从 1 开始严格单调递增，任何事件（含心跳）都占用一个 seq。 | MUST |
| FR-EVT-03 | `eventId` 默认由 seq 生成且运行内唯一；Mock 脚本可显式覆盖以模拟重复。 | MUST |
| FR-EVT-04 | 必须实现第 6 章的 14 种事件类型与 payload 结构。 | MUST |
| FR-EVT-05 | 消费端按 `eventId` 幂等：重复事件不重复应用，但游标仍必须跨过其 seq。 | MUST |
| FR-EVT-06 | 消费端遇到未知 `type` 时记录日志并跳过语义，游标照常前进，不得卡死。 | MUST |
| FR-EVT-07 | 检测到 seq 缺口并持续 2 秒未补齐时，前端必须主动拉快照恢复，不得静默等待。 | MUST |
| FR-EVT-08 | `timestamp` 使用 UTC ISO-8601 毫秒格式（`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`），由服务端生成。 | MUST |
| FR-EVT-09 | 任务相关事件的 `task` 必须包含 `taskId / parentTaskId / title / agentName`；非任务事件为 `null`。 | MUST |
| FR-EVT-10 | 任务状态必须按第 6.6 节状态机推进；终态后的状态类事件忽略并记录警告。 | MUST |

### 5.4 抉择闭环（DEC）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-DEC-01 | `waiting_input` 推送后，该任务置为 `WAITING_INPUT`，编排暂停，但 SSE 连接不得关闭。 | MUST |
| FR-DEC-02 | 提供 `POST /api/runs/{runId}/inputs`，body 为 `{taskId, selectedOptionId}`，校验通过返回 202。 | MUST |
| FR-DEC-03 | 校验成功后必须推送 `input_received`，随后从所选分支继续执行；选择与分支必须匹配。 | MUST |
| FR-DEC-04 | 任务不在 `WAITING_INPUT`、选项不存在、运行已结束时，分别返回 409；重复提交只有第一次被接受。 | MUST |
| FR-DEC-05 | 不得为等待输入新开 SSE 连接，不得使用轮询，SSE 始终单向推送。 | MUST |
| FR-DEC-06 | 前端提交后不得乐观改状态，必须等待 `input_received` 事件；提交失败展示错误并允许重试。 | MUST |

### 5.5 Mock 编排（MOCK）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-MOCK-01 | 定义 `TaskOrchestrator` 接口：`start(runId, request)`、`submitInput(runId, taskId, optionId)`；本阶段唯一实现为 `MockTaskOrchestrator`。 | MUST |
| FR-MOCK-02 | 从 `classpath:mock/{script}.json` 读取脚本；脚本名只允许允许列表，禁止路径拼接。 | MUST |
| FR-MOCK-03 | 按每条事件的 `delayMs` 顺序产生事件；事件先写入内存事件表，再进入推送队列。 | MUST |
| FR-MOCK-04 | 遇到 `waiting_input` 时挂起当前运行的后续执行，直到 `submitInput` 被调用；其他运行不受影响。 | MUST |
| FR-MOCK-05 | 提供 4 个脚本并覆盖：两层成功结构、等待输入双分支、可重试失败、噪声（重复/乱序/延迟/丢投递）。 | MUST |
| FR-MOCK-06 | 脚本可按 `taskId` 自动补全 `task` 元数据；显式 `task` 优先，但不得与 `plan` 冲突。 | MUST |
| FR-MOCK-07 | 脚本加载时必须校验：事件类型、必填 payload、两层结构（子任务必须直接挂在根任务下）、父引用、选项与分支覆盖、延迟范围。 | MUST |
| FR-MOCK-08 | 必须保留 `// P3: 替换为 SpringAiTaskOrchestrator` 注释位；接口与事件契约不得包含 Mock 专有字段。 | MUST |
| FR-MOCK-09 | `input_received` 与 `heartbeat` 禁止写在脚本中，分别由编排器和 SSE 层生成。 | MUST |

### 5.6 前端工作台（FE）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-FE-01 | 顶部提供 Mock 脚本选择器、可选目标输入与“开始运行”按钮；运行中禁用，终态后可再次运行（重置当前视图）。 | MUST |
| FR-FE-02 | 工作台布局：顶部工具栏；左侧两层任务结构；中部任务详情；右侧（或底部）事件日志。 | MUST |
| FR-FE-03 | 任务结构固定为两层：主任务行 + 直属子任务列表；主任务可折叠子任务，点击任一节点联动详情。 | MUST |
| FR-FE-04 | 主任务与子任务分别展示状态图标与文案；主任务使用待执行 / 执行中 / 等待输入 / 已完成 / 失败，子任务额外支持重试中，不得只靠颜色区分。 | MUST |
| FR-FE-05 | 任务详情包含“思考过程 / 拼接提示词 / 工具调用”三个可折叠面板。 | MUST |
| FR-FE-06 | 思考过程必须按 `task_thought.delta` 增量拼接、自动滚动；用户上滚时暂停吸底并提供“回到底部”。 | MUST |
| FR-FE-07 | 拼接提示词分 `system / user / context / finalPrompt` 展示；context 逐条显示 source 与 content；多次拼接可切换查看。 | MUST |
| FR-FE-08 | 工具调用与结果必须配对展示工具名、参数、状态、结果、耗时；未配对调用显示“未返回/孤立结果”警告。 | MUST |
| FR-FE-09 | `waiting_input` 到达时自动选中该任务并展开 DecisionPanel；支持多任务等待时按任务切换。 | MUST |
| FR-FE-10 | 抉择面板展示 question 与选项卡片（label + description），单选后点击“提交选择”，展示提交中/失败状态。 | MUST |
| FR-FE-11 | 事件日志展示 seq、type、时间、taskId；支持按类型多选过滤；默认隐藏 heartbeat。 | MUST |
| FR-FE-12 | 顶部连接状态指示：未开始 / 连接中 / 已连接 / 重连中 / 已断开；展示重连次数与最近 seq。 | MUST |
| FR-FE-13 | 顶部运行总览展示 runId、脚本名、goal、运行状态、任务统计（成功/失败/总数）。 | MUST |
| FR-FE-14 | 未开始、加载中、空数据、运行失败、运行丢失均必须有明确空态/错误态，不得白屏。 | MUST |
| FR-FE-15 | 重复事件、seq 缺口恢复、解析失败应在界面上以非侵入方式提示（计数或横幅）。 | SHOULD |
| FR-FE-16 | 交互必须可键盘操作；状态图标有 aria-label；遵循 `prefers-reduced-motion`。 | SHOULD |
| FR-FE-17 | 顶部提供「主任务库」入口，列出多个已持久化主任务；打开后展示其子任务、提示词、中间结果与事件账本，并明确标注只读。 | MUST |
| FR-FE-18 | 档案模式下禁用抉择提交与模拟断线，不允许建立 SSE；用户可退出档案并新建主任务。 | MUST |

### 5.7 前端状态机（STORE）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-STORE-01 | 主任务单独维护一个节点，子任务以 `taskId` 为键维护扁平表并按 `order` 排序；固定渲染两层，不递归。 | MUST |
| FR-STORE-02 | 按 `eventId` 幂等，按 `seq` 排序与推进游标；重复事件不得重复拼接文本或重复计数。 | MUST |
| FR-STORE-03 | 乱序事件必须进入缓冲，待缺口补齐后按序应用；缺口持续 2 秒触发快照恢复。 | MUST |
| FR-STORE-04 | `task_thought` 必须 append，不得覆盖；`prompt_assembled` 保留历史，默认展示最新。 | MUST |
| FR-STORE-05 | `waiting_input` → 节点 `WAITING_INPUT` 并生成待办抉择；`input_received` → 恢复 `RUNNING` 并清除待办。 | MUST |
| FR-STORE-06 | 重连后先以快照重建主任务与全部直属子任务，再以 `afterSeq` 增量续接；快照事件与缓冲事件必须去重。 | MUST |
| FR-STORE-07 | 所有业务状态变更集中在此 store；组件只读，通过 action 触发提交与重连。 | MUST |
| FR-STORE-08 | 未知任务事件创建占位节点并告警；终态任务收到状态类事件忽略；未知事件类型跳过语义但推进游标。 | MUST |
| FR-STORE-09 | 运行终态时，所有未完成任务在客户端修复为 `FAILED`（合成错误文案），保证 UI 无悬空状态。 | MUST |
| FR-STORE-10 | 提供派生数据：主任务、子任务列表、选中节点、待办抉择、子任务统计、去重/缺口告警计数。 | MUST |
| FR-STORE-11 | 提供主任务库状态：列表、加载中、错误、当前档案；打开档案时重建事件账本但禁止 SSE 与提交抉择。 | MUST |

### 5.8 持久化（DB）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-DB-01 | 使用 MyBatis-Plus `BaseMapper`、`IService`、`ServiceImpl` 作为实体管理基础；不得手写重复 CRUD。 | MUST |
| FR-DB-02 | `tn_main_task` 保存多个主任务；`main_task_id` 唯一，包含标题、目标、脚本、状态、摘要、错误、等待抉择与时间。 | MUST |
| FR-DB-03 | `tn_subtask` 保存直属子任务；`(main_task_id, task_id)` 唯一，包含排序、依赖、独立状态、摘要、错误与等待抉择。 | MUST |
| FR-DB-04 | `tn_prompt` 保存每次 `prompt_assembled` 的 system / user / context / finalPrompt，`(main_task_id, seq)` 唯一。 | MUST |
| FR-DB-05 | `tn_task_artifact` 保存思考增量、工具调用；`tool_result` 按 `callId` 或工具名 FIFO 回填到对应调用记录。 | MUST |
| FR-DB-06 | `tn_event` 保存每条事件的原始 JSON 与 seq，`(run_id, seq)` 唯一，供档案账本回放。 | MUST |
| FR-DB-07 | 每条事件在内存投影后同步落库；落库失败记录日志，但不得阻断 SSE 推送或让前端崩溃。 | MUST |
| FR-DB-08 | 主任务失败直接 `FAILED`；子任务 `retryable=true` 落为 `RETRYING`，重试成功后为 `SUCCESS`。 | MUST |
| FR-DB-09 | 进程重启后实时运行不可恢复，但已落库的主任务列表与详情必须仍可查询。 | MUST |
| FR-DB-10 | 连接信息只从环境变量读取；`schema.sql` 必须同时兼容 MySQL 与 H2（MODE=MySQL）。 | MUST |

### 5.9 SSE 客户端（CLIENT）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-CLIENT-01 | 封装 `EventSource`，组件不得直接创建连接。 | MUST |
| FR-CLIENT-02 | 支持 `afterSeq` 续传，重连 URL 使用 store 中的最新游标。 | MUST |
| FR-CLIENT-03 | 断开后指数退避重连：初始 1s，倍率 2，上限 30s，附加 0–20% 抖动。 | MUST |
| FR-CLIENT-04 | 向 store 暴露连接状态、重连次数、最近事件时间。 | MUST |
| FR-CLIENT-05 | JSON 解析失败或信封缺字段时记录日志并忽略该事件，不得崩溃；游标缺口交由快照恢复。 | MUST |
| FR-CLIENT-06 | 组件卸载、开始新运行、运行终态、运行丢失时必须关闭连接并取消定时器。 | MUST |
| FR-CLIENT-07 | 运行处于终态时不得再重连；连接关闭视为正常结束。 | MUST |
| FR-CLIENT-08 | 禁止依赖浏览器原生自动重连（它会丢失 `afterSeq`）；出错时必须主动 `close()` 后自行调度。 | MUST |

---

## 6. 事件契约（核心）

本章为规范性内容。`docs/events.md`、后端 Java 模型、前端 `api/events.ts` 必须与本章逐字段一致。

### 6.1 统一信封

```json
{
  "eventId": "evt_000123",
  "runId": "run_20261021_100000_a1b2c3",
  "seq": 123,
  "type": "task_started",
  "timestamp": "2026-10-21T10:00:00.123Z",
  "task": {
    "taskId": "t_scope",
    "parentTaskId": "t_root",
    "title": "评估同步策略",
    "agentName": "架构师"
  },
  "payload": {}
}
```

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `eventId` | string | 是 | 默认 `evt_` + 6 位以上零填充 seq；运行内唯一（噪声脚本可故意重复）。 |
| `runId` | string | 是 | 格式 `run_yyyyMMdd_HHmmss_<6位十六进制>`，全局唯一。 |
| `seq` | integer (int64) | 是 | 从 1 开始，运行内严格单调递增；所有事件（含 heartbeat）都占用 seq。 |
| `type` | string | 是 | 第 6.3 节的 14 种小写下划线值之一。 |
| `timestamp` | string | 是 | UTC ISO-8601，毫秒精度；服务端生成，不参与排序。 |
| `task` | object \| null | 是 | 任务相关事件必须非 null；`run_started / run_completed / heartbeat / error` 为 null。 |
| `payload` | object | 是 | 无有效载荷时序列化为 `{}`，不得省略。 |

`task` 对象：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `taskId` | string | 是 | 运行内唯一任务标识，如 `t_scope`。 |
| `parentTaskId` | string \| null | 是 | 根任务为 null。 |
| `title` | string | 是 | 任务标题，非空。 |
| `agentName` | string | 是 | 执行该任务的 Agent 名称，非空。 |

### 6.2 SSE 传输格式

每个事件一帧，`event:` 与 `type` 一致，`id:` 为 `eventId`，`data:` 为单行紧凑 JSON：

```
id: evt_000123
event: task_started
data: {"eventId":"evt_000123","runId":"run_20261021_100000_a1b2c3","seq":123,"type":"task_started","timestamp":"2026-10-21T10:00:00.123Z","task":{"taskId":"t_scope","parentTaskId":"t_root","title":"评估同步策略","agentName":"架构师"},"payload":{}}

```

约束：

- `data` 不得换行；不得额外包裹 `{code, message, data}`。
- 不发送 SSE 注释保活行；保活使用 `heartbeat` 事件。
- 不使用 `retry:` 字段；重连策略由前端控制。
- 心跳也是完整信封，`type` 为 `heartbeat`，`task` 为 `null`，`payload` 为 `{}`。

### 6.3 事件类型总表

| type | 触发时机 | task | payload 关键字段 |
| --- | --- | --- | --- |
| `run_started` | 一次运行开始 | null | `goal` (string) |
| `plan` | 主任务规划完成 | 根任务 | `tasks[]` |
| `task_started` | 子任务开始执行（含重试） | 任务 | `{}` |
| `task_thought` | 思考过程增量 | 任务 | `delta` (string) |
| `prompt_assembled` | 提示词拼接完成 | 任务 | `systemPrompt`, `userPrompt`, `contextSnippets[]`, `finalPrompt` |
| `tool_call` | 工具调用发起 | 任务 | `toolName`, `arguments`, 可选 `callId` |
| `tool_result` | 工具返回 | 任务 | `toolName`, `status`, `result`, `durationMs`, 可选 `callId` |
| `waiting_input` | 等待用户抉择，任务暂停 | 任务 | `question`, `options[]` |
| `input_received` | 用户抉择已提交 | 任务 | `selectedOptionId` |
| `task_completed` | 子任务完成 | 任务 | `summary` (string) |
| `task_failed` | 子任务失败 | 任务 | `error` (string), `retryable` (boolean) |
| `run_completed` | 全部完成 | null | `summary` (string) |
| `error` | 系统级错误 | null | `code` (string), `message` (string) |
| `heartbeat` | 保活，连接后立即一次，之后约每 15s | null | `{}` |

### 6.4 各事件 payload 定义

#### 6.4.1 `run_started`

```json
{ "goal": "为订单服务生成接口文档" }
```

- `goal`：非空 string，运行目标；快照与顶部总览展示。

#### 6.4.2 `plan`

```json
{
  "tasks": [
    { "taskId": "t_root",  "parentTaskId": null,      "title": "生成订单服务接口文档", "agentName": "lead",      "dependsOn": [] },
    { "taskId": "t_scan",  "parentTaskId": "t_root",  "title": "扫描 Controller",      "agentName": "scanner",   "dependsOn": [] },
    { "taskId": "t_list",  "parentTaskId": "t_root",  "title": "抽取接口清单",         "agentName": "extractor", "dependsOn": ["t_scan"] },
    { "taskId": "t_merge", "parentTaskId": "t_root",  "title": "合并接口参数",         "agentName": "merger",    "dependsOn": ["t_list"] }
  ]
}
```

- `tasks` 必须包含**完整两层结构，包括根任务**；根任务 `parentTaskId=null`，且每份脚本恰好一个根。
- 除根任务外，所有任务都必须直接挂在根任务下；出现更深层级时脚本加载失败。
- `taskId` 在运行内唯一；父引用必须存在；不得成环。
- `dependsOn` 为任务 ID 数组，可以为空；本阶段仅用于展示，不参与调度。
- 信封中的 `task` 为根任务引用。
- 同一运行本阶段只允许出现一次 `plan`，且必须早于任何任务事件。
- 根任务投影为主任务，直属子任务投影为子任务；前端固定渲染两层。

#### 6.4.3 `task_started`

```json
{}
```

- 任务进入 `RUNNING`；重试时再次发送该事件。
- 根任务也必须收到 `task_started` / `task_completed`，父节点状态不做隐式聚合。

#### 6.4.4 `task_thought`

```json
{ "delta": "先定位所有 Controller，再按模块分组……" }
```

- `delta` 非空 string，是增量片段；消费端必须 append。
- 同一任务可多次发送；顺序由 seq 决定。

#### 6.4.5 `prompt_assembled`

```json
{
  "systemPrompt": "你是一名接口文档专家……",
  "userPrompt": "请扫描并抽取以下服务……",
  "contextSnippets": [
    { "source": "file:src/main/java/.../OrderController.java", "content": "..." },
    { "source": "memory:project-structure", "content": "..." }
  ],
  "finalPrompt": "……最终拼接后的完整提示词……"
}
```

- 四个字段均为 string / array，不得为 null；`systemPrompt`、`userPrompt` 可为空字符串；`finalPrompt` 非空。
- `contextSnippets[].source`、`.content` 均为 string，可为空数组。
- 同一任务可因重试产生多条，前端保留历史并默认展示最新一条。

#### 6.4.6 `tool_call`

```json
{
  "callId": "call_0001",
  "toolName": "list_files",
  "arguments": { "path": "src/main/java" }
}
```

- `toolName` 非空；`arguments` 必须为 object，可为 `{}`。
- `callId` 为**可选扩展字段**（见 D-03）：存在时用于精确配对；缺省时前端按 `taskId + toolName` 的 FIFO 顺序配对。

#### 6.4.7 `tool_result`

```json
{
  "callId": "call_0001",
  "toolName": "list_files",
  "status": "success",
  "result": { "files": ["OrderController.java"] },
  "durationMs": 120
}
```

- `status` 必须是 `success | error`。
- `result` 为任意 JSON 值（object / array / string / number / boolean / null），前端格式化展示。
- `durationMs` 为整数，≥ 0。
- 配对规则：优先 `callId`；否则同任务同工具名的最早未配对 `tool_call`。
- 找不到配对调用时，仍展示为“孤立结果”并记录警告。

#### 6.4.8 `waiting_input`

```json
{
  "question": "首发版本的缓存策略选哪条？",
  "options": [
    { "id": "local-first", "label": "本地优先", "description": "实现快、无账号依赖；多设备之间需要手动迁移缓存。" },
    { "id": "cloud-sync",  "label": "云端同步", "description": "多设备自动同步；需要账号与网络。" }
  ]
}
```

- `question` 非空。
- `options` 至少 2 个；`id` 非空且同一事件内唯一；`label` 非空；`description` 可为空字符串。
- 推送后该任务暂停；SSE 保持连接；心跳继续。

#### 6.4.9 `input_received`

```json
{ "selectedOptionId": "local-first" }
```

- 只能由编排器在 `submitInput` 校验通过后生成；脚本禁止包含。
- `selectedOptionId` 必须匹配该任务当前 `waiting_input.options[].id`。

#### 6.4.10 `task_completed`

```json
{ "summary": "已抽取 12 个接口，合并为 8 个文档条目。" }
```

- `summary` 为 string，可为空字符串；前端展示在任务头部/概要区。

#### 6.4.11 `task_failed`

```json
{ "error": "读取 OrderController 超时", "retryable": true }
```

- `error` 非空；`retryable` 必填 boolean。
- `retryable=true` → 客户端置 `RETRYING`，等待后续 `task_started`。
- `retryable=false` → 置 `FAILED`，终态。

#### 6.4.12 `run_completed`

```json
{ "summary": "订单服务接口文档生成完成。" }
```

- 运行状态置 `COMPLETED`，即使存在失败的子任务（任务级失败不改变运行完成事实）。
- 推送后关闭已建立的 SSE 连接（冲刷后）。

#### 6.4.13 `error`

```json
{ "code": "ORCHESTRATOR_ERROR", "message": "Mock 脚本执行异常" }
```

- 系统级错误，运行状态置 `FAILED`；`task=null`。
- 推送后关闭已建立的 SSE 连接。
- 客户端将未完成任务修复为 `FAILED`，错误信息使用 `message`。

#### 6.4.14 `heartbeat`

```json
{}
```

- `task=null`；每连接建立后立即发送一次，之后约每 15 秒一次。
- 必须进入事件表并占用 seq（D-01）。
- 仅当该运行至少有一个订阅者时产生；最后一个订阅者断开后停止产生。
- 客户端默认不在事件日志展示，不参与任务状态，但参与 seq 缺口检测。

### 6.5 序号、去重与游标规则

1. **分配**：所有事件由 `EventPublisher` 在产生时原子分配 seq；心跳也走同一分配器。
2. **持久化**：先写入运行事件表，再进入订阅队列；保证快照/续传能读到。
3. **传输顺序**：默认按 seq 升序推送。`with-noise` 脚本可通过 `dispatchDelayMs` 故意乱序，仅用于测试。
4. **消费端游标** `lastSeq`：表示已按序处理到的最大连续 seq。
5. **乱序缓冲**：收到 `seq > lastSeq + 1` 时进入缓冲；补齐 `lastSeq + 1` 后连续排空。
6. **缺口恢复**：若缺口持续 2 秒仍未补齐，前端拉取快照重建；重建后丢弃 `seq <= snapshot.run.lastSeq` 的缓冲，应用其余缓冲。
7. **eventId 幂等**：已应用过的 `eventId` 再次出现时，不重复应用语义，但**仍推进游标**，避免缺口永久存在。记录重复计数与警告。
8. **同 seq 冲突**：两个不同 `eventId` 占用同一 seq 属于异常；保留先到达者，记录错误，后者忽略。
9. **快照重放**：快照中的事件按 seq 升序重放；完成后 `lastSeq = max(重放结果, snapshot.run.lastSeq)`。
10. **未知类型**：跳过语义、记录警告、推进游标。由于 `EventSource` 只能按事件名注册监听，浏览器层面未知事件名不会被交付；这属于可接受限制，新增事件类型必须同步前端。
11. **重复补发 / 多次重连**：不得产生重复节点、重复思考文本或重复工具调用。

### 6.6 任务与运行状态机

主任务与子任务使用两套独立的状态枚举，不共用 `TaskStatus`，也不由前端从子任务隐式聚合主任务状态。

#### 6.6.1 主任务状态（`MainTaskStatus`）

| 状态 | 含义 | UI 文案 |
| --- | --- | --- |
| `PENDING` | 主任务已创建，尚未开始 | 待执行 |
| `RUNNING` | 正在执行 | 执行中 |
| `WAITING_INPUT` | 等待用户抉择 | 等待输入 |
| `COMPLETED` | 主任务完成 | 已完成 |
| `FAILED` | 主任务最终失败 | 失败 |

主任务没有 `RETRYING` 与 `SUCCESS`：主任务失败即终态，重试只发生在子任务层面。

#### 6.6.2 子任务状态（`SubtaskStatus`）

| 状态 | 含义 | UI 文案 |
| --- | --- | --- |
| `PENDING` | 已规划，尚未开始 | 待执行 |
| `RUNNING` | 正在执行 | 执行中 |
| `WAITING_INPUT` | 等待用户抉择 | 等待输入 |
| `RETRYING` | 可重试失败，等待重试 | 重试中 |
| `SUCCESS` | 成功完成 | 成功 |
| `FAILED` | 最终失败 | 失败 |

#### 6.6.3 主任务状态迁移

| 当前状态 | 事件 | 新状态 | 说明 |
| --- | --- | --- | --- |
| 不存在 | 创建主任务 | `PENDING` | `tn_main_task` 先落库，随后启动运行。 |
| `PENDING` | `plan` | `PENDING` | 唯一根节点填充主任务标题与 agent。 |
| 任意非终态（`PENDING` / `WAITING_INPUT`） | `task_started` | `RUNNING` | 记录开始时间；`RUNNING` 重复开始保持不变。 |
| `RUNNING` | `task_thought` / `prompt_assembled` / `tool_call` / `tool_result` | 不变 | 附加详情数据。 |
| `RUNNING` | `waiting_input` | `WAITING_INPUT` | 记录待办抉择，自动选中主任务节点。 |
| `WAITING_INPUT` | `input_received` | `RUNNING` | 记录所选选项并清除待办。 |
| `RUNNING` / `WAITING_INPUT` | `task_completed` | `COMPLETED` | 记录 summary。 |
| `RUNNING` / `WAITING_INPUT` | `task_failed` | `FAILED` | 无论 `retryable` 为何值，主任务都不重试。 |
| 任意非终态 | `run_completed` | `COMPLETED` | 若主任务未收到 `task_completed`，用 `run_completed.summary` 补齐。 |
| 任意非终态 | `error` | `FAILED` | 使用 `error.message` 作为错误。 |
| `COMPLETED` / `FAILED` | 任何任务状态类事件 | 不变 | 记录警告；游标继续推进。 |

#### 6.6.4 子任务状态迁移

| 当前状态 | 事件 | 新状态 | 说明 |
| --- | --- | --- | --- |
| 不存在 | `plan` | `PENDING` | 只为直接挂在主任务下的 `tasks[]` 建节点。 |
| `PENDING` | `task_started` | `RUNNING` | — |
| `RUNNING` | `task_thought` / `prompt_assembled` / `tool_call` / `tool_result` | `RUNNING` | 附加详情数据，不改变状态。 |
| `RUNNING` | `waiting_input` | `WAITING_INPUT` | 记录待办抉择，自动选中节点。 |
| `WAITING_INPUT` | `input_received` | `RUNNING` | 清除该任务待办。 |
| `RUNNING` / `WAITING_INPUT` | `task_failed` (`retryable=true`) | `RETRYING` | 记录错误，不终止运行。 |
| `RETRYING` | `task_started` | `RUNNING` | 重试开始。 |
| `RETRYING` | `task_failed` (`retryable=true`) | `RETRYING` | 允许多次重试，保留最近一次错误。 |
| `RUNNING` / `WAITING_INPUT` / `RETRYING` | `task_failed` (`retryable=false`) | `FAILED` | 终态。 |
| `RUNNING` | `task_completed` | `SUCCESS` | 记录 summary。 |
| `SUCCESS` / `FAILED` | 任何任务状态类事件 | 不变 | 记录警告；游标继续推进。 |
| 任意非终态 | `run_completed` | `FAILED` | 服务端/客户端修复，合成错误“运行已完成但该子任务未完成”。 |
| 任意非终态 | `error` | `FAILED` | 合成错误使用 `error.message`。 |

补充规则：

- 两层结构固定：`plan` 中唯一没有 `parentTaskId` 的节点是主任务，其余节点必须直接挂在它下面；出现第二个根节点或更深层级时脚本非法，加载阶段即失败。
- 子任务 `task_started` 前收到 `task_thought` 等详情事件：允许先建占位节点并记录详情，状态保持 `PENDING`，同时告警（正常脚本不应出现）。
- 同一任务的 `waiting_input` 未解决时，不允许再次出现 `waiting_input`；若出现，后者覆盖待办并告警。
- 主任务状态不做隐式聚合；脚本必须为主任务发送自己的开始/完成事件。
- 终态任务不再接受新的详情事件（忽略并记录）。
- 主任务与子任务分别投影、分别持久化到 `tn_main_task` 与 `tn_subtask`，前端也分别渲染状态标签。

#### 6.6.5 运行状态

| 状态 | 进入条件 | 说明 |
| --- | --- | --- |
| `RUNNING` | 创建运行后 | 对外唯一进行中状态；`run_started` 之前也视为 RUNNING。 |
| `COMPLETED` | `run_completed` | 终态；即使有子任务失败也如此。 |
| `FAILED` | `error` | 终态；系统级失败。 |

- 首个终态事件生效；重复或后续终态事件忽略。
- 连接断开不改变运行状态。

### 6.7 事件与任务数据的一致性

- 服务端在 `RunState` 中维护与前端相同规则的任务投影，供快照使用；快照中的 `mainTask` / `subtasks` 状态是重连后的权威状态。
- 快照必须包含全量事件（含 `heartbeat` 与重复 `eventId` 事件），按 seq 升序。
- 快照结构固定为 `run + mainTask + subtasks + events`，不再返回递归 `taskTree`；`subtasks` 只包含直属子任务。
- 前端重放快照事件后，以 `snapshot.mainTask` 与 `snapshot.subtasks` 校准节点状态与缺失节点；`snapshot.run.lastSeq` 为游标下限。
- 服务端与前端都必须执行终态修复（6.6.3 / 6.6.4 最后两行），避免悬空状态。
- MySQL 档案与实时快照分离：档案详情返回主任务、子任务、提示词、中间结果与事件原文，事件只用于账本回放，不重新启动编排。

---

## 7. REST / SSE 接口规格

### 7.1 通用约定

- Base Path：`/api`。
- 传输：UTF-8 JSON；请求 `Content-Type: application/json`。
- 除 SSE 流本身外，所有响应统一包装：

```json
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | integer | 0 表示成功；非 0 为业务错误码。 |
| `message` | string | 人类可读信息，中文；成功固定 `ok`。 |
| `data` | object \| array \| null | 成功时为业务数据；错误时为 `null`。 |

- HTTP 状态码与业务码同时表达语义；前端优先按 HTTP 状态处理，展示 `message`。
- 未捕获异常由统一异常处理器包装，禁止返回 Spring 默认错误页。

### 7.2 错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| `0` | 200/201/202 | 成功 |
| `40001` | 400 | 参数校验失败（缺字段、类型错误、格式错误） |
| `40002` | 400 | `afterSeq` 非法（负数、非整数或大于当前 `lastSeq`） |
| `40003` | 400 | `mockScript` 不在允许列表 |
| `40401` | 404 | 运行不存在（含后端重启后丢失） |
| `40402` | 404 | 任务不存在 |
| `40403` | 404 | 主任务档案不存在 |
| `40901` | 409 | 任务当前不处于等待输入状态（含重复提交） |
| `40902` | 409 | 抉择选项无效 |
| `40903` | 409 | 运行已结束 |
| `50001` | 500 | 服务内部错误 |
| `50002` | 500 | 内置 Mock 脚本无效或缺失 |

错误响应示例：

```json
{
  "code": 40401,
  "message": "运行不存在",
  "data": null
}
```

### 7.3 创建主任务与运行

主入口是 `POST /api/main-tasks`。每次创建都会先写入一条 `PENDING` 主任务，再启动一次运行；
`POST /api/runs` 保留为兼容入口，行为等价于不带 `goal` 的创建主任务。

#### 7.3.1 创建主任务

```
POST /api/main-tasks
Content-Type: application/json
```

请求：

```json
{
  "mockScript": "happy-path",
  "goal": "生成一份竞品分析",
  "plan": {
    "title": "交付竞品分析报告",
    "rows": [
      {
        "title": "收集竞品事实",
        "agent": "研究员",
        "dependency": "—",
        "output": "事实与来源"
      }
    ],
    "revisions": ["按提示「增加一步风险复核」增加风险复核步骤"]
  }
}
```

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `mockScript` | string | 是 | 必须属于 `happy-path / with-ambiguity / with-failure / with-noise`。 |
| `goal` | string | 否 | 最多 2000 字；去除首尾空白后为空则视为未填写。 |
| `plan` | object | 否 | 确认后的计划表；省略时由后端按 Mock 脚本的 `plan` 事件生成版本 1。 |

`plan` 字段：

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `title` | string | 是 | 非空，最多 255 字。 |
| `rows` | array | 是 | 至少 1 行；每行含 `title / agent / dependency / output`，均为字符串。 |
| `revisions` | string[] | 否 | 已执行的提示词修订记录，按时间顺序。 |

行为：

1. 校验脚本名与脚本可加载性，并从脚本 `plan` 的唯一根节点推导初始标题；
2. 携带 `plan` 时校验其行结构，并以此作为确认稿；未携带时生成版本 1 计划草案；
3. 生成 `mainTaskId` 与 `runId`，先向 `tn_main_task` 写入 `PENDING` 记录，
   再向 `tn_main_task_plan` 写入计划表（版本 = `revisions.size() + 1`）；
4. 创建内存 `RunState` 并异步启动 Mock 编排；启动失败时将主任务标记为 `FAILED`；
5. 立即返回 201，不等待首个事件产生；响应中的 `plan` 是实际保存的计划表。

成功响应 `201 Created`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "mainTaskId": "mt_20261021_100000_a1b2c3",
    "runId": "run_20261021_100000_d4e5f6",
    "mockScript": "happy-path",
    "status": "PENDING",
    "createdAt": "2026-10-21T10:00:00.000Z",
    "plan": {
      "mainTaskId": "mt_20261021_100000_a1b2c3",
      "title": "交付竞品分析报告",
      "goal": "生成一份竞品分析",
      "mockScript": "happy-path",
      "version": 2,
      "rows": [],
      "revisions": [],
      "markdown": "# 交付竞品分析报告\n..."
    }
  }
}
```

失败：

- 未知脚本 → 400 / `40003`；
- 缺字段、`goal` 超长、计划表行结构非法 → 400 / `40001`；
- 内置脚本损坏或层级非法 → 500 / `50002`。

#### 7.3.2 兼容创建运行

```
POST /api/runs
Content-Type: application/json

{ "mockScript": "happy-path" }
```

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `mockScript` | string | 是 | 同 7.3.1。 |

行为与 7.3.1 相同，但 `goal` 为空，响应沿用运行视角：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "runId": "run_20261021_100000_d4e5f6",
    "mainTaskId": "mt_20261021_100000_a1b2c3",
    "mockScript": "happy-path",
    "status": "RUNNING",
    "createdAt": "2026-10-21T10:00:00.000Z"
  }
}
```

失败码同 7.3.1。

#### 7.3.3 主任务列表

```
GET /api/main-tasks?limit=50
```

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `limit` | query | 否 | 默认 50，范围 1–200；按 `createdAt` 倒序。 |

`data` 为 `MainTaskSummary[]`，每项包含 `mainTaskId`、`runId`、`rootTaskId`、`title`、
`goal`、`mockScript`、`planVersion`、`status`、`summary`、`error`、`subtaskCount`、
`completedSubtaskCount`、`createdAt`、`updatedAt`、`completedAt`。
`completedSubtaskCount` 只统计 `status = SUCCESS` 的子任务；`planVersion` 在没有计划表时
为 0（历史数据），否则为已保存计划的当前版本。

#### 7.3.4 主任务档案详情

```
GET /api/main-tasks/{mainTaskId}
```

`data` 为 `MainTaskDetail`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `mainTask` | `MainTaskSummary` | 主任务投影与子任务计数。 |
| `plan` | `PlanDraftView \| null` | 已保存的计划表；历史任务可能为 `null`。 |
| `subtasks` | `SubtaskView[]` | 直属子任务，按 `order` 升序。 |
| `prompts` | `PromptView[]` | 每次 `prompt_assembled` 一条，按 `seq` 升序。 |
| `artifacts` | `ArtifactView[]` | 思考增量与工具调用；工具结果并入对应 `TOOL_CALL` 行的 `status` / `content`。 |
| `events` | object[] | 原始事件 JSON，按 `seq` 升序，用于只读账本回放。 |
| `lastSeq` | number | 档案中最大事件序号；无事件时为 0。 |

失败：

- 主任务不存在 → 404 / `40403`。

#### 7.3.5 计划表草案与修订

创建前的计划流程由三个接口组成，均不启动运行：

```
POST /api/main-tasks/plan-drafts
POST /api/main-tasks/plan-drafts/revise
POST /api/main-tasks/{mainTaskId}/plan/revisions
```

1. **生成草案**：body `{"mockScript":"happy-path","goal":"目标"}`。后端读取 Mock 脚本的
   `plan` 事件，生成 `title / rows / markdown`，`version = 1`，`mainTaskId = null`。
2. **修订草案**：body 包含草案的 `mockScript / title / goal / rows / revisions` 与
   `instruction`。返回新草案，`version = revisions.size() + 1`；草案不落库。
3. **修订已保存计划**：body `{"instruction":"增加一步风险复核"}`。仅在主任务未进入终态时
   允许；成功后立即更新 `tn_main_task_plan` 并返回最新 `PlanDraftView`。

修订语义：

- 修订只改变计划表内容与版本，不会追加、删除或重启已经运行的子任务；
- 修订记录写入 `revisions`，同时渲染到 Markdown 的「修订记录」小节；
- 主任务进入 `COMPLETED / FAILED` 后修订返回 `409 / 40904 PLAN_READ_ONLY`；
- 主任务不存在返回 `404 / 40403`；主任务没有计划表返回 `404 / 40404 PLAN_NOT_FOUND`；
- 未知脚本返回 `400 / 40003`；空指令或非法行结构返回 `400 / 40001`。

`PlanDraftView` 字段：`mainTaskId`、`title`、`goal`、`mockScript`、`version`、
`rows`、`revisions`、`markdown`。每行含 `title / agent / dependency / output`。

### 7.4 SSE 事件流

```
GET /api/runs/{runId}/events?afterSeq={N}
Accept: text/event-stream
```

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `runId` | path | 是 | 运行 ID。 |
| `afterSeq` | query | 否 | 默认 0；只补发 `seq > N` 的事件；必须是 ≥0 的整数且 ≤ `lastSeq`。 |

前置校验（在建立 SSE 之前以 JSON 返回）：

- 运行不存在 → 404 / `40401`；
- `afterSeq` 非法 → 400 / `40002`。

成功响应头：

```
Content-Type: text/event-stream;charset=UTF-8
Cache-Control: no-cache
X-Accel-Buffering: no
```

连接行为：

1. 注册订阅者并冻结交接点；
2. 按 seq 升序补发 `seq > afterSeq` 的历史事件；
3. 发送一次即时心跳；
4. 接入实时事件，保证交接处不丢失、不重复；
5. 每 15s 发送一次心跳（同一运行共享节拍，不按连接重复）；
6. 运行终态：冲刷后关闭连接；
7. 客户端断开：清理订阅者，编排继续；
8. 1 小时超时：清理连接，前端自动重连并续传。

示例请求：

```bash
curl -N "http://localhost:8080/api/runs/run_20261021_100000_a1b2c3/events?afterSeq=0"
```

续传示例：

```bash
# 假设已处理到 seq=42
curl -N "http://localhost:8080/api/runs/run_20261021_100000_a1b2c3/events?afterSeq=42"
# 期望：第一帧事件 seq=43（若存在历史），否则等待实时事件/心跳
```

### 7.5 提交抉择

```
POST /api/runs/{runId}/inputs
Content-Type: application/json
```

请求：

```json
{
  "taskId": "t_scope",
  "selectedOptionId": "local-first"
}
```

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `taskId` | string | 是 | 必须是该运行中正在等待输入的任务。 |
| `selectedOptionId` | string | 是 | 必须属于该任务当前 `waiting_input.options[].id`。 |

成功响应 `202 Accepted`：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "accepted": true }
}
```

并发与重复提交：

- 采用原子状态切换，只有第一次提交被接受；
- 已接受后再次提交 → 409 / `40901`；
- 校验通过后异步发布 `input_received`，随后执行所选分支；
- HTTP 响应与 `input_received` 的到达顺序不做保证，前端必须等待事件。

失败：

| 场景 | HTTP / code |
| --- | --- |
| 缺字段、空字符串 | 400 / `40001` |
| 运行不存在 | 404 / `40401` |
| 任务不存在 | 404 / `40402` |
| 任务不在等待输入 | 409 / `40901` |
| 选项无效 | 409 / `40902` |
| 运行已结束 | 409 / `40903` |

### 7.6 全量快照

```
GET /api/runs/{runId}
```

成功响应 `200`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "run": {
      "runId": "run_20261021_100000_d4e5f6",
      "mainTaskId": "mt_20261021_100000_a1b2c3",
      "mockScript": "happy-path",
      "goal": "为订单服务生成接口文档",
      "status": "RUNNING",
      "createdAt": "2026-10-21T10:00:00.000Z",
      "updatedAt": "2026-10-21T10:00:05.123Z",
      "lastSeq": 42
    },
    "mainTask": {
      "mainTaskId": "mt_20261021_100000_a1b2c3",
      "taskId": "t_root",
      "title": "生成订单服务接口文档",
      "agentName": "lead",
      "status": "RUNNING",
      "summary": null,
      "error": null,
      "selectedOptionId": null,
      "waitingInput": null,
      "startedAt": "2026-10-21T10:00:00.500Z",
      "completedAt": null
    },
    "subtasks": [
      {
        "taskId": "t_scan",
        "title": "扫描 Controller",
        "agentName": "scanner",
        "status": "SUCCESS",
        "summary": "发现 3 个 Controller",
        "error": null,
        "dependsOn": [],
        "order": 0,
        "selectedOptionId": null,
        "waitingInput": null,
        "startedAt": "2026-10-21T10:00:01.000Z",
        "completedAt": "2026-10-21T10:00:02.000Z"
      }
    ],
    "events": [ ]
  }
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `run.goal` | `run_started` 之前可为 `null`。 |
| `run.status` | `RUNNING / COMPLETED / FAILED`。 |
| `run.lastSeq` | 最新已分配 seq；无事件时为 0。 |
| `run.updatedAt` | 最近事件时间或创建时间。 |
| `mainTask` | 主任务投影；`plan` 之前 `taskId` / `agentName` 可为 `null`，状态为 `PENDING`。 |
| `mainTask.waitingInput` | 主任务等待抉择时为 `{question, options[]}`，否则为 `null`。 |
| `subtasks` | 直属子任务，按 `order` 升序；不存在递归 `children`，也不存在第三个层级。 |
| `subtasks[].waitingInput` | 该子任务等待抉择时的待办，否则为 `null`。 |
| `events` | 全量事件，按 seq 升序，包含 heartbeat 与重复 eventId 事件。 |

- 快照固定为 `run + mainTask + subtasks + events`，不再返回 `taskTree`。
- 主任务使用 `MainTaskStatus`，子任务使用 `SubtaskStatus`；两者都是权威状态，前端重放事件后用其校准。
- 运行不存在 → 404 / `40401`。

### 7.7 CORS 与本地开发

- 推荐前端通过 Vite 代理 `/api` 到 `http://localhost:8080`，避免 CORS 与 SSE 跨域问题。
- 若直连后端，后端允许本机开发源 `http://localhost:*`、`http://127.0.0.1:*`
  （Vite 在 5173 被占用时会自动顺延端口），不放开其他来源。
- 允许方法：`GET, POST, OPTIONS`；允许头：`Content-Type`；不启用凭据。
- 后端默认绑定 `127.0.0.1`，减少本机暴露面。

---

## 8. Mock 编排器与脚本

### 8.1 编排器接口

```java
public interface TaskOrchestrator {
    String start(String runId, RunRequest request);
    void submitInput(String runId, String taskId, String optionId);
}
```

- `start`：加载并校验脚本，提交异步执行，返回 `runId`（或由调用方生成后传入）。
- `submitInput`：校验并唤醒等待中的运行；校验失败抛出可映射为 7.2 错误码的业务异常。
- 本阶段唯一实现：`MockTaskOrchestrator`。
- P3 实现：`SpringAiTaskOrchestrator`，接口不变；SSE/前端零改动。
- 必须保留注释位：`// P3: 替换为 SpringAiTaskOrchestrator`。

### 8.2 运行生命周期

1. 创建 `RunState`（runId、脚本名、事件表、seq 分配器、任务表、订阅者表、状态）。
2. 异步执行脚本：
   - 逐条等待 `delayMs`；
   - 生成事件（分配 seq、eventId、timestamp、task）；
   - 写入事件表；
   - 交给推送层（可能再延迟 `dispatchDelayMs`）。
3. 遇到 `waiting_input`：
   - 推送事件；
   - 记录等待状态（taskId、options、待唤醒信号）；
   - 暂停当前运行后续事件，不阻塞其他运行；
   - 不关闭 SSE。
4. `submitInput` 校验通过：
   - 原子清除等待状态；
   - 发布 `input_received`；
   - 执行所选分支；
   - 分支结束后继续顶层后续事件。
5. `run_completed` / `error`：标记终态，停止脚本，通知 SSE 层冲刷并关闭连接。

### 8.3 脚本结构

顶层：

```json
{
  "description": "可选，人类可读说明",
  "events": [ ]
}
```

单条事件：

```json
{
  "delayMs": 300,
  "type": "task_thought",
  "taskId": "t_research",
  "payload": { "delta": "..." },
  "eventId": "evt_optional_override",
  "dispatchDelayMs": 0,
  "dropDispatch": false,
  "branches": { }
}
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `delayMs` | integer | 否 | 0 | 相对上一条事件**产生**的等待毫秒数；范围 0–60000。 |
| `type` | string | 是 | — | 14 种事件类型之一；禁止 `input_received` / `heartbeat`。 |
| `taskId` | string | 任务事件必填 | — | 自动补全 `task` 元数据。`plan` 可省略，编排器从 `tasks[]` 中 `parentTaskId=null` 的节点推导根任务；`run_started / run_completed / error` 禁止填写。 |
| `task` | object | 否 | — | 显式任务引用；与 `plan` 中的元数据冲突时报错。 |
| `payload` | object | 是 | — | 按 type 校验必填字段；无载荷类型写 `{}`。 |
| `eventId` | string | 否 | 自动生成 | 显式覆盖，用于重复 eventId 测试；格式 `^evt_[A-Za-z0-9_]{1,64}$`。 |
| `dispatchDelayMs` | integer | 否 | 0 | 产生并持久化后，延迟多少毫秒再投递给当前订阅者；范围 0–1000，用于乱序测试。 |
| `dropDispatch` | boolean | 否 | false | 持久化但不投递给当前连接，用于模拟丢投递并验证快照恢复；重连补发时正常发送。 |
| `branches` | object | 否 | — | 仅 `waiting_input` 可用；键为 option id，值为事件数组。 |

分支规则：

- 若 `waiting_input` 提供 `branches`，必须为**每个 option** 提供键（可为空数组），不得出现未知键；
- 选中选项后，自动发布 `input_received`，然后按分支数组执行；分支结束后继续顶层事件；
- 分支内事件的 `delayMs` 从 `input_received` 之后开始计算；
- 允许分支内再次出现 `waiting_input`（递归），本期脚本只需一层。
- 若未提供 `branches`，提交后直接继续顶层事件。

### 8.4 任务元数据解析

- `plan` 事件建立 `taskId → {taskId, parentTaskId, title, agentName}` 注册表。
- 后续任务事件只写 `taskId` 时，编排器自动填充 `task`。
- 显式 `task` 与注册表不一致 → 脚本校验失败（500 / `50002`）。
- 任务事件引用未在 `plan` 中声明的 `taskId` → 脚本校验失败。
- `plan` 事件自身的 `task` 为根任务；运行开始前无任务元数据。

### 8.5 噪声能力

`with-noise` 脚本必须使用以下能力组合：

| 能力 | 字段 | 要求 |
| --- | --- | --- |
| 延迟抖动 | `delayMs` | 在 0–1200ms 间不规则分布。 |
| 乱序到达 | `dispatchDelayMs` | 至少一对相邻事件：先产生的事件延迟更久，后产生的事件先到达。 |
| 重复 eventId | `eventId` | 至少 2 个事件复用已有 eventId，内容一致；消费端只应用一次。 |
| 丢投递 | `dropDispatch` | 至少 1 条非关键事件（如一条 `task_thought`）不投递给当前连接；快照中仍存在，前端应通过快照恢复补回。 |

约束：

- 乱序只影响**单次投递**，事件表中的 seq 仍按产生顺序严格递增；
- 丢投递只影响实时连接，快照与后续重连补发不受影响；
- 噪声脚本的最终任务状态必须与 `happy-path` 一致（全部成功），以便验证“最终一致”。

### 8.6 四个必需脚本

| 脚本 | 任务结构 | 关键行为 | 预期最终状态 |
| --- | --- | --- | --- |
| `happy-path.json` | 1 主 + 5 子（`t_root` / `t_research` / `t_research_web` / `t_research_docs` / `t_outline` / `t_write`） | 全成功；主任务与每个子任务都有 `task_started` 与 `task_completed`；含思考、提示词、工具调用。 | 运行 `COMPLETED`；主任务 `COMPLETED`，5 个子任务 `SUCCESS`。 |
| `with-ambiguity.json` | 1 主 + 3 子（`t_root` / `t_scope` / `t_scope_signals` / `t_draft`） | `t_scope` 出现 `waiting_input`，两个选项 `local-first` / `cloud-sync`；两条分支产生不同 summary/思考。 | 选择任一选项后运行 `COMPLETED`；主任务 `COMPLETED`，`t_scope` 成功，summary 与分支对应。 |
| `with-failure.json` | 1 主 + 4 子（`t_root` / `t_fetch` / `t_fetch_auth` / `t_parse` / `t_report`） | `t_fetch_auth` 先 `task_failed(retryable=true)`，再 `task_started` 重试，最后成功。 | 运行 `COMPLETED`；主任务 `COMPLETED`，`t_fetch_auth` 经历 `RETRYING` 后 `SUCCESS`。 |
| `with-noise.json` | 1 主 + 3 子（`t_root` / `t_a` / `t_a_sub` / `t_b`） | 重复 eventId、乱序到达、延迟抖动、丢投递；最终全部成功。 | 运行 `COMPLETED`；UI 不崩溃，主任务 `COMPLETED`、3 个子任务 `SUCCESS`，思考内容经快照补全后完整。 |

`with-ambiguity` 要求：

- 选项至少两个，脚本使用 `local-first` 与 `cloud-sync`；
- 两个分支的最终 `task_completed.summary` 必须不同；
- 选择后任务从 `WAITING_INPUT` 回到 `RUNNING`，再成功；
- 等待期间心跳持续。

`with-failure` 要求：

- 失败事件 `retryable=true`；
- 失败与重试之间 UI 可观察到 `RETRYING`（通过事件日志与状态图标）；
- 至少一次 `tool_result(status=error)` 可与失败叙事对应（非强制，但推荐）。

### 8.7 脚本校验规则

服务端在运行创建时校验，失败返回 `50002`（内置脚本问题）或 `40003`（用户传入未知脚本）：

1. `events` 非空；`type` 合法；禁止 `input_received` / `heartbeat`；
2. 每个任务的 payload 必填字段完整、类型正确；
3. `plan` 恰好一次，且位于任何任务事件之前；
4. `tasks[]` 恰好一个根；taskId 唯一；父引用存在；除根外每个任务的 `parentTaskId` 必须直接指向根（固定两层）；
5. `dependsOn` 引用的 taskId 存在；
6. 所有任务事件的 `taskId` 均在 `plan` 中；
7. `waiting_input` 选项数量 ≥2、id 唯一、label 非空；
8. 分支覆盖所有选项且无未知键；
9. `delayMs ∈ [0, 60000]`，`dispatchDelayMs ∈ [0, 1000]`；
10. `eventId` 覆盖值符合正则；允许重复（仅用于噪声），但不得与自动生成的格式冲突。

### 8.8 与 P3 的衔接

- `TaskOrchestrator` 只暴露 `start` / `submitInput`；
- 事件契约不得包含 `mockScript`、`delayMs`、`branches` 等 Mock 专有字段；
- `RunState` 的 `mockScript` 字段仅供展示，P3 可为 `null` 或 `"spring-ai"`；
- 事件产生顺序、等待输入语义、终态规则必须与真实编排器一致；
- `// P3:` 注释必须标明替换点，不得留下无阶段标记的 TODO。

---

## 9. 前端工作台需求

> 9.1–9.5 保留为早期三栏原型的交互基线；页面原型评审通过后，正式布局、创建流程、
> 计划表、日志弹窗与通知以 **9.9 正式工作台交互** 为准，两者冲突时以 9.9 为准。

### 9.1 页面布局

桌面优先，最小支持 1280×720。

```
┌────────────────────────────────────────────────────────────────────────────┐
│ TaskNest 任务巢  [脚本: happy-path] [目标______] [开始运行] [主任务库 3]   │
│ 主任务 mt_... / COMPLETED · 子任务 4/5 · 等待 1 · ● 已连接 · Run run_...  │
├──────────────────┬───────────────────────────────────────┬─────────────────┤
│ 任务树            │ 任务详情                               │ 事件日志         │
│                  │                                       │ [类型过滤 ▾]     │
│ ▾ ✓ 交付报告 主任务│ 搭报告骨架  写作员  ● 执行中           │ #42 task_...     │
│     ✓ 收集事实    │ ┌ 思考过程 ─────────────────────────┐ │ #41 tool_...     │
│     ✓ 检索资料    │ │ ...增量文本，自动滚动...           │ │ #40 ...          │
│     ⏸ 搭报告骨架  │ ├ 拼接提示词 ───────────────────────┤ │ ...              │
│     ○ 撰写正文    │ ├ 工具调用 ─────────────────────────┤ │                  │
│                  │ └ DecisionPanel（等待输入时）        │ │                  │
└──────────────────┴───────────────────────────────────────┴─────────────────┘
```

响应式：

- ≥1280px：三栏；
- 900–1279px：事件日志移动到下方抽屉/折叠区；
- <900px：不做专门适配，但不得布局错乱。

### 9.2 顶部工具栏与运行总览

- 品牌标题 `TaskNest 任务巢`。
- Mock 脚本选择器：4 个内置脚本，默认 `happy-path`。
- 目标输入框（可选）：作为 `goal` 传给 `POST /api/main-tasks`；运行中禁用。
- “开始运行 / 重新运行 / 新建主任务”按钮：
  - 无运行时可用；
  - 运行中禁用；
  - 运行终态或档案模式下可用，点击重置当前 store 并创建新主任务；
  - 请求失败时展示错误，不进入连接状态。
- “主任务库”按钮：打开 MySQL 档案抽屉，展示已持久化主任务数量。
- 连接状态指示器（FR-FE-12）：
  - 未开始 / 连接中 / 已连接 / 重连中（第 n 次）/ 已断开；
  - 展示最近 seq 与最近事件时间（可选）。
- 运行总览：主任务 ID、runId、goal、主任务状态、子任务统计（完成数/总数/等待中）、脚本名。
- 运行丢失时展示明确提示：“运行已失效（后端可能已重启）”，并提供“重新开始”；已持久化档案仍可从主任务库打开。

### 9.3 任务树

- 数据来源：store 的 `mainNode` + 扁平 `subtaskNodes`；固定两层，不递归渲染。
- 第一行是主任务，下面直接排列全部直属子任务；主任务行可折叠子任务列表。
- 节点内容：
  - 主任务行的展开/折叠箭头；子任务行不显示箭头；
  - 状态图标 + 文案；
  - `title`；
  - `agentName`（次要文本）；
  - “主任务 / 子任务”标记；
  - `WAITING_INPUT` 时显示“待选”，`RETRYING` 时显示“重试”；
  - `dependsOn` 以数量或小标签展示（仅展示，不表达调度；仅子任务）。
- 状态图标与色彩（不得只靠颜色）：

| 状态 | 适用 | 图标 | 颜色 | aria-label |
| --- | --- | --- | --- | --- |
| `PENDING` | 两者 | ○ | 中性灰 | 待执行 |
| `RUNNING` | 两者 | ◐（可脉冲） | 蓝 | 执行中 |
| `WAITING_INPUT` | 两者 | ⏸ | 琥珀 | 等待输入 |
| `RETRYING` | 子任务 | ↻ | 紫 | 重试中 |
| `SUCCESS` | 子任务 | ✓ | 绿 | 成功 |
| `COMPLETED` | 主任务 | ✓ | 绿 | 已完成 |
| `FAILED` | 两者 | ✕ | 红 | 失败 |

- 交互：
  - 点击节点：选中并展开详情；
  - 折叠/展开：仅主任务可折叠，保留用户手动状态；
  - 当新的 `waiting_input` 到达时，自动选中该任务、展开主任务并滚动可见；
  - 空状态显示“还没有主任务 / 等待规划事件…”；
  - 档案模式显示只读提示与持久化来源。
- 排序：子任务按 `plan.tasks[]` 原始顺序；未知占位节点追加在末尾。

### 9.4 任务详情

头部展示：`title`、`taskId`、`agentName`、主任务/子任务类型、对应状态徽标、成功 summary 或失败 error；
仅子任务展示 `dependsOn`。档案模式下若存在历史待办，只展示只读提示，不提供提交入口。

三个折叠面板：

#### 9.4.1 思考过程

- 将所选任务的所有 `task_thought.delta` 按 seq 顺序拼接。
- 使用等宽字体、保留换行、可滚动。
- 默认吸底自动滚动；用户向上滚动后暂停吸底，显示“回到底部”按钮。
- 无内容时显示“尚无思考过程”。
- 不得重复拼接同一事件（幂等）。

#### 9.4.2 拼接提示词

- 展示最新一条 `prompt_assembled`；
- 分四块：
  - System Prompt；
  - User Prompt；
  - Context Snippets（逐条展示 source + content，可折叠）；
  - Final Prompt（完整结果）。
- 若同一任务有多条（重试），提供“第 n 次拼接”切换；默认最新。
- 文本区域可复制（可以增强，非必须）。
- 空态：“尚无提示词”。

#### 9.4.3 工具调用

- 每次调用一行/一张卡片，展示：
  - 工具名；
  - 状态（执行中 / 成功 / 失败 / 未返回 / 孤立结果）；
  - 参数（可展开 JSON）；
  - 返回结果（可展开 JSON）；
  - 耗时 `durationMs`（有结果时）。
- 配对规则与 6.4.7 一致。
- 未配对调用在任务进入终态后显示“未返回”；孤立结果显示警告。
- 空态：“尚无工具调用”。

#### 9.4.4 抉择面板

- 触发：所选任务存在待办 `waiting_input`，或收到新的 `waiting_input` 时自动选中。
- 一个任务同时只显示一个待办；多个任务等待时：
  - 任务树上显示等待徽标；
  - 面板顶部提供任务切换（或随选中任务切换）；
  - 自动选中最新到达的等待任务。
- 内容：
  - 问题文本；
  - 选项卡片列表：label + description，单选；
  - “提交选择”按钮：未选中时禁用；提交中显示 loading；
  - “稍后决定”按钮：关闭面板，保留树徽标，可再次打开；
  - 提交失败展示 `message`，允许重试；若返回 409，触发一次快照刷新。
- 提交后不乐观更新状态，等待 `input_received`；收到后关闭面板。
- 键盘：Tab 切换选项，Enter/Space 选择，Escape 关闭（等同“稍后决定”）。
- 模态/非模态均可，但不得阻塞事件日志与连接状态。

### 9.5 事件日志

- 列：seq、type、时间（本地时区）、taskId；行可展开查看完整 JSON。
- 排序：按 seq 升序。
- 过滤：按类型多选；提供“全部”；heartbeat 默认关闭。
- 自动滚动跟随最新；用户上滚后暂停，显示“回到底部”。
- 性能：最多渲染最近 1000 条；超出时在顶部提示“仅显示最近 1000 条”（内存中仍保留全部）。
- 额外显示（非事件行）：
  - 重复 eventId 计数；
  - seq 缺口/快照恢复次数；
  - 解析失败计数。
- 运行终态后日志保留可查看。

### 9.6 连接状态指示

| 状态 | 触发 | 展示 |
| --- | --- | --- |
| `IDLE` | 页面初始 / 未创建运行 | 未开始，灰色 |
| `CONNECTING` | 首次连接中 | 连接中，蓝色 |
| `CONNECTED` | `EventSource.onopen` 或收到首个事件 | 已连接，绿色 |
| `RECONNECTING` | 断开后等待/重连中 | 重连中（第 n 次），琥珀 |
| `DISCONNECTED` | 主动停止、运行丢失、终态后关闭、重试放弃 | 已断开，红色/灰色 |

- 终态后关闭连接显示“已断开（运行已结束）”，不得显示为故障。
- 重连中展示下一次重试倒计时（可选）。
- 运行丢失展示专门错误态。

### 9.7 前端行为细则

- 开始运行：
  - 重置 store；
  - `POST /api/runs`；
  - 成功后连接 SSE `afterSeq=0`；
  - 失败展示错误并恢复按钮。
- 刷新页面：本阶段不恢复当前运行（已知限制）；用户需重新开始。
- 运行中不可开始新运行；终态后可。
- 所有时间显示本地时区，事件 timestamp 原值可在 JSON 中查看。
- 任何解析/契约异常不得导致白屏。

### 9.8 可访问性与视觉

- 状态图标必须有可读文本或 `aria-label`；
- 抉择面板焦点陷阱与键盘操作；
- 遵循 `prefers-reduced-motion`：关闭脉冲动画；
- 对比度满足 WCAG AA；
- 使用统一的颜色语义 token，不在组件中散落硬编码颜色。

### 9.9 正式工作台交互（已评审通过）

#### 9.9.1 布局

```
┌──────────────────────────────────────────────────────────────────────────┐
│ TaskNest 任务巢   主任务 3 · 运行中 1 · MySQL 档案            [深色页头]   │
├──────────────────────┬───────────────────────────────────────────────────┤
│ 主任务目录            │ 任务工作区                                         │
│ [搜索________]        │ 标题 / 状态 / 目标 / 脚本 / 计划版本 / 进度         │
│ [+ 新建主任务]        │ [查询日志]                                         │
│ ┌ 01 交付竞品分析 ┐  │ [子任务] [计划表]                                   │
│ │ 运行中 · 3/5      │ ▾ 01 收集竞品事实   成功                           │
│ ├ 02 历史档案     ┤  │   思考增量 / 提示词 / 工具调用 / 抉择面板           │
│ └ ...             ┘  │ ▸ 02 检索公开资料   执行中                         │
│ MYSQL ARCHIVE        │                                                   │
└──────────────────────┴───────────────────────────────────────────────────┘
```

- 上下布局：深色页头 + 主体；主体左右布局，左侧目录固定约 302px，右侧自适应。
- 左侧目录列出 MySQL 中已保存的主任务（`GET /api/main-tasks`），并合并当前实时运行；
  显示序号、标题、状态、实时标记、完成数/总数、计划版本与进度。
- 目录支持标题/目标即时搜索、刷新、新建入口；加载中显示骨架，失败显示可重试错误。
- 视觉继续使用浅色绘图纸工作台、深色页头与账本、信号橙 `#ee5a2a`、直角细线格；
  主任务详情区域使用不透明纸面底色，避免网格透出导致灰字模糊。

#### 9.9.2 创建主任务与计划表

- 点击“新建主任务”后，右侧切换为创建面板，不立即创建运行。
- 创建步骤固定为：**选择脚本 → 描述目标 → 生成计划表 → 提示词修订（可选）→ 确认创建**。
- 目标使用大的多行文本框（最多 2000 字），支持 `Ctrl/Meta + Enter` 生成计划表。
- 第一次解析必须调用 `POST /api/main-tasks/plan-drafts` 并强制生成 Markdown 计划表；
  未生成计划表不得确认创建。
- 计划表使用 Markdown 组件渲染（`html: false`，禁止原始 HTML）；表格、标题与修订记录
  必须可读，长内容在卡片内滚动。
- 用户可用自然语言提示修订：调用 `POST /api/main-tasks/plan-drafts/revise`，展示版本号
  与修订记录；每次修订追加一条记录，不覆盖历史。
- 确认创建调用 `POST /api/main-tasks` 并携带 `plan: {title, rows, revisions}`；成功后
  进入任务工作区并建立 SSE。

#### 9.9.3 任务工作区

- 主任务头部展示标题、主任务状态、目标、脚本、创建/更新时间、计划版本与完成进度。
- 固定两个页签：
  - **子任务**：`el-collapse` 按计划顺序展示直属子任务；点击展开思考增量、提示词
    拼接版本、工具调用表和抉择面板。
  - **计划表**：渲染已保存的 Markdown 计划、版本与修订记录；运行中可继续提示词修订。
- `waiting_input` 到达时自动展开对应子任务并滚动可见；通知点击同样定位到该子任务。
- 提交抉择后不乐观更新，等待 `input_received` 恢复；失败展示可重试错误。
- 档案模式顶部显示“只读档案”提示和“返回实时任务”，不建立 SSE、不提交抉择。
- 计划修订只更新档案中的计划表与版本；已经运行的子任务不受影响，界面必须显式说明。

#### 9.9.4 事件账本弹窗

- 入口为工作区右上角“查询日志”，以可滚动 `el-dialog` 打开，不占用主布局。
- 行展示 seq、事件类型、本地时间、任务标题与摘要；按 seq 升序。
- 默认隐藏 `heartbeat`；类型过滤为多选，选项来自当前已有事件。
- 展示解析失败数与重复 `eventId` 计数；保留重复行用于核对去重。
- 档案模式使用持久化事件原文重建账本；运行终态后账本仍可查看。

#### 9.9.5 Element Plus 通知

- 子任务 `task_completed` → 成功通知（约 4.5s 自动关闭）。
- 子任务 `task_failed(retryable=false)` → 失败通知（粘性，需手动关闭）。
- `waiting_input` → 等待抉择通知（粘性）；同一任务只保留一条。
- `input_received`、`task_completed` 对应任务、`run_completed`、`error` 时关闭对应或全部
  等待通知，避免遗留过期提醒。
- 通知按 `eventId` 去重；档案模式与快照重放不触发通知。
- 点击等待/成功通知定位到对应子任务；相邻通知错开约 420ms 创建，避免堆叠错位。

---

## 10. 数据模型

> 以下为参考设计，用于保证字段与契约一致；类名/包结构可按实现调整，但字段与语义不得偏离。

### 10.1 后端事件模型

```java
public enum EventType {
    RUN_STARTED("run_started"),
    PLAN("plan"),
    TASK_STARTED("task_started"),
    TASK_THOUGHT("task_thought"),
    PROMPT_ASSEMBLED("prompt_assembled"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    WAITING_INPUT("waiting_input"),
    INPUT_RECEIVED("input_received"),
    TASK_COMPLETED("task_completed"),
    TASK_FAILED("task_failed"),
    RUN_COMPLETED("run_completed"),
    ERROR("error"),
    HEARTBEAT("heartbeat");

    private final String wireName;
    EventType(String wireName) { this.wireName = wireName; }

    @JsonValue
    public String wireName() { return wireName; }
}

public record TaskRef(
    String taskId,
    String parentTaskId,
    String title,
    String agentName
) {}

public sealed interface EventPayload permits
    RunStartedPayload, PlanPayload, TaskThoughtPayload, PromptAssembledPayload,
    ToolCallPayload, ToolResultPayload, WaitingInputPayload, InputReceivedPayload,
    TaskCompletedPayload, TaskFailedPayload, RunCompletedPayload, ErrorPayload,
    EmptyPayload {}

public record RunStartedPayload(String goal) implements EventPayload {}
public record PlanPayload(List<PlannedTask> tasks) implements EventPayload {}
public record PlannedTask(
    String taskId, String parentTaskId, String title,
    String agentName, List<String> dependsOn
) {}
public record TaskThoughtPayload(String delta) implements EventPayload {}
public record PromptAssembledPayload(
    String systemPrompt, String userPrompt,
    List<ContextSnippet> contextSnippets, String finalPrompt
) implements EventPayload {}
public record ContextSnippet(String source, String content) {}
public record ToolCallPayload(
    String callId, String toolName, Map<String, Object> arguments
) implements EventPayload {}
public record ToolResultPayload(
    String callId, String toolName, ToolStatus status,
    Object result, long durationMs
) implements EventPayload {}
public enum ToolStatus {
    SUCCESS("success"), ERROR("error");
    private final String wireName;
    ToolStatus(String wireName) { this.wireName = wireName; }
    @JsonValue
    public String wireName() { return wireName; }
}
public record WaitingInputPayload(
    String question, List<DecisionOption> options
) implements EventPayload {}
public record DecisionOption(
    String id, String label, String description
) {}
public record InputReceivedPayload(String selectedOptionId) implements EventPayload {}
public record TaskCompletedPayload(String summary) implements EventPayload {}
public record TaskFailedPayload(String error, boolean retryable) implements EventPayload {}
public record RunCompletedPayload(String summary) implements EventPayload {}
public record ErrorPayload(String code, String message) implements EventPayload {}
public record EmptyPayload() implements EventPayload {}

public record EventEnvelope(
    String eventId,
    String runId,
    long seq,
    EventType type,
    Instant timestamp,
    TaskRef task,
    EventPayload payload
) {}
```

要求：

- 事件类型只通过 `EventType` 枚举表达，禁止裸字符串；
- payload 使用 record + sealed 层次；
- 序列化由统一的 `EventSerializer` 保证线上字段与第 6 章一致；
- 反序列化/测试必须覆盖全部 14 种类型。

### 10.2 MySQL 持久化模型

实体使用 MyBatis-Plus：每个实体一个 `BaseMapper<T>`，一个 `IService<T>` + `ServiceImpl`，
业务层只调用 `save / updateById / getOne / list / count` 等基础方法。表由
`backend/src/main/resources/schema.sql` 在启动时以 `CREATE TABLE IF NOT EXISTS` 创建。

| 表 | 实体 | 内容 |
| --- | --- | --- |
| `tn_main_task` | `MainTaskEntity` | 一条创建记录对应一个主任务与一次运行。 |
| `tn_main_task_plan` | `MainTaskPlanEntity` | 与主任务一对一的计划表、行数据与修订记录。 |
| `tn_subtask` | `SubtaskEntity` | 直属子任务；不保存更深层级。 |
| `tn_prompt` | `PromptEntity` | 每次 `prompt_assembled` 的完整提示词。 |
| `tn_task_artifact` | `TaskArtifactEntity` | 思考增量、工具调用与工具结果。 |
| `tn_event` | `RunEventEntity` | 每条事件的原始 JSON。 |

#### 10.2.1 `tn_main_task`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `main_task_id` | VARCHAR(64) UNIQUE | 对外主任务 ID，`mt_` 前缀。 |
| `run_id` | VARCHAR(64) UNIQUE | 对应运行 ID。 |
| `root_task_id` | VARCHAR(64) | `plan` 根节点 taskId。 |
| `title` / `goal` | VARCHAR(255) / TEXT | 标题与用户目标。 |
| `mock_script` | VARCHAR(64) | 演练脚本名。 |
| `status` | VARCHAR(32) | `MainTaskStatus`。 |
| `summary` / `error` | TEXT | 完成摘要与错误。 |
| `waiting_question` / `waiting_options` | TEXT | 当前待办；选项为 JSON 数组。 |
| `selected_option_id` | VARCHAR(64) | 最近一次选择。 |
| `started_at` / `completed_at` | DATETIME(3) | 开始与结束时间。 |
| `created_at` / `updated_at` | DATETIME(3) | 创建与最近更新时间。 |

#### 10.2.2 `tn_subtask`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `main_task_id` | VARCHAR(64) | 所属主任务。 |
| `task_id` | VARCHAR(64) | 子任务 ID；与主任务联合唯一。 |
| `title` / `agent_name` | VARCHAR(255) / VARCHAR(64) | 标题与执行 agent。 |
| `status` | VARCHAR(32) | `SubtaskStatus`。 |
| `sort_order` | INT | `plan` 中的顺序。 |
| `depends_on` | TEXT | 依赖 taskId 的 JSON 数组。 |
| `summary` / `error` | TEXT | 完成摘要与最近错误。 |
| `waiting_question` / `waiting_options` | TEXT | 待办问题与选项。 |
| `selected_option_id` | VARCHAR(64) | 最近一次选择。 |
| `started_at` / `completed_at` | DATETIME(3) | 开始与结束时间。 |
| `created_at` / `updated_at` | DATETIME(3) | 创建与最近更新时间。 |

#### 10.2.3 `tn_prompt`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `main_task_id` / `task_id` | VARCHAR(64) | 归属主任务与任务。 |
| `seq` | BIGINT | 对应事件序号；与主任务联合唯一。 |
| `system_prompt` / `user_prompt` / `final_prompt` | LONGTEXT | 提示词三段。 |
| `context_snippets` | LONGTEXT | 上下文片段 JSON 数组。 |
| `created_at` | DATETIME(3) | 事件时间。 |

#### 10.2.4 `tn_task_artifact`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `main_task_id` / `task_id` | VARCHAR(64) | 归属主任务与任务。 |
| `seq` | BIGINT | 对应事件序号。 |
| `kind` | VARCHAR(32) | `THOUGHT` / `TOOL_CALL` / `TOOL_RESULT`。 |
| `call_id` / `tool_name` | VARCHAR(64) / VARCHAR(128) | 工具调用配对键。 |
| `status` / `duration_ms` | VARCHAR(32) / BIGINT | 工具结果回填的成功或错误、耗时。 |
| `content` | LONGTEXT | 思考增量、调用参数或结果 JSON。 |
| `created_at` | DATETIME(3) | 事件时间。 |

工具结果优先按 `callId` 回填到同任务的未完成 `TOOL_CALL` 行；`callId` 缺失时按工具名
FIFO 匹配；找不到调用时新增 `TOOL_RESULT` 行。

#### 10.2.5 `tn_event`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `main_task_id` | VARCHAR(64) | 归属主任务。 |
| `run_id` / `event_id` | VARCHAR(64) | 运行与事件 ID。 |
| `seq` | BIGINT | 事件序号；与 `run_id` 联合唯一。 |
| `type` | VARCHAR(32) | 事件类型线上名。 |
| `task_id` | VARCHAR(64) | 事件所属任务，可为空。 |
| `event_json` | LONGTEXT | 完整事件信封 JSON，用于账本回放。 |
| `event_time` / `created_at` | DATETIME(3) | 事件时间与落库时间。 |

#### 10.2.6 `tn_main_task_plan`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `main_task_id` | VARCHAR(64) UNIQUE | 所属主任务；一个主任务只有一份当前计划。 |
| `plan_title` | VARCHAR(255) | 计划标题。 |
| `plan_goal` | TEXT | 计划目标。 |
| `plan_script` | VARCHAR(64) | 生成计划所用的 Mock 脚本。 |
| `plan_version` | INT | 当前版本；草案初始为 1，每次修订 +1。 |
| `plan_markdown` | LONGTEXT | 渲染用 Markdown 计划表，含约束与修订记录。 |
| `plan_rows` | LONGTEXT | 计划行 JSON 数组（title / agent / dependency / output）。 |
| `plan_revisions` | LONGTEXT | 修订记录 JSON 字符串数组。 |
| `created_at` / `updated_at` | DATETIME(3) | 创建与最近修订时间。 |

计划表在创建主任务时与 `tn_main_task` 同事务写入；运行中的提示词修订立即更新该行。
修订只改变计划档案，不会创建或删除 `tn_subtask` 记录。

#### 10.2.7 落库规则

- 创建主任务时先写 `tn_main_task`（`PENDING`）与 `tn_main_task_plan`，再启动编排；
  启动失败标记 `FAILED`；确认稿的版本由 `revisions.size() + 1` 决定。
- 运行中的计划提示词修订直接更新 `tn_main_task_plan` 的 Markdown、行数据、修订记录、
  版本与 `updated_at`；终态后拒绝修订，返回 `40904`。
- 每条事件先进入内存事件表并更新投影，再同步落库，最后进入 SSE 队列；落库失败只记录日志，不阻断 SSE。
- 主任务与子任务的状态事件按 `rootTaskId` 分流，分别更新两张表。
- `run_completed` / `error` 会补齐主任务终态，并把仍未结束的子任务修复为 `FAILED`。
- 档案查询只读，按 `main_task_id` 取子任务、提示词、中间结果与事件；不依赖内存 `RunStore`。
- 测试使用 H2 `MODE=MySQL`；MySQL 8 为运行环境，两者共用 `schema.sql`。

### 10.3 运行内存状态

```java
final class RunState {
    String runId;
    String mainTaskId;
    String mockScript;
    RunStatus status;                      // RUNNING / COMPLETED / FAILED
    Instant createdAt;
    Instant updatedAt;
    long seq;                              // 从 1 开始
    List<EventEnvelope> events;            // 按 seq 升序，含心跳/重复 id
    MainTaskState mainTask;                // 主任务独立状态
    Map<String, TaskNodeState> subtasks;   // 只含直属子任务
    Map<String, SseConnection> subscribers;
    WaitingInputState waitingInput;        // 本阶段 Mock 至多一个活动等待
}
```

- 事件写表、seq 分配、订阅者入队必须在同一锁内保证顺序；
- 订阅者使用每连接单一消费队列/分发器，避免慢客户端阻塞编排；
- 断连只移除订阅者，不清除 `RunState`；
- 进程重启后实时运行失效，但 `tn_*` 档案仍可查询。

### 10.4 前端 TypeScript 契约

```ts
export type EventType =
  | 'run_started' | 'plan' | 'task_started' | 'task_thought'
  | 'prompt_assembled' | 'tool_call' | 'tool_result'
  | 'waiting_input' | 'input_received'
  | 'task_completed' | 'task_failed'
  | 'run_completed' | 'error' | 'heartbeat';

export interface TaskRef {
  taskId: string;
  parentTaskId: string | null;
  title: string;
  agentName: string;
}

export interface PlannedTask extends TaskRef {
  dependsOn: string[];
}

export interface ContextSnippet { source: string; content: string; }
export interface DecisionOption { id: string; label: string; description: string; }

export interface PayloadMap {
  run_started: { goal: string };
  plan: { tasks: PlannedTask[] };
  task_started: Record<string, never>;
  task_thought: { delta: string };
  prompt_assembled: {
    systemPrompt: string;
    userPrompt: string;
    contextSnippets: ContextSnippet[];
    finalPrompt: string;
  };
  tool_call: { callId?: string; toolName: string; arguments: Record<string, unknown> };
  tool_result: {
    callId?: string;
    toolName: string;
    status: 'success' | 'error';
    result: unknown;
    durationMs: number;
  };
  waiting_input: { question: string; options: DecisionOption[] };
  input_received: { selectedOptionId: string };
  task_completed: { summary: string };
  task_failed: { error: string; retryable: boolean };
  run_completed: { summary: string };
  error: { code: string; message: string };
  heartbeat: Record<string, never>;
}

export interface BaseEvent<K extends EventType> {
  eventId: string;
  runId: string;
  seq: number;
  type: K;
  timestamp: string;
  task: TaskRef | null;
  payload: PayloadMap[K];
}

export type TaskNestEvent = { [K in EventType]: BaseEvent<K> }[EventType];

export type MainTaskStatus =
  | 'PENDING' | 'RUNNING' | 'WAITING_INPUT' | 'COMPLETED' | 'FAILED';

export type SubtaskStatus =
  | 'PENDING' | 'RUNNING' | 'WAITING_INPUT'
  | 'RETRYING' | 'SUCCESS' | 'FAILED';

export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export type ConnectionState =
  | 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED';

export interface WaitingInputSnapshot {
  question: string;
  options: DecisionOption[];
}

export interface MainTaskSnapshot {
  mainTaskId: string;
  taskId: string;
  title: string;
  agentName: string;
  status: MainTaskStatus;
  summary: string | null;
  error: string | null;
  selectedOptionId: string | null;
  waitingInput: WaitingInputSnapshot | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface SubtaskSnapshot {
  taskId: string;
  title: string;
  agentName: string;
  status: SubtaskStatus;
  summary: string | null;
  error: string | null;
  dependsOn: string[];
  order: number;
  selectedOptionId: string | null;
  waitingInput: WaitingInputSnapshot | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface PlanRowView {
  title: string;
  agent: string;
  dependency: string;
  output: string;
}

export interface PlanDraftView {
  mainTaskId: string | null;
  title: string;
  goal: string;
  mockScript: string;
  version: number;
  rows: PlanRowView[];
  revisions: string[];
  markdown: string;
}

export interface RunSnapshot {
  run: {
    runId: string;
    mainTaskId: string;
    mockScript: string | null;
    goal: string | null;
    status: RunStatus;
    createdAt: string;
    updatedAt: string;
    lastSeq: number;
  };
  mainTask: MainTaskSnapshot;
  subtasks: SubtaskSnapshot[];
  events: TaskNestEvent[];
}

export interface MainTaskDetail {
  mainTask: MainTaskSummary;
  plan: PlanDraftView | null;
  subtasks: SubtaskView[];
  prompts: PromptView[];
  artifacts: ArtifactView[];
  events: unknown[];
  lastSeq: number;
}
```

- 开启 `strict: true`；
- 禁止把 `payload` 声明为 `any`；解析入口使用 `unknown` 并做字段校验。

### 10.5 前端 store 状态

```ts
interface ToolCallView {
  callId?: string;
  toolName: string;
  arguments: Record<string, unknown>;
  status: 'RUNNING' | 'SUCCESS' | 'ERROR' | 'NO_RESULT' | 'ORPHAN';
  result?: unknown;
  durationMs?: number;
}

interface BaseNodeView {
  taskId: string;
  title: string;
  agentName: string;
  thought: string;
  prompts: PayloadMap['prompt_assembled'][];
  toolCalls: ToolCallView[];
  waiting: PayloadMap['waiting_input'] | null;
  lastDecisionId: string | null;
  summary: string | null;
  error: string | null;
  selectedOptionId: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

interface MainNodeView extends BaseNodeView {
  kind: 'MAIN';
  mainTaskId: string;
  status: MainTaskStatus;
}

interface SubtaskNodeView extends BaseNodeView {
  kind: 'SUBTASK';
  parentTaskId: string;
  status: SubtaskStatus;
  dependsOn: string[];
  order: number;
}

interface TaskTreeState {
  runId: string | null;
  mainTaskId: string | null;
  mockScript: string | null;
  goal: string | null;
  runStatus: RunStatus | null;
  runTerminal: boolean;
  mainNode: MainNodeView | null;
  subtaskNodes: Record<string, SubtaskNodeView>;
  selectedTaskId: string | null;
  collapsed: Record<string, true>;
  events: TaskNestEvent[];
  seen: Record<string, true>;
  lastSeq: number;
  connection: ConnectionState;
  reconnectAttempt: number;
  buffered: Record<number, TaskNestEvent>;
  archiveMode: boolean;
  archiveLoading: boolean;
  archiveError: string | null;
  library: MainTaskSummary[];
  libraryLoading: boolean;
  libraryError: string | null;
  libraryOpen: boolean;
  plan: PlanDraftView | null;
  planError: string | null;
  planRevising: boolean;
  mainCreatedAt: string | null;
  mainUpdatedAt: string | null;
}
```

`startRun(script, goal, planDraft?)` 在创建成功时写入 `plan` 与时间戳；`revisePlan(instruction)`
只在存在 `mainTaskId` 时调用已保存计划修订接口，成功后替换 `plan`。打开档案时
`plan` 来自 `MainTaskDetail.plan`，档案模式下不允许修订。

### 10.6 事件处理算法（store 内部）

**接收事件：**

```text
receive(event):
  1. 校验信封（必需字段、seq 为正整数、type 已知）
     失败 → parseErrorCount++，记录日志，触发缺口检查，返回
  2. 若 recovering：放入缓冲，返回
  3. 若 event.seq <= lastSeq：
       若已应用 eventId → duplicateCount++；否则按重复/过期处理
       返回
  4. 若 event.seq > lastSeq + 1：
       缓冲 event（同 seq 保留先到者）
       安排 2s 缺口检查
       返回
  5. 按序应用：
       applyOne(event)
       然后循环：只要缓冲中存在 lastSeq+1，取出、applyOne，直到无连续事件
  6. 无缺口时取消缺口定时器
```

**applyOne(event)：**

```text
if appliedEventIds[event.eventId]:
    duplicateCount++
    记录警告
else:
    appliedEventIds[event.eventId] = true
    switch (event.type) { ... 按第 6.6 节状态机与详情规则 ... }
lastSeq = max(lastSeq, event.seq)
```

关键：**重复 eventId 的语义跳过不能阻塞 seq 游标**（FR-EVT-05）。

**缺口检查（2s）：**

```text
if 仍存在 gap（lastSeq+1 不在缓冲）:
  gapRecoveryCount++
  await recoverFromSnapshot()
```

**快照恢复：**

```text
recovering = true
snapshot = await GET /api/runs/{runId}   // 失败：退避重试 3 次，仍失败则显示错误
reset 事件派生状态（保留 runId、连接与正在缓冲的实时事件）
for event in snapshot.events sorted by seq:
    applyOne(event)
lastSeq = max(lastSeq, snapshot.run.lastSeq)
reconcileTasks(snapshot.mainTask, snapshot.subtasks)   // 服务端状态为准
if snapshot.run.status != RUNNING:
    repairNonTerminalTasks()
for event in snapshot.events 与实时缓冲中 seq > lastSeq 的事件，按 seq 排序:
    应用一次（eventId 幂等）
清空 <= lastSeq 的缓冲
recovering = false
if SSE 已断开: reconnect(afterSeq=lastSeq)
```

**注意**：快照恢复期间保持当前 SSE 连接（若仍连接），实时事件只入缓冲，不直接应用。

**重连流程：**

```text
onSseError:
  es.close()
  if runStatus in [COMPLETED, FAILED]: connection = DISCONNECTED; return
  connection = RECONNECTING
  attempt++
  delay = min(30s, 1s * 2^(attempt-1)) * (1 + random*0.2)
  after delay:
      snapshot = GET /api/runs/{runId}
        404 → connection = DISCONNECTED, errorMessage = "运行已失效", 停止
        其他失败 → 按重连退避继续
      rebuildFromSnapshot(snapshot)
      connectSse(runId, afterSeq = lastSeq)
```

**初始连接：** 创建主任务成功后直接 `connectSse(runId, 0)`，无需先拉快照。

**打开主任务档案：**

```text
openMainTask(mainTaskId):
  archiveRequest++（丢弃过期响应）
  停止 SSE、清空定时器与实时投影
  detail = await GET /api/main-tasks/{mainTaskId}
      → 填充 mainTask / subtasks / prompts / artifacts
      → 按 seq 重放 detail.events（parseEventValue 逐条校验，坏事件只记录）
      → 再用 detail.mainTask 与 detail.subtasks 覆盖权威状态
  archiveMode = true，runTerminal = true
  禁止提交抉择与模拟断线，不建立 SSE
```

### 10.7 生命周期与清理

- 组件卸载：`sse.stop()`，清空所有定时器；
- 开始新运行：退出档案模式、停止旧客户端、重置 store、断开旧连接；
- 运行终态：停止重连、关闭连接；
- 快照 404：停止重连并展示“运行已失效”；
- 档案模式：只读、不重连、不提交、不启动编排；退出后回到实时工作台。

---

## 11. 非功能需求

| 编号 | 类别 | 要求 |
| --- | --- | --- |
| NFR-01 | 性能 | 本机环境下，创建运行接口 P95 < 200ms；1,000 条历史事件补发 < 500ms；事件到达至 UI 更新 < 100ms（心跳除外）。 |
| NFR-02 | 可靠性 | 断线重连后事件不丢、不重复应用；乱序/重复/丢投递下最终状态一致；SSE 连接 1 小时不因空闲断开。 |
| NFR-03 | 容量 | 单实例支持 ≥10 个并发运行、每运行 ≥10 个订阅者、每运行 ≥10,000 条事件。 |
| NFR-04 | 兼容性 | Java 17+、Spring Boot 3.x、Maven；Node 20+、Vite、Vue 3、TypeScript strict；Chrome/Edge 最新版。 |
| NFR-05 | 安全 | 默认绑定 `127.0.0.1`；CORS 仅白名单开发源；脚本名允许列表 + 正则校验，禁止路径穿越；不提供文件/命令执行接口；无鉴权是本期明确接受的约束。 |
| NFR-06 | 可观测性 | 后端日志包含 `runId`、`seq`、`type`、连接生命周期；前端控制台记录重复/缺口/解析失败；UI 事件日志可调试。 |
| NFR-07 | 可维护性 | 契约单一来源；事件类型枚举化；Controller 无业务逻辑；关键逻辑有测试；无裸字符串事件类型。 |
| NFR-08 | 可访问性 | 键盘可操作、aria 标签、对比度 AA、减少动效支持。 |
| NFR-09 | 禁止项 | 运行时不得发起任何模型/外部 AI 请求；不得引入 Redis、消息队列或微服务拆分。MySQL 仅用于主任务档案持久化。 |
| NFR-10 | 状态与持久化 | 实时运行与 SSE 游标仍在内存，后端重启后不能续传，前端必须优雅处理 404；主任务、计划表、子任务、提示词、中间结果与事件原文写入 MySQL，可在主任务目录中只读查询。 |

---

## 12. 测试与验收

### 12.1 后端测试（`mvn test`）

必须覆盖：

| 测试 | 内容 |
| --- | --- |
| 事件序列化 | 14 种事件逐字段序列化/反序列化；枚举输出小写下划线；空 payload 为 `{}`；`task` 为 null 的情况。 |
| seq 单调性 | 并发发布 N 条事件后，seq 集合恰为 1..N，无重复、无空洞；事件表按 seq 升序。 |
| eventId | 默认生成规则；显式覆盖；重复 eventId 允许写入但不影响 seq。 |
| 补发 | `afterSeq=N` 只返回 seq > N；N=lastSeq 时只接实时；非法 N 返回 400。 |
| 心跳 | 连接后立即收到；间隔约 15s；占用 seq；无订阅者时不产生；终态后连接关闭。 |
| 抉择 | 正常提交 → 202 + `input_received` + 分支继续；错误任务/选项/状态 → 404/409；重复提交 → 409。 |
| 快照 | `run + mainTask + subtasks + events`；两层结构；无 plan 时主任务仍为 `PENDING`；终态修复。 |
| Mock 脚本 | 4 个脚本均可解析并通过校验；更深层级或第二个根节点报错；任务元数据自动补全。 |
| 断连 | 客户端断开后编排继续，事件仍可补发。 |
| 包装体 | 所有非 SSE 响应为 `{code, message, data}`；异常被统一处理。 |
| 持久化 | 创建主任务后写入 `tn_main_task`；`plan` 写入直属子任务；`prompt_assembled` / 思考 / 工具调用写入对应表；事件原文写入 `tn_event`。 |
| 档案查询 | `GET /api/main-tasks` 按创建时间倒序；`GET /api/main-tasks/{id}` 返回主任务、计划表、子任务、提示词、中间结果与事件；未知 ID → 40403。 |
| 计划草案 | `plan-drafts` 从 Mock `plan` 生成 Markdown 表格、5 行、版本 1；依赖文本正确；未知脚本 → 40003。 |
| 计划修订与持久化 | 草案修订后版本 +1 且追加行；携带 `plan` 创建后详情/列表暴露正确版本；运行中可修订已保存计划；终态后 → 40904。 |
| 修订语义 | 修订计划后实际子任务数量不变。 |
| 两层校验 | happy-path 产生 1 个主任务 + 5 个直属子任务；深层级脚本加载失败。 |

SSE 集成测试可以使用 `MockMvc` 异步请求、`WebTestClient` 或直接对 `SseService` 做单元测试；至少必须覆盖事件序列化与 seq 单调性（`todo.md` 的底线）。

### 12.2 前端检查

- `npm run build` 必须通过（`vue-tsc` / tsc 严格模式）；
- `npm run lint` 必须通过（ESLint）；
- 应当使用 Vitest 覆盖 store 的纯函数 reducer：
  - 重复 eventId；
  - 乱序 → 缓冲 → 补齐；
  - 缺口 → 快照恢复（mock API）；
  - thought 增量拼接；
  - waiting_input / input_received；
  - 终态修复；
  - 快照 + 增量去重；
  - 主任务/子任务双状态投影；
  - 档案回放只读、不建立 SSE。

### 12.3 端到端验收清单

| 编号 | 对应 todo 验收项 | 步骤 | 预期 |
| --- | --- | --- | --- |
| AT-01 | curl 触发后收到完整事件流含 heartbeat | `POST /api/runs` 取 runId；`curl -N /events?afterSeq=0` | 收到 run_started → plan → … → run_completed；至少 1 条 heartbeat；字段完整；终态后连接关闭。 |
| AT-02 | 4 个脚本可运行，waiting_input 提交后继续 | 依次运行 4 个脚本；对 ambiguity 提交 `local-first`，再开一次提交 `cloud-sync` | 无异常；waiting 状态可观测；提交后任务继续；两个分支 summary 不同。 |
| AT-03 | 仅凭事件流还原两层任务结构 | 运行 happy-path，观察树 | 1 个主任务 + 5 个直属子任务；主任务状态 PENDING → RUNNING → COMPLETED，子任务 PENDING → RUNNING → SUCCESS；图标与文案正确。 |
| AT-04 | 思考增量拼接与提示词三段展示 | 运行 happy-path，选中任务 | thought 为多条 delta 拼接；prompt 的 system/user/context/final 完整；工具调用配对并显示耗时。 |
| AT-05 | kill SSE 后自动重连、afterSeq 续传、无重复丢失 | 运行中关闭连接（DevTools 离线 3s 或关闭 curl 后再连） | 自动重连；先拉快照；从 lastSeq 续传；树无重复节点；thought 无重复；最终状态正确。 |
| AT-06 | with-noise 下 UI 不崩、最终一致 | 运行 with-noise，立即连 SSE | 重复计数 >0；发生乱序缓冲；发生至少 1 次缺口/快照恢复；最终主任务 COMPLETED、子任务全部 SUCCESS；thought 经快照补全；无白屏。 |
| AT-07 | docs/events.md 与前后端一致 | 对照检查 14 种类型、字段、错误码、状态机 | 三处一致；无遗漏、无多余。 |
| AT-08 | 全程无真实模型调用 | 检查 pom/package 依赖与运行日志 | 无 Spring AI / 模型 SDK / 外部 AI 请求；只有 MyBatis-Plus 的 MySQL 访问。 |
| AT-09 | 运行丢失处理（补充） | 启动运行后重启后端，刷新/等待重连 | 实时快照 404；前端提示“运行已失效”；停止重连；不白屏。 |
| AT-10 | MySQL 持久化与档案查询 | 创建多个主任务（含 goal）；在左侧目录中打开历史条目；查询详情 | 列表含多个主任务与计划版本；详情含计划表、直属子任务、提示词、思考/工具调用与事件；打开档案为只读，不重新编排；重启后端后档案仍可打开。 |
| AT-11 | 计划表创建与修订 | 新建主任务：描述目标 → 生成计划表 → 提示词增加风险复核 → 确认创建；打开日志弹窗；运行中再修订计划 | 未生成计划表不能确认；Markdown 表格正常渲染；确认后计划版本与修订记录落库；子任务不受修订影响；终态后修订提示只读。 |
| AT-12 | 通知与日志弹窗 | 分别运行 with-ambiguity、with-failure、happy-path；观察通知与「查询日志」 | 等待抉择/失败通知为粘性，成功通知自动关闭；点击通知定位子任务；提交后等待通知关闭；日志弹窗默认隐藏 heartbeat，支持多选过滤与滚动。 |

### 12.4 脚本测试矩阵

| 脚本 | 等待输入 | 重试 | 噪声 | 断线恢复 | 终态 |
| --- | --- | --- | --- | --- | --- |
| `happy-path` | 否 | 否 | 否 | 验证 | 全部 SUCCESS / COMPLETED |
| `with-ambiguity` | 是（2 选项） | 否 | 否 | 验证 | 全部 SUCCESS / COMPLETED |
| `with-failure` | 否 | 是（retryable） | 否 | 验证 | 全部 SUCCESS / COMPLETED |
| `with-noise` | 否 | 否 | 是 | 必须验证 | 全部 SUCCESS / COMPLETED |

### 12.5 验收补充规则

- 心跳在 happy-path 中可能很快结束；由于连接后立即发送一次心跳，AT-01 仍可验证。
- 观察 `RETRYING` 时，事件日志应能看到 `task_failed(retryable=true)`，且随后出现同一任务的 `task_started`。
- 噪声场景的“最终一致”以刷新后拉快照仍得到相同的主任务与子任务状态为准。

---

## 13. 交付物与完成定义

### 13.1 交付物

```text
task-nest/
  CLAUDE.md                      项目约定（命令、规范、契约同步要求）
  docs/
    events.md                    人工可读事件契约（与第 6 章同步）
    requirements.md              本文件
  backend/
    pom.xml                      artifactId: task-nest-backend
    src/main/java/com/tasknest/
      TaskNestApplication.java
      common/                    ApiResponse、ErrorCode、全局异常处理
      config/                    CORS、SSE/线程池配置
      event/                     事件模型、枚举、序列化、EventPublisher
      store/                     运行状态、事件表、任务投影、快照
      sse/                       SseService、连接管理、心跳、补发
      orchestrator/              TaskOrchestrator、MockTaskOrchestrator、脚本模型
      persistence/               MyBatis-Plus 实体 / Mapper / Service、事件落库、档案查询
      api/                       控制器与 DTO
    src/main/resources/
      application.yml
      schema.sql                 MySQL / H2 共用建表脚本（tn_ 前缀）
      mock/
        happy-path.json
        with-ambiguity.json
        with-failure.json
        with-noise.json
    src/test/java/com/tasknest/ 单元/集成测试（H2，MODE=MySQL）
  frontend/
    src/
      api/                       events.ts、client.ts、sse.ts
      stores/                    taskTree.ts
      components/                TaskTree、TaskDetail、DecisionPanel、EventLog、
                                 ConnectionStatus、StatusMark、MainTaskLibrary
      views/                     WorkbenchView.vue
      App.vue、main.ts
    package.json、vite.config.ts、tsconfig*.json、eslint 配置
```

### 13.2 完成定义（DoD）

- [ ] 第 12.3 节 AT-01 ~ AT-10 全部通过；
- [ ] `mvn test` 通过，覆盖事件序列化、seq 单调性、两层投影与持久化档案；
- [ ] `npm run build` 与 `npm run lint` 通过；
- [ ] `docs/events.md` 与 Java 枚举 / TS 类型 / 本文第 6 章一致；
- [ ] 4 个 Mock 脚本齐全且可通过校验；
- [ ] 没有真实模型调用、Redis、消息队列或微服务拆分；MySQL 仅用于档案持久化；
- [ ] 主任务与子任务使用独立状态枚举，前端分别显示标签；
- [ ] `tn_main_task`、`tn_subtask`、`tn_prompt`、`tn_task_artifact`、`tn_event` 均可查询到数据；
- [ ] 没有无阶段标记的 TODO 空壳代码；
- [ ] 后端构造器注入、控制器只做校验与转发；
- [ ] 前端所有业务状态变更集中在 store；
- [ ] 断线、乱序、重复、丢投递场景均验证通过。

---

## 14. 设计决策、风险与待确认问题

### 14.1 已决设计决策（评审时请确认）

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D-01 | heartbeat 是正式事件：进入事件表、占用 seq、连接后立即发送一次、之后每 15s；仅在有订阅者时产生。 | 保证 `afterSeq` 续传不会因心跳造成误判缺口；立即心跳让“含 heartbeat”验收稳定可测。 |
| D-02 | `plan.tasks[]` 包含根任务；每份脚本恰好一个根，其余任务必须直接挂在根下；根任务映射主任务，根任务也发送自己的 `task_started` / `task_completed`。 | 固定两层结构，主任务状态可由事件完整投影且不做隐式聚合；更深层级在脚本加载阶段拒绝。 |
| D-03 | `tool_call` / `tool_result` 增加可选 `callId`；缺省时按 `taskId + toolName` FIFO 配对。 | 解决同名工具并发调用的配对歧义，同时向后兼容 `todo.md` 的最小字段集。 |
| D-04 | 断线重连采用“先拉快照 → 再以 `afterSeq` 续传”；活跃连接中的 seq 缺口则直接拉快照。 | 同时满足 `todo.md` 5.2 的“先重建再增量”和 3.4 的续传要求，覆盖事件丢失场景。 |
| D-05 | `run_completed` → 运行 `COMPLETED`（即使有任务失败）；`error` → 运行 `FAILED`。 | 区分“运行流程结束”和“系统级失败”；任务失败是任务级信息。 |
| D-06 | 前端增加脚本选择器与“开始运行”按钮。 | 工作台需要可操作的入口；Mock 脚本是本期唯一运行方式。 |
| D-07 | 运行终态后 SSE 冲刷完即关闭，前端不再重连。 | 避免无意义的长连接与重连循环。 |
| D-08 | 重复 eventId 只跳过语义，不阻塞 seq 游标；未知类型同样推进游标。 | 防止幂等去重导致永久缺口和反复快照。 |
| D-09 | 乱序缓冲窗口 2s；噪声脚本 `dispatchDelayMs` 上限 1s。 | 常规乱序可在窗口内补齐；真实丢失在 2s 后触发快照恢复。 |
| D-10 | `with-noise` 增加 `dropDispatch`，模拟“持久化成功但实时投递丢失”，强制验证快照恢复。 | `todo.md` 要求“seq 缺口时主动拉快照”，该能力使此路径可自动化验证。 |
| D-11 | 服务端在第一次 `plan` 前不创建子任务节点；快照始终有 `mainTask`（`PENDING`），`subtasks` 可为空数组。 | 主任务在创建时已存在，子任务随 `plan` 出现；前端已有空态。 |
| D-12 | 主任务与子任务使用两套状态枚举，分别持久化到 `tn_main_task` / `tn_subtask`。 | 状态语义不同（主任务无重试、子任务无 COMPLETED），分开处理避免前端与快照产生歧义。 |
| D-13 | MySQL 使用 MyBatis-Plus 的 `BaseMapper` + `IService` / `ServiceImpl` 基础方法，不写复杂 XML。 | 满足基础实体管理需求，保持实现轻量。 |

### 14.2 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 浏览器原生 `EventSource` 自动重连丢失 `afterSeq` | 重连后重复/丢失事件 | 出错时主动 `close()`，自建退避与 URL 参数（FR-CLIENT-08）。 |
| 代理/压缩缓冲 SSE | 事件延迟到达，疑似“卡住” | `X-Accel-Buffering: no`、`Cache-Control: no-cache`；Vite 代理验证；禁用该路径压缩。 |
| SSE 补发与实时交接竞态 | 交接处重复或丢失 | 先订阅入队、再读取历史，交接按 seq 去重；测试覆盖。 |
| 1 小时超时 | 长任务连接中断 | 前端自动重连并续传；超时清理不影响编排。 |
| 内存增长 | 长运行事件累积 | 本期容量上限 10k/运行；事件同步落库；UI 日志仅渲染最近 1000 条。 |
| 后端重启 | 实时运行丢失，前端重连风暴 | 快照 404 后停止重连并提示（AT-09）；已落库主任务仍可从主任务库只读打开。 |
| 数据库不可用 | 事件落库失败 | 持久化异常只记录日志，不阻断 SSE 与前端展示；创建主任务前的落库失败直接返回错误。 |
| 主任务/子任务状态混用 | 快照与 UI 状态语义不一致 | 两套枚举、两张表、前端 `nodeStatusLabel(kind, status)` 分开渲染。 |
| 重复 eventId 与乱序叠加 | 游标卡死、反复快照 | D-08 规则：重复跳过语义但推进 seq；store 测试覆盖。 |
| 父子状态规则不一致 | 前后端树显示不同 | 根任务显式事件；快照状态权威；共享状态机测试用例。 |
| 多标签页/多订阅者 | 心跳重复、事件重复 | 心跳按运行共享节拍；每个订阅者独立队列；客户端按 eventId 幂等。 |
| 脚本路径穿越 | 读取任意文件 | 允许列表 + 脚本名正则 + 固定 classpath 前缀。 |
| 时间戳不可信/时钟回拨 | 排序错误 | 只用 seq 排序；timestamp 仅展示。 |

### 14.3 待确认问题

| 编号 | 问题 | 默认方案 |
| --- | --- | --- |
| Q-01 | 是否需要在刷新页面后恢复当前运行？ | 本期不恢复；后续可用 `sessionStorage` 保存 runId，并支持 URL 直达。 |
| Q-02 | 是否需要“取消运行”接口？ | 本期不提供；`todo.md` 未列，避免扩大范围。 |
| Q-03 | 事件日志是否需要导出？ | 本期不提供；可复制单条 JSON。 |
| Q-04 | 是否使用 Vite 代理还是后端 CORS 直连？ | 推荐 Vite 代理，同时后端保留白名单 CORS。 |
| Q-05 | 是否把 `callId` 写入 `docs/events.md` 的必填字段？ | 作为可选扩展字段；实现 P3 时若工具框架提供调用 ID，再考虑升级为必填。 |

---

## 15. 附录

### 15.1 完整事件序列示例（happy-path 节选）

```text
seq  type               task              payload 摘要
1    run_started        null              goal="为面向开发者的 AI 编程助手..."
2    plan               t_root            tasks=[t_root, t_research, t_research_web,
                                                 t_research_docs, t_outline, t_write]
3    task_started       t_root            {}
4    task_started       t_research        {}
5    task_thought       t_research        delta="先确认..."
6    prompt_assembled   t_research        system/user/context/final
7    tool_call          t_research        toolName="search_web", callId="call_001"
8    tool_result        t_research        status="success", durationMs=120
9    task_started       t_research_web    {}
10   ...
N-6  task_completed     t_research_web    summary="抓到 8 条结果..."
N-5  task_completed     t_research_docs   summary="核对了两家定价页..."
N-4  task_completed     t_research        summary="三类事实已收齐..."
N-3  task_completed     t_outline         summary="骨架定稿..."
N-2  task_completed     t_write           summary="一页纸正文完成..."
N-1  task_completed     t_root            summary="报告已交付..."
N    run_completed      null              summary="竞品分析一页纸已完成..."
（heartbeat 可在任意位置插入，占用 seq）
```

### 15.2 Mock 脚本示例

#### 15.2.1 happy-path（节选）

```json
{
  "description": "两层任务树顺利执行：规划、检索、写作、汇总，全程无阻塞。",
  "events": [
    {
      "delayMs": 0,
      "type": "run_started",
      "payload": { "goal": "为面向开发者的 AI 编程助手产出一页竞品分析，并给出差异化建议。" }
    },
    {
      "delayMs": 250,
      "type": "plan",
      "payload": {
        "tasks": [
          { "taskId": "t_root", "parentTaskId": null, "title": "交付竞品分析报告", "agentName": "协调者", "dependsOn": [] },
          { "taskId": "t_research", "parentTaskId": "t_root", "title": "收集竞品事实", "agentName": "研究员", "dependsOn": [] },
          { "taskId": "t_research_web", "parentTaskId": "t_root", "title": "检索公开资料", "agentName": "检索员", "dependsOn": [] },
          { "taskId": "t_research_docs", "parentTaskId": "t_root", "title": "核对官方文档", "agentName": "研究员", "dependsOn": [] },
          { "taskId": "t_outline", "parentTaskId": "t_root", "title": "搭报告骨架", "agentName": "写作员", "dependsOn": ["t_research"] },
          { "taskId": "t_write", "parentTaskId": "t_root", "title": "撰写并校对正文", "agentName": "写作员", "dependsOn": ["t_outline"] }
        ]
      }
    },
    {
      "delayMs": 200,
      "type": "task_started",
      "taskId": "t_root",
      "payload": {}
    },
    {
      "delayMs": 200,
      "type": "task_started",
      "taskId": "t_research",
      "payload": {}
    },
    {
      "delayMs": 400,
      "type": "task_thought",
      "taskId": "t_research",
      "payload": { "delta": "先确认竞品的定价与核心能力，再补公开评价。" }
    },
    {
      "delayMs": 300,
      "type": "prompt_assembled",
      "taskId": "t_research",
      "payload": {
        "systemPrompt": "你是一名接口文档专家。",
        "userPrompt": "请扫描并抽取以下服务的接口。",
        "contextSnippets": [
          { "source": "file:src/main/java/.../OrderController.java", "content": "@RestController ..." }
        ],
        "finalPrompt": "系统：你是一名接口文档专家。\n用户：请扫描并抽取以下服务的接口。\n上下文：..."
      }
    },
    {
      "delayMs": 300,
      "type": "tool_call",
      "taskId": "t_research",
      "payload": {
        "callId": "call_001",
        "toolName": "search_web",
        "arguments": { "query": "AI coding assistant pricing" }
      }
    },
    {
      "delayMs": 500,
      "type": "tool_result",
      "taskId": "t_research",
      "payload": {
        "callId": "call_001",
        "toolName": "search_web",
        "status": "success",
        "result": { "sources": 8 },
        "durationMs": 120
      }
    },
    {
      "delayMs": 200,
      "type": "task_completed",
      "taskId": "t_research",
      "payload": { "summary": "三类事实已收齐，并保留了一手来源链接。" }
    }
  ]
}
```

> 实际交付脚本必须完整覆盖 `plan` 中 1 个主任务与全部 5 个直属子任务的开始、详情与完成事件，并包含 `run_completed`；上例仅为结构说明。

#### 15.2.2 with-ambiguity 分支示例

```json
{
  "delayMs": 400,
  "type": "waiting_input",
  "taskId": "t_scope",
  "payload": {
    "question": "首发版本的缓存策略选哪条？",
    "options": [
      { "id": "local-first", "label": "本地优先", "description": "实现快、无账号依赖；多设备之间需要手动迁移缓存。" },
      { "id": "cloud-sync", "label": "云端同步", "description": "多设备自动同步；需要账号与网络。" }
    ]
  },
  "branches": {
    "local-first": [
      { "delayMs": 300, "type": "task_thought", "taskId": "t_scope", "payload": { "delta": "选定本地优先，冲突信号简化为 mtime 落后。" } },
      { "delayMs": 400, "type": "task_completed", "taskId": "t_scope", "payload": { "summary": "已按本地优先方案确定缓存策略。" } }
    ],
    "cloud-sync": [
      { "delayMs": 300, "type": "task_thought", "taskId": "t_scope", "payload": { "delta": "选定云端同步，需要补齐账号与冲突合并。" } },
      { "delayMs": 400, "type": "task_completed", "taskId": "t_scope", "payload": { "summary": "已按云端同步方案确定缓存策略。" } }
    ]
  }
}
```

#### 15.2.3 with-failure 重试示例

```json
[
  { "delayMs": 300, "type": "task_started", "taskId": "t_fetch_auth", "payload": {} },
  { "delayMs": 400, "type": "task_failed", "taskId": "t_fetch_auth", "payload": { "error": "OAuth 刷新令牌已过期，无法读取仓库。", "retryable": true } },
  { "delayMs": 600, "type": "task_started", "taskId": "t_fetch_auth", "payload": {} },
  { "delayMs": 400, "type": "task_completed", "taskId": "t_fetch_auth", "payload": { "summary": "凭据刷新后重新读取成功。" } }
]
```

#### 15.2.4 with-noise 噪声示例

```json
[
  {
    "delayMs": 100,
    "type": "task_thought",
    "taskId": "t_a",
    "eventId": "evt_dup_thought",
    "dispatchDelayMs": 900,
    "payload": { "delta": "这一段会晚到。" }
  },
  {
    "delayMs": 100,
    "type": "task_thought",
    "taskId": "t_a",
    "eventId": "evt_dup_thought",
    "dispatchDelayMs": 0,
    "payload": { "delta": "这一段会晚到。" }
  },
  {
    "delayMs": 200,
    "type": "task_thought",
    "taskId": "t_a",
    "dropDispatch": true,
    "payload": { "delta": "这一段实时投递丢失，需靠快照补回。" }
  }
]
```

> 上例演示字段用法；实际噪声脚本在两层结构中分布这些能力，并确保最终成功。

### 15.3 `todo.md` 与本文档映射

| `todo.md` 章节 | 本文档 |
| --- | --- |
| 一、本阶段范围锁 | 第 3 章 |
| 二、目录结构 | 第 13.1 节 |
| 三、SSE 事件契约 | 第 6 章、第 7.4 节 |
| 四、后端任务 | 第 5.1–5.5 节、第 7–8 章、第 10.1–10.3 节 |
| 五、前端任务 | 第 5.6–5.8 节、第 9 章、第 10.4–10.7 节 |
| 六、编码规范 | 第 11 章、第 13.2 节 |
| 七、验收标准 | 第 12 章 |
| 八、后续阶段 | 第 3.3 节、第 8.8 节 |

---

**文档结束。** 评审通过后，建议按以下顺序落地：`docs/events.md` → 后端事件模型与 SSE 骨架 → Mock 脚本 → 前端 store → 前端组件 → 端到端验收。
