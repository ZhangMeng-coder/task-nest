# TaskNest 详细需求文档（P3 软件开发 Agent 平台）

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
| 文档版本 | v2.0 |
| 日期 | 2026-10-22 |
| 依据 | `todo.md` v1.0 + MySQL 持久化 + P3 真实 Agent 执行 |
| 状态 | 已实现 |
| 适用阶段 | P3（软件开发 Agent 平台，含 P0–P2 展示层与 MySQL 持久化） |
| 读者 | 前端、后端、测试、评审人 |
| 契约同步文件 | `docs/events.md`（与本文第 6 章逐字段一致） |

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [用户与使用场景](#2-用户与使用场景)
3. [阶段范围与边界](#3-阶段范围与边界)
4. [总体设计](#4-总体设计)
5. [功能需求总表](#5-功能需求总表)
6. [事件契约（核心）](#6-事件契约核心)
7. [REST / SSE 接口规格](#7-rest--sse-接口规格)
8. [Agent 编排与运行生命周期](#8-agent-编排与运行生命周期)
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

### 1.2 本阶段目标（P3 软件开发 Agent）

在既有“展示层 + 事件协议 + MySQL 档案”之上，把任务工作台升级为本地软件开发 Agent：

1. 创建主任务前建立 SSE 规划会话，主 Agent 读取仓库、拆分两层计划，含糊处可向用户提问；
2. 用户确认计划后创建主任务，在 Git 仓库中创建主任务临时分支并串行执行直属子任务；
3. 子 Agent 通过目录查询、文件查询、文件读取、文件创建、文件写入、本地 Shell 实现任务；
4. 每个子任务使用独立临时分支；成功合并、退回或失败时删除分支，主任务结束前原分支不变；
5. 可配置独立审核 Agent：在已提交的子任务分支上自行执行测试，输出结构化结论；
6. 审核退回时携带问题与测试输出重做；超过重做上限则失败并回滚；
7. 主 Agent 在子任务之间可动态重排未开始的任务，计划 `revision` 递增；
8. 主 Agent、子 Agent、审核 Agent 都可通过 `waiting_input` 向用户展示选项与自由输入框；
9. 运行结束前由主 Agent 汇总，合并主任务分支回原分支；合并冲突时保留现场并报错；
10. 规划会话与执行运行共用同一事件信封、SSE 传输与前端类型，不维护第二套演示链路。

**本阶段结束时应达到的状态：** 真实编码运行、审核、Git 回滚与前端工作台形成完整闭环，
且事件契约在 Java、TypeScript、`docs/events.md` 三处逐字段一致。

### 1.3 成功标准

- 验收清单（第 12 章与 `docs/p3-agent-platform.md` 第 15 节）全部通过；
- 事件契约在 Java、TypeScript、`docs/events.md` 三处逐字段一致；
- 断线、乱序、重复、丢投递等异常下，UI 不崩溃且最终状态一致；
- 任务层级严格为两层：一个主任务 + 直属子任务；
- 所有文件工具限制在工作目录内，敏感路径与 Git 写命令被拒绝；
- 审核测试失败、超时或留下工作区副作用时，不得被判为 PASS；
- 重启进程后，MySQL 中的主任务档案仍可列表与查看；
- 真实模型只在用户配置 LLM 连接后调用；未配置或不可用时规划会话进入 `FAILED` 并给出明确错误。

### 1.4 术语表

| 术语 | 定义 |
| --- | --- |
| Run（运行） | 一次完整的 Agent 编排执行，具有唯一 `runId` 与独立事件序列。 |
| Main Task（主任务） | plan 中唯一没有 `parentTaskId` 的根任务；有独立的 `mainTaskId` 与状态枚举。 |
| Subtask（子任务） | 直接挂在主任务下的任务；只允许一层，使用独立状态枚举。 |
| Archive（档案） | 从 MySQL 读取的只读主任务详情，不建立 SSE，也不会重启编排。 |
| Event（事件） | 通过 SSE 传输的结构化数据，统一使用信封（Envelope）。 |
| seq | 同一 `runId` 内事件的严格单调递增序号，从 1 开始，是排序、去重、续传的游标。 |
| eventId | 事件的唯一标识，由 seq 派生；消费端必须按它幂等。 |
| Snapshot（快照） | 服务端为一次运行生成的完整状态：运行信息 + 主任务 + 扁平子任务列表 + 全量事件。 |
| Orchestrator（编排器） | 产生事件、驱动任务状态推进的抽象；本阶段由 `CodingTaskOrchestrator` 实现。 |
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
| UC-01 | 规划并确认一次任务 | 输入目标 + 选择 Git 仓库根目录 | 规划 SSE 展示思考与只读工具，产出两层计划；修改或确认后创建主任务。 |
| UC-02 | 回答 Agent 的追问 | 规划或执行中出现 `waiting_input` | 详情区弹出抉择面板，点选或自由输入并提交后，`input_received` 到达，任务继续。 |
| UC-03 | 网络中断后恢复 | 断开/恢复 SSE 连接 | 前端自动重连，先拉快照再按 `afterSeq` 续传，任务树无重复、无丢失。 |
| UC-04 | 处理失败与重试 | 子任务执行失败或审核退回 | 任务短暂显示“重试中”，随后重试成功或达到上限失败，最终状态一致。 |
| UC-05 | 验证异常健壮性 | 开发态模拟断线、重复投递或乱序 | UI 不崩溃，快照恢复后最终状态与服务端一致。 |
| UC-06 | 调试事件流 | 打开事件日志 | 可按类型过滤，查看 seq、type、时间、taskId，重复/缺口有明确提示。 |
| UC-07 | 查询历史主任务 | 打开顶栏「主任务库」 | 列出 MySQL 中多个主任务；打开任一记录后以只读方式展示其子任务、提示词、中间结果与事件账本。 |

---

## 3. 阶段范围与边界

### 3.1 本阶段必须包含

| 模块 | 内容 |
| --- | --- |
| 事件契约 | 信封、17 种事件类型、payload 结构、序号/去重/续传规则、状态机。 |
| 后端编排 | `TaskOrchestrator` 接口与唯一实现 `CodingTaskOrchestrator`：依赖调度、Git 分支、审核与动态重排。 |
| 规划会话 | 创建主任务前的 SSE 会话；只读工具调查仓库；计划修订、确认、取消与工作区锁。 |
| Agent 循环 | 主 / 子 / 审核 Agent 共用 `AgentLoop`；OpenAI 兼容 function tool calling；`ask_user` 抉择。 |
| 工具 | `fs.list`、`fs.find`、`fs.read`、`fs.create`、`fs.write`、`shell.exec`；工作目录沙箱与危险命令策略。 |
| Git | 主任务 / 子任务临时分支、`--no-ff` 合并、回滚删除、只读 Git Shell 白名单、`git_checkpoint` 事件。 |
| 审核 | 可配置开关与最大重做次数；独立 `ReviewTestRunner`；结构化 JSON 结论与强制判定规则。 |
| SSE 出口 | `SseEmitter` 管理、1 小时超时、15 秒心跳、`afterSeq` 补发、断连清理；运行与规划会话各一条流。 |
| REST API | 规划会话、主任务列表 / 详情、已保存计划修订、提交抉择、获取快照、SSE 事件流、本地目录浏览、LLM 设置、Agent 设置。 |
| 持久化 | MySQL + MyBatis-Plus；事件同步落库；规划会话、Agent 设置与审核记录落库。 |
| 数据表 | `tn_main_task`、`tn_main_task_plan`、`tn_main_task_workspace`、`tn_subtask`、`tn_prompt`、`tn_task_artifact`、`tn_event`、`tn_llm_setting`、`tn_llm_setting_model`、`tn_plan_session`、`tn_plan_session_event`、`tn_agent_setting`、`tn_task_review`。 |
| 工作目录 | 规划会话与执行都必须选择 Git 仓库根目录；校验干净工作区并规范化路径。 |
| LLM 设置 | OpenAI 兼容 Base URL、模型名；literal 密钥或环境变量名；写入 MySQL、启动加载、保存后热更新。 |
| 前端工作台 | 上下页头 + 左右目录布局、SSE 规划会话、计划表、工作目录选择器、两层子任务折叠详情、抉择面板（选项 + 自由输入）、审核与 Git 时间线、日志弹窗、连接状态、主任务档案、LLM / Agent 设置。 |
| 前端状态机 | `taskTree` store：事件 → 主任务 + 子任务状态，幂等、排序、增量拼接、快照恢复、只读档案、动态计划。 |
| SSE 客户端 | `EventSource` 封装、指数退避重连、`afterSeq` 续传、连接状态暴露。 |
| 文档 | `docs/events.md` 与前后端类型定义同步。 |

### 3.2 本阶段明确禁止（范围锁）

- **禁止**多模型路由、RAG、向量库、Embedding；
- **禁止**用户体系、权限、登录、鉴权；
- **禁止**消息队列、Redis、微服务拆分、服务注册发现；
- **禁止**为等待输入另开 SSE 连接、使用轮询或 WebSocket；
- **禁止**三层及以上任务层级；真实规划输出出现非直属子任务时必须失败；
- **禁止**把实时运行恢复成可继续执行的进程状态；进程重启后只允许查询已落库档案；
- **禁止**远端 Git 操作（fetch / pull / push / PR）与强制推送；
- **禁止**子 Agent 或审核 Agent 写 Git。分支、提交、合并、回滚由编排器独占；
- **禁止**审核 Agent 使用文件写工具；其 Shell 仅允许测试、lint、build、类型检查与只读 Git；
- **禁止**在日志或 API 响应中输出 LLM 密钥、数据库密码等敏感值；
- **禁止** token / 费用预算与计费逻辑（P3 明确不做）。

数据库只保存主任务、计划表、工作目录、直属子任务、提示词、中间结果、事件原文、
LLM 连接设置、规划会话、Agent 设置与审核记录，不引入 JPA、Flyway、消息队列或分布式事务。
真实模型调用通过自研 OpenAI 兼容客户端完成，不引入 Spring AI。

### 3.3 后续阶段（仅记录，不实现）

| 阶段 | 内容 | 对本期的影响 |
| --- | --- | --- |
| P4 | 运行中断点续传、跨进程恢复与更完整的历史检索 | 当前档案为只读；断点续传不在本期。 |
| P5 | 多模型路由、子 Agent 隔离、失败降级、远端沙箱 | 事件契约保留扩展空间。 |

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
│   ├─ AppHeader         目录总数 / 运行中 / 设置入口               │
│   ├─ TaskDirectory     MySQL 档案 + 当前实时运行                 │
│   ├─ CreateTaskPanel   目标 + Git 工作目录 + 规划会话            │
│   ├─ TaskWorkspace     主任务 + 直属子任务（两层）                │
│   ├─ SubtaskDetail     思考 / 提示词 / 工具 / 审核 / Git         │
│   ├─ PlanSheet         计划表                                     │
│   └─ EventLogDialog    账本弹窗                                   │
│            ▲                              │                      │
│            │ 读取状态                      │ POST /inputs         │
│      Pinia taskTree store ◄── SSE Client ─┼── REST Client        │
└────────────┼──────────────────────────────┼──────────────────────┘
             │ 事件信封                      │ JSON
┌────────────┼──────────────────────────────┼──────────────────────┐
│ Backend (Spring Boot 3.x)                 ▼                      │
│  PlanSessionController / MainTaskController / RunController       │
│       │                                                           │
│       ├─► PlanningSessionService ─► PlanningAgent（真实模型）     │
│       └─► RunService ─► CodingTaskOrchestrator                    │
│                              │ SubAgent / ReviewAgent / GitService │
│                              │ publish(event)                     │
│                              ▼                                    │
│                 RunStore (内存事件表 + 两层任务投影)              │
│                        │                                          │
│                        ▼                                          │
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
| `orchestrator` | 调度子 Agent、工具、审核与 Git，产生事件、等待/恢复抉择 | 禁止直接写 SSE；只能通过 `EventPublisher`。 |
| `event` | 事件类型、信封、payload 模型、序列化 | 禁止裸字符串表示事件类型。 |
| `sse` | 连接生命周期、补发、心跳、超时、断连清理 | 禁止因客户端断开而中断编排。 |
| `store` | 运行状态、事件表、两层任务投影、快照 | 禁止把编排器内部语义泄漏到契约。 |
| `persistence` | MyBatis-Plus 实体 / Mapper / IService、事件落库、主任务档案查询 | 禁止持久化非直属任务；禁止把持久化失败抛给 SSE 调用链。 |
| 前端 `stores` | 所有运行状态的唯一写入点；实时状态与只读档案分开 | 组件禁止直接改 store 状态。 |
| 前端 `api` | REST 与 SSE 传输、重连、解析 | 禁止在组件内直接 new `EventSource`。 |
| 前端 `components` | 展示与用户交互 | 禁止持有业务状态副本并自行改写。 |

### 4.3 核心流程

#### 4.3.1 正常流程

```
Client                                      Backend
  │ POST /plan-sessions {goal, workingDir}   │
  │─────────────────────────────────────────►│ 校验 Git、获取仓库工作区锁
  │◄─────────────────────────────────────────│ 201 { planSessionId, status }
  │ GET /plan-sessions/{id}/events?afterSeq=0│
  │─────────────────────────────────────────►│ 注册订阅者 → 补发历史 → 接入实时
  │◄═════════════════════════════════════════│ 思考 / 只读工具 / waiting_input / plan
  │ POST /plan-sessions/{id}/confirm         │
  │─────────────────────────────────────────►│ 释放规划锁 → 写入主任务与计划
  │◄─────────────────────────────────────────│ 200 { mainTaskId, runId, planVersion }
  │ GET /api/runs/{runId}/events?afterSeq=0  │
  │─────────────────────────────────────────►│ CodingTaskOrchestrator 执行
  │◄═════════════════════════════════════════│ run_started / plan / ... / run_completed
  │  （waiting_input，连接保持）              │
  │ POST /api/runs/{runId}/inputs            │
  │─────────────────────────────────────────►│ 校验 → 唤醒 Agent
  │◄─────────────────────────────────────────│ 202 Accepted
  │◄═════════════════════════════════════════│ input_received + 后续事件
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
| FR-RUN-01 | 主任务只能由规划会话确认创建：`POST /api/main-tasks/plan-sessions/{id}/confirm` 返回 `mainTaskId`、`runId`、`workingDir` 与 `planVersion`。 | MUST |
| FR-RUN-02 | 确认时必须已有合法两层计划与 Git 仓库根目录；缺失或未就绪返回 409 / 40906，字段缺失返回 400 / 40001。 | MUST |
| FR-RUN-03 | 确认请求同步写入主任务与计划表，编排异步启动；HTTP 响应不得等待 Agent 执行完成。 | MUST |
| FR-RUN-04 | 实时运行状态与事件保存在内存；主任务、子任务、提示词、中间结果与事件原文同步写入 MySQL。 | MUST |
| FR-RUN-05 | 同一后端进程内，多次创建主任务互不干扰；`mainTaskId` 与 `runId` 全局唯一；可保存多个主任务并分别查询详情。 | MUST |
| FR-RUN-06 | 运行具有 `RUNNING / COMPLETED / FAILED` 三种对外状态；首个终态事件生效后，后续终态事件忽略。 | MUST |
| FR-RUN-07 | 提供 `GET /api/main-tasks?limit=N` 与 `GET /api/main-tasks/{mainTaskId}`，从 MySQL 返回主任务列表与详情；未知主任务返回 40403。 | MUST |
| FR-RUN-08 | 规划会话的 `workingDir` 必填，且必须是后端机器上的 Git 仓库根目录。后端规范化为真实绝对路径后保存，不存在、不是目录或不是仓库根分别返回 400 / 40004、40012。 | MUST |
| FR-RUN-09 | 创建响应、主任务列表与详情均返回 `workingDir`；未选择时为 `null`。 | MUST |
| FR-RUN-10 | 真实执行必须由规划会话确认后通过 `RunService.createFromPlan` 启动，且只由 `CodingTaskOrchestrator` 承担；不存在脚本或兼容运行入口。 | MUST |
| FR-RUN-11 | 真实执行前必须校验 Git（可执行、仓库根、非 detached HEAD、工作区干净），并获取仓库级工作区锁；同一仓库并发启动返回 409 / 40910。 | MUST |
| FR-RUN-12 | 运行总超时 60 分钟；超时后回滚临时分支并以 `error` 终止，不得无限占用工作区锁。 | MUST |

### 5.2 SSE 通道（SSE）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-SSE-01 | 提供 `GET /api/runs/{runId}/events?afterSeq=N`，返回 `text/event-stream`。 | MUST |
| FR-SSE-02 | 连接建立后，先补发 `seq > afterSeq` 的全部历史事件，按 seq 升序，再接实时流，交接处不得丢失或重复。 | MUST |
| FR-SSE-03 | 单连接内实时事件按 seq 升序推送；乱序到达由前端缓冲处理。 | MUST |
| FR-SSE-04 | 连接建立后立即发送一次 `heartbeat`，此后每 15 秒发送一次；至少有一个订阅者时才产生心跳。 | MUST |
| FR-SSE-05 | `SseEmitter` 超时设为 1 小时（3,600,000 ms）；超时后清理连接，编排不受影响。 | MUST |
| FR-SSE-06 | 客户端断开时清理对应 emitter/队列，但不得停止编排，也不得停止写入事件表。 | MUST |
| FR-SSE-07 | 运行进入终态（`run_completed` 或 `error`）后，已推送终态事件的连接应在冲刷完成后关闭；新连接补发历史 + 一次心跳后关闭。 | MUST |
| FR-SSE-08 | 同一运行允许多个订阅者，每个订阅者独立收到完整事件；事件产生一次、共享序号。 | SHOULD |
| FR-SSE-09 | 响应头必须包含 `Cache-Control: no-cache`、`X-Accel-Buffering: no`，防止代理缓冲。 | MUST |
| FR-SSE-10 | 运行不存在返回 404；`afterSeq` 非法（负数、非整数、大于 `lastSeq`）返回 400；均使用 JSON 包装体，不建立 SSE 流。 | MUST |
| FR-SSE-11 | 心跳事件必须进入事件表并占用 seq，使 `afterSeq` 续传不会误判缺口。 | MUST |
| FR-SSE-12 | 提供 `GET /api/main-tasks/plan-sessions/{id}/events?afterSeq=N` 规划 SSE；信封、心跳、补发与游标规则与运行 SSE 一致。 | MUST |
| FR-SSE-13 | 规划会话事件写入 `tn_plan_session_event`，进程重启后可查询已落库会话与事件，但不可续跑。 | MUST |

### 5.3 事件契约（EVT）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-EVT-01 | 所有事件使用统一信封：`eventId / runId / seq / type / timestamp / task / payload`。 | MUST |
| FR-EVT-02 | `seq` 在同一运行内从 1 开始严格单调递增，任何事件（含心跳）都占用一个 seq。 | MUST |
| FR-EVT-03 | `eventId` 由 seq 生成且运行内唯一，格式为 `evt_` + 6 位 seq。 | MUST |
| FR-EVT-04 | 必须实现第 6 章的 17 种事件类型与 payload 结构。 | MUST |
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
| FR-DEC-07 | 抉择请求支持 `customInput`（≤4000 字）；与 `selectedOptionId` 至少提供一个；`allowCustom=false` 时拒绝自由输入。 | MUST |
| FR-DEC-08 | 主任务、子任务、规划 Agent 均通过 `waiting_input` 提问；规划会话使用 `POST /api/main-tasks/plan-sessions/{id}/inputs`。 | MUST |
| FR-DEC-09 | 自由输入不能绕过选项校验；选择选项时可附带补充说明，Agent 同时收到两者。 | MUST |

### 5.5 编排器与运行生命周期（ORCH）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-ORCH-01 | 定义 `TaskOrchestrator` 接口：`start(runId, request)`、`submitInput(runId, taskId, optionId)`；唯一实现为 `CodingTaskOrchestrator`。 | MUST |
| FR-ORCH-02 | `start` 必须校验确认后的计划、工作目录与原分支，创建 `RunState` 后异步执行；启动失败把主任务标记为 `FAILED`。 | MUST |
| FR-ORCH-03 | 事件先写入内存事件表并更新投影，再同步落库，最后进入订阅队列；终止事件在冲刷后关闭订阅者。 | MUST |
| FR-ORCH-04 | 遇到 `waiting_input` 时挂起当前任务直到 `submitInput` 被调用，SSE 保持连接；其他运行不受影响。 | MUST |
| FR-ORCH-05 | 子任务严格按 `dependsOn` 串行调度；根任务覆盖整个运行，依赖失败时后续任务不执行。 | MUST |
| FR-ORCH-06 | 运行对外状态为 `RUNNING / COMPLETED / FAILED`；首个终态事件生效后忽略后续终态事件。 | MUST |
| FR-ORCH-07 | 无论成功、失败还是超时，终态时必须释放仓库工作区锁并关闭运行订阅者。 | MUST |
| FR-ORCH-08 | 事件契约不得包含编排器内部字段（脚本、延迟、分支等）；阶段扩展统一使用 `// P4: ...` 标记。 | MUST |

### 5.6 前端工作台（FE）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-FE-01 | 提供“新建主任务”入口：创建面板包含目标输入、Git 工作目录选择、规划会话与确认按钮；规划或运行进行中时禁止重复创建。 | MUST |
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
| FR-FE-13 | 顶部运行总览展示 runId、goal、运行状态、任务统计（成功/失败/总数）与计划版本。 | MUST |
| FR-FE-14 | 未开始、加载中、空数据、运行失败、运行丢失均必须有明确空态/错误态，不得白屏。 | MUST |
| FR-FE-15 | 重复事件、seq 缺口恢复、解析失败应在界面上以非侵入方式提示（计数或横幅）。 | SHOULD |
| FR-FE-16 | 交互必须可键盘操作；状态图标有 aria-label；遵循 `prefers-reduced-motion`。 | SHOULD |
| FR-FE-17 | 顶部提供「主任务库」入口，列出多个已持久化主任务；打开后展示其子任务、提示词、中间结果与事件账本，并明确标注只读。 | MUST |
| FR-FE-18 | 档案模式下禁用抉择提交与模拟断线，不允许建立 SSE；用户可退出档案并新建主任务。 | MUST |
| FR-FE-19 | 创建面板提供只读工作目录输入与“选择目录”按钮；目录选择弹窗展示当前路径、父目录、根目录与子文件夹，只列目录不列文件，确认后回填绝对路径。 | MUST |
| FR-FE-20 | 顶部提供“LLM 设置”入口；弹窗包含 Base URL、API Key 来源单选（直接写入 / 环境变量）与对应输入；保存后提示已立即生效，literal 密钥只展示掩码。 | MUST |
| FR-FE-21 | 任务工作区头部展示工作目录；未指定时显示“未指定工作目录”，档案模式同样可见。 | MUST |
| FR-FE-22 | 创建流程必须先建立规划会话：连接规划 SSE，展示规划思考与工具调用，`READY` 后展示计划表；确认前不得创建主任务。 | MUST |
| FR-FE-23 | 规划会话支持按提示修订、确认、取消；`waiting_input` 时展示问题、选项与自由输入框，提交后等待 `input_received`。 | MUST |
| FR-FE-24 | 工作目录选择器必须提示并校验 Git 仓库根目录；目录不是仓库或工作区不干净时展示后端错误，不进入规划。 | MUST |
| FR-FE-25 | 子任务展示 `REVIEWING` / `CANCELLED` 状态；工作区提供审核结论面板（verdict、问题列表、测试证据）与 Git 时间线（分支、提交、合并、回滚）。 | MUST |
| FR-FE-26 | 计划表展示 `revision`、重排原因与 `changedTaskIds`；重排后刷新行与依赖，不重置已完成子任务的局部状态。 | MUST |
| FR-FE-27 | 设置弹窗分为“LLM 连接”与“Agent 审核”两个页签；审核页签包含开关与最大重做次数（0–5）。 | MUST |
| FR-FE-28 | 无模型配置、模型不可用、规划失败时展示明确错误与修复入口（打开设置 / 重试 / 取消），不得白屏。 | MUST |

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
| FR-STORE-12 | 提供规划会话状态：`planSessionId`、状态、计划、最后 seq、连接状态、待办抉择；规划与运行状态互斥。 | MUST |
| FR-STORE-13 | 应用 `review_started` → `REVIEWING`、`review_completed` → `RETRYING`（REWORK）；保存最近一次审核结论与测试证据。 | MUST |
| FR-STORE-14 | 应用 `git_checkpoint` 维护按任务分组的 Git 时间线，不改变任务状态；重复事件按 `eventId` 去重。 | MUST |
| FR-STORE-15 | 应用重排后的 `plan`：保留已有节点状态，新增任务为 `PENDING`，被移除的 `PENDING` 任务为 `CANCELLED`。 | MUST |
| FR-STORE-16 | 抉择状态保存 `allowCustom` / `customPlaceholder` 与已提交的自由输入，重置运行或切换会话时清空。 | MUST |

### 5.8 持久化（DB）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-DB-01 | 使用 MyBatis-Plus `BaseMapper`、`IService`、`ServiceImpl` 作为实体管理基础；不得手写重复 CRUD。 | MUST |
| FR-DB-02 | `tn_main_task` 保存多个主任务；`main_task_id` 唯一，包含标题、目标、状态、摘要、错误、等待抉择与时间。 | MUST |
| FR-DB-03 | `tn_subtask` 保存直属子任务；`(main_task_id, task_id)` 唯一，包含排序、依赖、独立状态、摘要、错误与等待抉择。 | MUST |
| FR-DB-04 | `tn_prompt` 保存每次 `prompt_assembled` 的 system / user / context / finalPrompt，`(main_task_id, seq)` 唯一。 | MUST |
| FR-DB-05 | `tn_task_artifact` 保存思考增量、工具调用；`tool_result` 按 `callId` 或工具名 FIFO 回填到对应调用记录。 | MUST |
| FR-DB-06 | `tn_event` 保存每条事件的原始 JSON 与 seq，`(run_id, seq)` 唯一，供档案账本回放。 | MUST |
| FR-DB-07 | 每条事件在内存投影后同步落库；落库失败记录日志，但不得阻断 SSE 推送或让前端崩溃。 | MUST |
| FR-DB-08 | 主任务失败直接 `FAILED`；子任务 `retryable=true` 落为 `RETRYING`，重试成功后为 `SUCCESS`。 | MUST |
| FR-DB-09 | 进程重启后实时运行不可恢复，但已落库的主任务列表与详情必须仍可查询。 | MUST |
| FR-DB-10 | MySQL 连接信息只从 `TASKNEST_MYSQL_*` / `MYSQL_*` 环境变量读取；`schema.sql` 必须同时兼容 MySQL 与 H2（MODE=MySQL）。 | MUST |
| FR-DB-11 | `tn_main_task_workspace` 与主任务一对一保存 `working_dir`；未选择目录时不写行。 | MUST |
| FR-DB-12 | `tn_llm_setting` 为单例表（`id=1`），保存 Base URL、`api_key_type`（`LITERAL` / `ENV`）与值；ENV 模式的值是环境变量名。 | MUST |
| FR-DB-13 | 应用启动时必须自动读取 LLM 设置；保存成功后必须在同一请求内更新运行时快照。读取接口不得返回 literal 密钥明文，日志不得输出密钥。 | MUST |
| FR-DB-14 | `tn_plan_session` / `tn_plan_session_event` 保存规划会话状态与事件原文；`(plan_session_id, seq)` 唯一。 | MUST |
| FR-DB-15 | `tn_agent_setting` 为单例表（`id=1`），保存 `review_enabled` 与 `review_max_reworks`；启动加载，保存后热更新。 | MUST |
| FR-DB-16 | `tn_task_review` 保存每次审核结论、问题 JSON、测试证据与耗时，`(main_task_id, task_id, attempt)` 唯一。 | MUST |
| FR-DB-17 | 新增表继续使用 `CREATE TABLE IF NOT EXISTS`，同时兼容 MySQL 8 与 H2（MODE=MySQL）；不得修改既有表列。 | MUST |

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

### 5.10 本地目录浏览（FS）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-FS-01 | 提供 `GET /api/fs/directories?path=...&showHidden=false`，默认从用户主目录开始浏览。 | MUST |
| FR-FS-02 | 返回当前规范化绝对路径、父目录、当前目录名、本机根目录列表、子文件夹列表与截断标记。 | MUST |
| FR-FS-03 | 只返回目录，不读取或返回文件内容；默认过滤隐藏目录，可按参数包含。 | MUST |
| FR-FS-04 | 路径不存在、不是目录或格式非法返回 400 / 40004；无权限读取返回 400 / 40005。 | MUST |
| FR-FS-05 | 单目录最多返回 500 个文件夹，超出时置 `truncated=true`，前端提示可直接输入路径跳转。 | SHOULD |

### 5.11 LLM 设置（SET）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-SET-01 | 提供 `GET /api/settings/llm`，返回 Base URL、模型名称、Key 类型、环境变量名或掩码、配置状态、环境变量解析状态与更新时间。 | MUST |
| FR-SET-02 | 提供 `PUT /api/settings/llm`，body 为 `{baseUrl, modelName, apiKeyType, apiKeyValue?}`；`apiKeyType` 只允许 `LITERAL` / `ENV`。 | MUST |
| FR-SET-03 | Base URL 必须是完整 `http/https` 地址；`modelName` 必填、自由文本、最多 128 字；ENV 模式的变量名必须符合 `[A-Za-z_][A-Za-z0-9_]*`。 | MUST |
| FR-SET-04 | LITERAL 模式提交空白值表示沿用已保存密钥；没有已保存密钥时返回 400 / 40001。 | MUST |
| FR-SET-05 | 设置写入 MySQL 后立即替换 volatile 运行时快照；进程重启时从数据库自动加载。 | MUST |
| FR-SET-06 | `GET` 对 LITERAL 只返回 `apiKeyValue=null` 与末四位掩码；ENV 返回变量名并实时解析 `envResolved`。 | MUST |
| FR-SET-07 | 设置是真实模型请求的唯一来源；规划、子 Agent、审核与汇总均读取当前运行时快照。 | MUST |
| FR-SET-08 | `modelName` 是必填的自由文本模型 ID，默认 `gpt-4o`；保存时去除首尾空白，不能为空且最多 128 字。 | MUST |

### 5.12 规划会话（PLAN）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-PLAN-01 | 提供 7.3.4 的七个规划会话接口；确认前不得创建主任务。 | MUST |
| FR-PLAN-02 | 规划会话必须持有仓库工作区锁，确认/取消/失败时释放；同一仓库并发规划或运行返回 409 / 40910。 | MUST |
| FR-PLAN-03 | 规划 Agent 使用 `PLANNING` 工具模式：只读文件工具与 `ask_user`，不得写文件或执行 shell。 | MUST |
| FR-PLAN-04 | 规划输出必须是两层计划：一个根任务 + 直接子任务；子任务含 `description`、`acceptanceCriteria`、`allowedTools`、`testCommand`。 | MUST |
| FR-PLAN-05 | 计划不合法（层级、ID、依赖、字段）时返回 `error(PLAN_INVALID_OUTPUT)`，不得进入 `READY`。 | MUST |
| FR-PLAN-06 | `READY` 后可反复修订，`revision` 递增；确认响应返回 `planVersion`。 | MUST |
| FR-PLAN-07 | 规划会话只使用当前 LLM 设置调用真实模型；未配置或不可用时以 `error(PLAN_AGENT_UNAVAILABLE)` 收尾并释放工作区锁。 | MUST |

### 5.13 Agent 执行与工具（AGENT）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-AGENT-01 | 主 Agent 负责汇总与动态重排；子 Agent 在独立分支实现单个子任务；审核 Agent 独立审核并跑测试。 | MUST |
| FR-AGENT-02 | 工具集合：`fs.list`、`fs.find`、`fs.read`、`fs.write`、`fs.create`、`shell.exec`、`ask_user`；模型通过 OpenAI 兼容函数调用触发。 | MUST |
| FR-AGENT-03 | 所有文件工具必须限制在工作区内，拒绝敏感路径（`.env`、私钥、`.ssh`、`.aws`、`.git` 等）与越界路径。 | MUST |
| FR-AGENT-04 | `shell.exec` 仅本地执行，危险命令由 `CommandPolicy` 拦截并产生 `tool_result(error)`；不得联网拉取或推送。 | MUST |
| FR-AGENT-05 | 子任务按 `dependsOn` 串行调度；依赖未成功前不得开始；依赖失败则后续任务不执行。 | MUST |
| FR-AGENT-06 | 子 Agent 每次尝试最多 40 轮工具调用；超过上限按可重试失败处理。 | MUST |
| FR-AGENT-07 | Agent 遇到歧义必须调用 `ask_user`，映射为 `waiting_input` 并阻塞至用户提交。 | MUST |
| FR-AGENT-08 | 单个子任务失败不终止整个运行；达到重做上限后标记 `FAILED` 并继续可执行任务，最终由主 Agent 汇总。 | MUST |
| FR-AGENT-09 | 动态重排只允许修改/删除 `PENDING` 任务；新增任务不得依赖被取消任务；重排失败保留原计划并记录日志。 | MUST |

### 5.14 Git 版本管理（GIT）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-GIT-01 | 运行开始时从原始分支创建 `tasknest/<mainTaskId>`；子任务每次尝试创建 `tasknest/subtasks/<mainTaskId>/<taskId>-a<attempt>`，与主任务分支保持同级，避免 Git ref 文件 / 目录冲突。 | MUST |
| FR-GIT-02 | 子任务成功后以 `--no-ff` 合并回主任务分支并删除子分支；失败/重做时丢弃子分支。 | MUST |
| FR-GIT-03 | 全部子任务完成后主任务分支以 `--no-ff` 合并回原始分支并删除主任务分支。 | MUST |
| FR-GIT-04 | 合并冲突时保留主任务与子任务分支，发布 `git_checkpoint` 与 `error(GIT_MERGE_CONFLICT)`，不得强推或自动解决。 | MUST |
| FR-GIT-05 | 非冲突失败回滚主任务分支、恢复原始分支并发布 `rolled_back`；回滚失败以 `GIT_ROLLBACK_FAILED` 终止。 | MUST |
| FR-GIT-06 | 每个分支/提交/合并/回滚动作发布 `git_checkpoint`；提交使用内部身份并跳过钩子。 | MUST |
| FR-GIT-07 | 只允许本地 Git；禁止 fetch / pull / push / remote / force push；Agent 只能执行只读 Git 查询。 | MUST |

### 5.15 审核 Agent（REVIEW）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-REVIEW-01 | 审核开关与最大重做次数由 `/api/settings/agent` 控制；默认开启、最多重做 2 次。 | MUST |
| FR-REVIEW-02 | 审核 Agent 无写文件工具，只能只读文件、受限 shell 与 `ask_user`；shell 仅允许测试/lint/build/类型检查/只读 Git。 | MUST |
| FR-REVIEW-03 | 平台先独立运行测试命令（计划 `testCommand`，`none` 时自动探测），再让审核 Agent 给出结论。 | MUST |
| FR-REVIEW-04 | 审核输出严格 JSON：`verdict`（PASS/REWORK）、`issues[]`（BLOCKER/MAJOR/MINOR）、`testStatus`、`testEvidence[]`。 | MUST |
| FR-REVIEW-05 | 平台测试证据优先：测试失败、超时或出现未提交副作用时强制 REWORK；模型输出冲突时以平台为准。 | MUST |
| FR-REVIEW-06 | 审核测试超时 10 分钟；LLM/解析失败重试一次，仍失败按 `REVIEW_UNAVAILABLE` 终止该子任务。 | MUST |
| FR-REVIEW-07 | REWORK 时删除子任务分支、携带审核意见重做；超过上限发布 `task_failed(retryable=false)`。 | MUST |
| FR-REVIEW-08 | 每次审核发布 `review_started` 与 `review_completed`，并写入 `tn_task_review`。 | MUST |

### 5.16 Agent 设置与安全（SAFE）

| 编号 | 需求 | 级别 |
| --- | --- | --- |
| FR-SAFE-01 | 密钥不得出现在日志、事件、提示词快照或 API 响应中；工具环境变量只注入解析后的密钥。 | MUST |
| FR-SAFE-02 | 文件工具与工作区锁都以 Git 仓库根为边界；路径解析必须防止 `..` 与符号链接逃逸。 | MUST |
| FR-SAFE-03 | 不实现 token / 成本预算；单任务以轮数与总时长限制兜底。 | MUST |
| FR-SAFE-04 | P3 不使用 Spring AI `ChatClient` / `TaskTool`；使用项目内 OpenAI 兼容客户端。 | MUST |
| FR-SAFE-05 | 前端不得展示 literal 密钥；设置保存后只显示掩码。 | MUST |

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
| `eventId` | string | 是 | 默认 `evt_` + 6 位以上零填充 seq；运行内唯一。 |
| `runId` | string | 是 | 格式 `run_yyyyMMdd_HHmmss_<6位十六进制>`，全局唯一。 |
| `seq` | integer (int64) | 是 | 从 1 开始，运行内严格单调递增；所有事件（含 heartbeat）都占用 seq。 |
| `type` | string | 是 | 第 6.3 节的 17 种小写下划线值之一。 |
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
| `waiting_input` | 等待用户抉择，任务暂停 | 任务 | `question`, `options[]`, `allowCustom`, `customPlaceholder` |
| `input_received` | 用户抉择已提交 | 任务 | `selectedOptionId`, `customInput` |
| `task_completed` | 子任务完成 | 任务 | `summary` (string) |
| `task_failed` | 子任务失败/等待重做 | 任务 | `error` (string), `retryable` (boolean) |
| `review_started` | 审核开始 | 任务 | `attempt` (number) |
| `review_completed` | 审核结论 | 任务 | `attempt`, `verdict`, `summary`, `issues[]`, `testStatus`, `testEvidence[]`, `modelName`, `durationMs` |
| `git_checkpoint` | Git 分支/提交/合并/回滚 | 任务 | `action`, `branch`, `targetBranch`, `commit`, `attempt`, `noChanges`, `message` |
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
  "revision": 1,
  "reason": null,
  "changedTaskIds": null,
  "tasks": [
    {
      "taskId": "t_root", "parentTaskId": null, "title": "生成订单服务接口文档",
      "agentName": "lead", "dependsOn": [],
      "description": null, "acceptanceCriteria": null,
      "allowedTools": null, "testCommand": null
    },
    {
      "taskId": "t_scan", "parentTaskId": "t_root", "title": "扫描 Controller",
      "agentName": "scanner", "dependsOn": [],
      "description": "列出所有 Controller 与路由",
      "acceptanceCriteria": ["列出全部路由"],
      "allowedTools": ["fs.list", "fs.find", "fs.read"],
      "testCommand": "none"
    },
    {
      "taskId": "t_list", "parentTaskId": "t_root", "title": "抽取接口清单",
      "agentName": "extractor", "dependsOn": ["t_scan"],
      "description": "从 Controller 抽取接口参数与返回",
      "acceptanceCriteria": ["每个接口都有参数与返回"],
      "allowedTools": ["fs.read", "fs.write"],
      "testCommand": "mvn test"
    }
  ]
}
```

- `tasks` 必须包含**完整两层结构，包括根任务**；根任务 `parentTaskId=null`，且恰好一个根。
- 除根任务外，所有任务都必须直接挂在根任务下；更深层级直接失败。
- `taskId` 在运行内唯一；父引用必须存在；依赖不得成环。
- `dependsOn` 为任务 ID 数组，可以为空；编排器按依赖顺序串行调度。
- P3 字段：`allowedTools`、`testCommand` 可以为 `null`；规划器必须提供 `description` 与
  `acceptanceCriteria`。
- 信封中的 `task` 为根任务引用。
- 执行开始时发送一次 `plan`；动态重排时再次发送，`revision` 递增并携带
  `reason` / `changedTaskIds`。重排只允许动 `PENDING` 任务。
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
  ],
  "allowCustom": true,
  "customPlaceholder": "也可以直接描述你的选择……"
}
```

- `question` 非空。
- `options` 至少 2 个；`id` 非空且同一事件内唯一；`label` 非空；`description` 可为空字符串。
- `allowCustom` 可选 boolean，缺省为 `true`；为 `false` 时前端禁用自由输入。
- `customPlaceholder` 可选 string，仅在允许自由输入时展示。
- 主任务、子任务与规划会话均可发出；推送后对应任务暂停，SSE 保持连接，心跳继续。
- 规划会话中该事件属于 `plan_root` 合成根任务。

#### 6.4.9 `input_received`

```json
{ "selectedOptionId": "local-first", "customInput": null }
```

- 只能由编排器在 `submitInput` 校验通过后生成；模型与工具都不能直接产出该事件。
- `selectedOptionId` 与 `customInput` 至少一个非空；`selectedOptionId` 必须匹配该任务当前
  `waiting_input.options[].id`（自定义输入时为 `null`）。
- `customInput` 为自由文本，最长 4000 字；选择选项时可同时附带补充说明。

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
{ "code": "ORCHESTRATOR_ERROR", "message": "编排器执行异常" }
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

#### 6.4.15 `review_started`

```json
{ "attempt": 1 }
```

- `task` 为被审核的子任务；`attempt` 为当前尝试序号（从 1 开始）。
- 仅在开启 `reviewEnabled` 时出现；紧接着子任务提交后发送。

#### 6.4.16 `review_completed`

```json
{
  "attempt": 1,
  "verdict": "REWORK",
  "summary": "缓存失效逻辑不完整。",
  "issues": [
    { "severity": "MAJOR", "file": "src/cache.ts", "line": 42,
      "message": "缺少失效回退逻辑", "suggestion": "补充 stale 分支" }
  ],
  "testStatus": "PASSED",
  "testEvidence": [
    { "command": "npm test", "exitCode": 0, "timedOut": false, "summary": "42 passed" }
  ],
  "modelName": "gpt-4o",
  "durationMs": 18342
}
```

- `verdict` 只能是 `PASS` / `REWORK`；`testStatus` 只能是 `PASSED` / `FAILED` / `UNAVAILABLE`。
- 平台测试证据优先于模型自述：平台测试失败或出现未提交的副作用一律强制 `REWORK`。
- `issues[].severity` 为 `BLOCKER` / `MAJOR` / `MINOR`；`file`、`line`、`suggestion` 可空。
- `testEvidence[].exitCode` 在超时或不可用时可为 `null`。
- `REWORK` 后子任务回到 `RUNNING` 重试（新分支、`attempt+1`），超过上限则 `task_failed`。

#### 6.4.17 `git_checkpoint`

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
- `targetBranch` 仅在 `merged` / `rolled_back` 时填写；`commit` 为短 SHA，可为空。
- `attempt` 仅子任务分支事件携带；主任务分支事件为 `null`。
- `noChanges=true` 表示工作区无变化，未创建空提交。
- Git 操作由编排器独占；Agent 只能通过 `shell.exec` 执行只读 Git 命令。

### 6.5 序号、去重与游标规则

1. **分配**：所有事件由 `EventPublisher` 在产生时原子分配 seq；心跳也走同一分配器。
2. **持久化**：先写入运行事件表，再进入订阅队列；保证快照/续传能读到。
3. **传输顺序**：连接内严格按 seq 升序推送；网络乱序由前端缓冲与快照恢复处理。
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
| `REVIEWING` | 已提交，审核 Agent 正在检查 | 审核中 |
| `SUCCESS` | 成功完成并已合并 | 成功 |
| `FAILED` | 最终失败 | 失败 |
| `CANCELLED` | 动态重排移除，不再执行 | 已取消 |

`CANCELLED` 只由动态重排产生，且只能移除仍为 `PENDING` 的子任务；进入终态的
`SUCCESS` / `FAILED` 不可再取消。

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
| `RUNNING` / `WAITING_INPUT` / `RETRYING` | `review_started` | `REVIEWING` | 子任务已提交，等待审核结论。 |
| `REVIEWING` | `review_completed` (`verdict=PASS`) | `REVIEWING` | 保持审核中，随后由 `task_completed` 收尾。 |
| `REVIEWING` | `review_completed` (`verdict=REWORK`) | `RETRYING` | 等待重做；编排器随后发送 `task_failed(retryable=true)`。 |
| `REVIEWING` | `task_failed` (`retryable=true`) | `RETRYING` | 审核退回或审核不可用后重试。 |
| `RUNNING` / `WAITING_INPUT` / `RETRYING` / `REVIEWING` | `task_failed` (`retryable=false`) | `FAILED` | 终态。 |
| `RUNNING` / `REVIEWING` | `task_completed` | `SUCCESS` | 记录 summary；完成后分支已合并。 |
| `PENDING` | `plan`（重排，任务被移除） | `CANCELLED` | 只有 `PENDING` 可被取消；记录原因。 |
| `SUCCESS` / `FAILED` / `CANCELLED` | 任何任务状态类事件 | 不变 | 记录警告；游标继续推进。 |
| 任意非终态 | `run_completed` | `FAILED` | 服务端/客户端修复，合成错误“运行已完成但该子任务未完成”。 |
| 任意非终态 | `error` | `FAILED` | 合成错误使用 `error.message`。 |

补充规则：

- 两层结构固定：`plan` 中唯一没有 `parentTaskId` 的节点是主任务，其余节点必须直接挂在它下面；出现第二个根节点或更深层级时计划非法，解析阶段即失败。
- 子任务 `task_started` 前收到 `task_thought` 等详情事件：允许先建占位节点并记录详情，状态保持 `PENDING`，同时告警（正常编排不会出现）。
- 同一任务的 `waiting_input` 未解决时，不允许再次出现 `waiting_input`；若出现，后者覆盖待办并告警。
- 主任务状态不做隐式聚合；编排器必须为主任务发送自己的开始/完成事件。
- 终态任务不再接受新的详情事件（忽略并记录）。
- 主任务与子任务分别投影、分别持久化到 `tn_main_task` 与 `tn_subtask`，前端也分别渲染状态标签。
- `git_checkpoint` 不改变任务状态，只记录分支/提交/合并/回滚时间线；分支操作失败由
  `task_failed` 或 `error` 表达。
- 审核被退回时：`review_completed(REWORK)` 先把子任务置为 `RETRYING`，随后
  `task_failed(retryable=true)` 重申重试；达到上限则 `task_failed(retryable=false)`。
- 动态重排只在两个子任务之间发生；重排不会重新运行 `SUCCESS`，也不会复活 `FAILED`。
- 合并冲突保留主任务分支与子任务分支，运行以 `error(GIT_MERGE_CONFLICT)` 终止；
  其他失败回滚主任务分支并恢复原始分支。

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
| `40004` | 400 | 工作目录 / 浏览路径不存在、不是目录或格式非法 |
| `40005` | 400 | 目录不可读取（权限或 IO 错误） |
| `40006` | 400 | 抉择提交不合法（选项与自由输入均缺失等） |
| `40010` | 400 | 本机找不到可执行的 git |
| `40011` | 400 | 工作目录不是 Git 仓库 |
| `40012` | 400 | 工作目录不是 Git 仓库根目录 |
| `40013` | 400 | Git 处于 detached HEAD |
| `40014` | 400 | Git 工作区存在未提交改动 |
| `40401` | 404 | 运行不存在（含后端重启后丢失） |
| `40402` | 404 | 任务不存在 |
| `40403` | 404 | 主任务档案不存在 |
| `40404` | 404 | 主任务没有保存计划表 |
| `40405` | 404 | 规划会话不存在 |
| `40901` | 409 | 任务当前不处于等待输入状态（含重复提交） |
| `40902` | 409 | 抉择选项无效 |
| `40903` | 409 | 运行已结束 |
| `40904` | 409 | 运行已结束，计划表只读 |
| `40905` | 409 | 规划会话正在运行，不能并发修订 |
| `40906` | 409 | 规划会话尚未就绪（无计划可确认） |
| `40907` | 409 | 动态重排不合法（改动非 PENDING 任务、依赖悬空或成环） |
| `40910` | 409 | 同一仓库已有活动运行 / 规划会话 |
| `40911` | 409 | 运行期间 Git 分支被外部切换 |
| `40912` | 409 | Git 合并冲突，分支已保留 |
| `50001` | 500 | 服务内部错误 |
| `50010` / `50011` / `50012` | 500 | Git 分支创建 / 提交 / 回滚失败 |
| `50020` | 500 | 规划 Agent 执行失败 |
| `50201` | 502 | 模型请求失败 |
| `50202` | 502 | 模型不支持工具调用 |
| `50301` | 503 | 规划 Agent 不可用 |

`error` 事件与 `task_failed.error` 中还可能出现 Agent 字符串错误码：
`SUBAGENT_FAILED`、`REVIEW_REJECTED`、`REVIEW_UNAVAILABLE`、`REVIEW_INVALID_OUTPUT`、
`REVIEW_SIDE_EFFECT`、`PLAN_INVALID_OUTPUT`、`TOOL_BLOCKED`、`LLM_REQUEST_FAILED`。
前端按字符串展示即可，不映射为 HTTP 码。

错误响应示例：

```json
{
  "code": 40401,
  "message": "运行不存在",
  "data": null
}
```

### 7.3 创建主任务与运行

P3 只有一个创建入口：**规划会话**（7.3.4）。先通过 SSE 完成需求澄清与计划确认，
再由 `POST /api/main-tasks/plan-sessions/{id}/confirm` 创建主任务并启动
`CodingTaskOrchestrator`。每次确认都会先写入一条 `PENDING` 主任务与确认稿计划，
再启动一次运行。

#### 7.3.1 主任务列表

```
GET /api/main-tasks?limit=50
```

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `limit` | query | 否 | 默认 50，范围 1–200；按 `createdAt` 倒序。 |

`data` 为 `MainTaskSummary[]`，每项包含 `mainTaskId`、`runId`、`rootTaskId`、`title`、
`goal`、`workingDir`、`planVersion`、`status`、`summary`、`error`、
`subtaskCount`、`completedSubtaskCount`、`createdAt`、`updatedAt`、`completedAt`。
`workingDir` 在没有选择目录或历史数据中为 `null`；`completedSubtaskCount` 只统计
`status = SUCCESS` 的子任务；`planVersion` 在没有计划表时为 0（历史数据），
否则为已保存计划的当前版本。

#### 7.3.2 主任务档案详情

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

#### 7.3.3 已保存计划修订

创建前的计划由规划会话生成；确认后如需继续调整，只提供已保存计划的修订接口，
不启动新的运行：

```
POST /api/main-tasks/{mainTaskId}/plan/revisions
{ "instruction": "增加一步风险复核" }
```

- 仅在主任务未进入终态时允许；成功后立即更新 `tn_main_task_plan` 并返回最新
  `PlanDraftView`；
- 修订只改变计划表内容与版本，不会追加、删除或重启已经运行的子任务；
- 修订记录写入 `revisions`，同时渲染到 Markdown 的「修订记录」小节；
- 主任务进入 `COMPLETED / FAILED` 后修订返回 `409 / 40904 PLAN_READ_ONLY`；
- 主任务不存在返回 `404 / 40403`；主任务没有计划表返回 `404 / 40404 PLAN_NOT_FOUND`；
- 空指令或非法行结构返回 `400 / 40001`。

`PlanDraftView` 字段：`mainTaskId`、`title`、`goal`、`version`、
`rows`、`revisions`、`markdown`。每行含
`title / agent / dependency / output / taskId / description / acceptanceCriteria /
allowedTools / testCommand`；P3 行字段可为 `null`（历史档案）。

#### 7.3.4 规划会话（P3）

创建主任务前先通过 SSE 完成需求澄清与计划确认。会话在确认或取消前不产生主任务，
并持有该 Git 仓库根目录的工作区锁。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/main-tasks/plan-sessions` | 创建会话，返回 `201` |
| `GET` | `/api/main-tasks/plan-sessions/{id}` | 查询状态、计划与 `lastSeq` |
| `GET` | `/api/main-tasks/plan-sessions/{id}/events?afterSeq=N` | 规划 SSE |
| `POST` | `/api/main-tasks/plan-sessions/{id}/inputs` | 提交抉择 `{taskId, selectedOptionId?, customInput?}` |
| `POST` | `/api/main-tasks/plan-sessions/{id}/revisions` | 按提示修订 `{instruction}` |
| `POST` | `/api/main-tasks/plan-sessions/{id}/confirm` | 确认计划并创建主任务 |
| `POST` | `/api/main-tasks/plan-sessions/{id}/cancel` | 取消会话并释放锁 |

创建请求：

```json
{
  "goal": "为订单服务增加缓存层并补测试",
  "workingDir": "E:/workspace/demo"
}
```

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `goal` | string | 是 | 去除首尾空白后非空，最多 4000 字。 |
| `workingDir` | string | 是 | 必须是本机 Git 仓库根目录；后端规范化为绝对真实路径。 |

创建行为：

1. 校验 `goal`；
2. `GitService.validate` 校验 Git 可执行、目录是仓库根、非 detached HEAD、工作区干净；
3. 以 `plan:<planSessionId>` 为 owner 获取仓库工作区锁，冲突返回 `409 / 40910`；
4. 创建会话状态并由 `PlanningAgent` 异步生成计划；
5. 立即返回 `201`，前端再连接 SSE。

创建响应：

```json
{ "planSessionId": "ps_20261021_100000_a1b2c3", "status": "RUNNING", "lastSeq": 1 }
```

会话视图 `PlanSessionView`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `planSessionId` | string | 会话 ID。 |
| `status` | string | `RUNNING / WAITING_INPUT / READY / CONFIRMED / CANCELLED / FAILED`。 |
| `goal` | string | 原始目标。 |
| `workingDir` | string | 规范化后的仓库根路径。 |
| `plan` | `Plan \| null` | 最近一次生成的计划。 |
| `lastSeq` | number | 会话事件最大序号。 |
| `error` | string \| null | `FAILED` 时的错误信息。 |

状态语义：

- `RUNNING`：规划 Agent 正在分析 / 调用只读工具；
- `WAITING_INPUT`：已发出 `waiting_input`，等待 `/inputs`；心跳继续；
- `READY`：计划已生成，可 `revisions` 反复修订或 `confirm`；
- `CONFIRMED`：已创建主任务并释放锁，会话 SSE 以 `run_completed` 收尾；
- `CANCELLED`：用户取消，SSE 以 `run_completed` 收尾；
- `FAILED`：以 `error` 事件收尾，`error.code` 为 `PLAN_AGENT_FAILED` / `PLAN_AGENT_UNAVAILABLE`。

规划 SSE：

- 事件信封与运行 SSE 相同，`runId = planSessionId`；`task` 固定为合成根任务
  `{taskId:"plan_root", title:"规划", ...}`。
- 支持 `afterSeq` 补发、心跳、`waiting_input` / `input_received` 与 `run_completed` / `error`。
- 规划阶段只出现只读工具（`fs.list` / `fs.find` / `fs.read`）与 `ask_user`。
- 事件同时写入 `tn_plan_session_event`，供断线补发。

修订：

```
POST /api/main-tasks/plan-sessions/{id}/revisions
{ "instruction": "拆出独立的测试任务" }
```

- 仅 `READY` 可修订，否则 `409 / 40905`；空指令 `400 / 40001`。
- 修订后状态回到 `RUNNING`，`revision` 递增；新计划事件的
  `revision` 与 `reason` 反映本次修订。

确认：

```
POST /api/main-tasks/plan-sessions/{id}/confirm
```

成功响应：

```json
{
  "mainTaskId": "mt_20261021_100500_d4e5f6",
  "runId": "run_20261021_100500_d4e5f6",
  "workingDir": "E:/workspace/demo",
  "planVersion": 2
}
```

- 仅 `READY` 且有计划时可确认，否则 `409 / 40906`；
- 确认前重新校验 Git 与工作目录，并记录当前分支作为 `originalBranch`；
- 先释放规划锁，再写入 `PENDING` 主任务与计划表并启动运行；启动失败会重新获取锁，
  会话保持 `READY`，避免产生“假确认”。

取消：

- 非终态会话取消后状态置 `CANCELLED`，发布 `run_completed` 并释放锁；
- 已终态会话重复取消幂等返回成功。

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
  "selectedOptionId": "local-first",
  "customInput": "先只做本地缓存，云端同步下一期"
}
```

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `taskId` | string | 是 | 必须是该运行中正在等待输入的任务。 |
| `selectedOptionId` | string | 否 | 必须属于该任务当前 `waiting_input.options[].id`。 |
| `customInput` | string | 否 | 自由输入，最多 4000 字；与选项至少提供一个。 |

- `allowCustom=false` 时只接受 `selectedOptionId`；
- 选择选项时可同时提交补充说明，编排器把两者一起回传给 Agent。

成功响应 `202 Accepted`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "runId": "run_20261021_100000_a1b2c3",
    "taskId": "t_scope",
    "selectedOptionId": "local-first",
    "customInput": "先只做本地缓存，云端同步下一期"
  }
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
- 允许方法：`GET, POST, PUT, OPTIONS`；允许头：`Content-Type`；不启用凭据。
- 后端默认绑定 `127.0.0.1`，减少本机暴露面。

### 7.8 本地目录浏览

```
GET /api/fs/directories?path=E:\workspace&showHidden=false
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `path` | 否 | 绝对路径；省略时使用后端进程的用户主目录。 |
| `showHidden` | 否 | 默认 `false`；为 `true` 时包含隐藏目录。 |

响应 `data`：

```json
{
  "path": "E:\\workspace",
  "parent": "E:\\",
  "name": "workspace",
  "roots": ["C:\\", "D:\\", "E:\\"],
  "directories": [
    { "name": "task-nest", "path": "E:\\workspace\\task-nest", "hidden": false }
  ],
  "truncated": false
}
```

- 只读取目录项并过滤普通文件；不提供文件内容读取接口。
- 路径不存在、不是目录或格式非法 → 400 / `40004`；读取失败 → 400 / `40005`。
- 最多返回 500 个文件夹；超出时 `truncated=true`。
- 该接口用于本地单用户工作台，浏览范围是后端进程可访问的本地文件系统。

### 7.9 LLM 设置

```
GET /api/settings/llm
PUT /api/settings/llm
```

`GET` 响应 `data`：

```json
{
  "baseUrl": "https://api.openai.com/v1",
  "modelName": "gpt-4o",
  "apiKeyType": "LITERAL",
  "apiKeyValue": null,
  "apiKeyPreview": "****5678",
  "apiKeyConfigured": true,
  "envResolved": false,
  "updatedAt": "2026-10-21T10:00:00.000Z"
}
```

- LITERAL：`apiKeyValue` 恒为 `null`，`apiKeyPreview` 为 `****` + 末四位；不会返回明文。
- ENV：`apiKeyValue` 为环境变量名，`apiKeyPreview` 为 `null`，`envResolved` 表示后端进程
  当前能否解析到非空值。
- 尚未保存时返回默认 Base URL（`https://api.openai.com/v1`）、默认模型
  `gpt-4o`、`ENV` 与 `OPENAI_API_KEY`，`updatedAt` 为 `null`。
- 模型名称为自由文本，以兼容不同 OpenAI 兼容服务商的模型 ID；不做模型列表校验。

`PUT` 请求：

```json
{
  "baseUrl": "https://api.openai.com/v1",
  "modelName": "gpt-4o",
  "apiKeyType": "LITERAL",
  "apiKeyValue": "sk-..."
}
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `baseUrl` | 是 | 完整 http/https 地址，最多 500 字；保存时去掉结尾 `/`。 |
| `modelName` | 是 | 自由文本模型 ID，最多 128 字；去除首尾空白后不能为空。 |
| `apiKeyType` | 是 | `LITERAL` 或 `ENV`。 |
| `apiKeyValue` | 视类型 | LITERAL 留空表示沿用已保存值；ENV 必须是合法环境变量名。 |

保存成功后响应与 `GET` 相同的视图，并立即替换运行时快照。Base URL 非法、
模型名称为空或超长、环境变量名非法、LITERAL 首次保存却为空 → 400 / `40001`。

### 7.10 Agent / 审核设置（P3）

```
GET /api/settings/agent
PUT /api/settings/agent
```

`GET` 响应 `data`：

```json
{
  "reviewEnabled": true,
  "reviewMaxReworks": 2,
  "updatedAt": "2026-10-21T10:00:00.000Z"
}
```

`PUT` 请求：

```json
{ "reviewEnabled": true, "reviewMaxReworks": 2 }
```

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `reviewEnabled` | boolean | 是 | 是否在每个子任务提交后启动审核 Agent。 |
| `reviewMaxReworks` | integer | 是 | 审核退回后的最大重做次数，0–5。 |

- 默认：`reviewEnabled=true`、`reviewMaxReworks=2`，保存在单例表 `tn_agent_setting`（`id=1`）。
- 保存后立即替换运行时快照；正在运行的子任务沿用启动时的设置。
- 参数缺失或越界 → 400 / `40001`。
- 关闭审核时，子任务提交后直接合并，不产生 `review_started` / `review_completed`。

---

## 8. Agent 编排与运行生命周期

### 8.1 编排器接口

```java
public interface TaskOrchestrator {
    String start(String runId, RunRequest request);
    void submitInput(String runId, String taskId, String optionId, String customInput);
}
```

- `start`：接收已确认的计划、工作目录、原始分支与主任务分支，校验后异步执行；
  由 `RunService.createFromPlan` 调用。
- `submitInput`：校验任务归属、等待状态与选项后唤醒 Agent；校验失败抛出可映射为
  7.2 错误码的业务异常。
- 唯一实现：`CodingTaskOrchestrator`，按 `dependsOn` 串行执行子任务，负责
  Git 分支、审核与动态重排。
- 真实执行不使用 Spring AI：项目以自定义 OpenAI 兼容 `/chat/completions` 客户端实现
  函数工具调用（`orchestrator/llm/LlmClient`），因为现有依赖中不存在 Spring AI，
  且 Spring AI 1.0.x 要求 Spring Boot 3.4+。该偏离已记录在 `CLAUDE.md`。

### 8.2 运行生命周期

1. 规划会话确认后，`RunService.createFromPlan` 生成 `mainTaskId` 与 `runId`，
   写入 `PENDING` 主任务、工作目录与计划表，再调用 `TaskOrchestrator.start`。
2. `start` 校验 `planTasks`、`workingDir` 与 `originalBranch` 后创建 `RunState`
   （runId、事件表、seq 分配器、任务表、订阅者表、状态），在编排线程池中异步执行。
3. 依次发布 `run_started`、`plan` 与根任务的 `task_started`；从原始分支创建
   `tasknest/<mainTaskId>`。
4. 按计划顺序执行满足 `dependsOn` 的子任务，每次尝试创建独立子任务分支；
   子 Agent 通过 `AgentLoop` 调用工具并发布思考、提示词、工具与完成 / 失败事件。
5. 需要人工裁决时发布 `waiting_input` 并挂起当前任务，SSE 保持连接；
   `submitInput` 通过后发布 `input_received`，把选项与自由输入回传给 Agent 继续执行。
6. 子任务完成后提交、按设置执行审核；审核通过后 `--no-ff` 合并回主任务分支并删除子分支，
   随后发布该子任务的 `task_completed`。
7. 主 Agent 可在子任务之间发布新的 `plan` 动态重排未开始的任务。
8. 全部子任务结束后，主 Agent 汇总并把主任务分支 `--no-ff` 合并回原始分支，
   发布根任务 `task_completed` 与 `run_completed`；系统级失败发布 `error`。
9. 终态事件冲刷完成后关闭订阅者，释放仓库工作区锁；进程退出或超时同样释放锁。

### 8.3 计划与动态重排

- 确认稿计划是运行的唯一输入：`plan` 事件首次携带根任务与全部直属子任务，
  `revision` 从 1 开始。
- 主 Agent 可在子任务之间发布新的 `plan` 动态重排计划，必须携带 `revision`（递增）、
  `reason` 与 `changedTaskIds`。
- 动态重排只允许修改或删除 `PENDING` 子任务；被移除的 `PENDING` 任务进入 `CANCELLED`，
  已完成或运行中的任务不得修改。
- 新增任务不得依赖被取消或不存在的任务；依赖图不得成环。
- 重排校验由 `PlanRevisionService` 完成；失败时保留原计划并记录日志，不发布新计划。
- 计划表同时写入 `tn_main_task_plan`，运行中的提示词修订只更新档案，不影响已执行子任务。

### 8.4 两层任务约束与元数据

- 计划必须恰好包含一个根任务（主任务）与若干直属子任务，禁止更深层级。
- `taskId` 在运行内唯一；子任务的 `parentTaskId` 必须直接指向根任务；
  `dependsOn` 只能引用同一计划内已声明的任务。
- 任务事件通过 `task` 引用任务元数据；根任务事件更新主任务，直属子任务事件更新子任务表，
  非直属任务一律不落库。
- 根任务覆盖整个运行：`run_started` 后由根任务 `task_started` 开始，
  最终以根任务 `task_completed` 与 `run_completed` 收尾。

### 8.5 工具、沙箱与抉择

| 工具 | 用途 | 约束 |
| --- | --- | --- |
| `fs.list` / `fs.find` / `fs.read` | 浏览、查找、读取仓库文件 | 限制在仓库根；敏感路径与越界路径拒绝。 |
| `fs.create` / `fs.write` | 创建与写入文件 | 仅子 Agent；审核 Agent 不可用。 |
| `shell.exec` | 本地命令 | 由 `CommandPolicy` 拦截危险命令；禁止联网拉取或推送。 |
| `ask_user` | 向用户提问 | 0–8 个选项；0 个选项表示只接受自由输入；映射为 `waiting_input`。 |

- 所有路径解析必须防止 `..` 与符号链接逃逸；工作区边界是 Git 仓库根。
- `waiting_input` 只挂起当前任务，SSE 与心跳保持；`submitInput` 携带选项与自由输入，
  两者可同时存在；`allowCustom=false` 时拒绝自由输入。
- 审核 Agent 只能使用只读文件工具、受限 shell 与 `ask_user`；其 shell 仅允许测试、
  lint、build、类型检查与只读 Git。

### 8.6 审核闭环

- 每个子任务提交后，若启用审核，平台先按计划 `testCommand`（`none` 时自动探测）
  独立运行测试，再调用审核 Agent 输出严格 JSON 结论。
- 结论包含 `verdict`（`PASS` / `REWORK`）、`issues[]`、`testStatus` 与 `testEvidence[]`；
  平台测试证据优先：测试失败、超时或产生未提交副作用一律强制 `REWORK`。
- `REWORK` 时发布 `task_failed(retryable=true)`、丢弃本次子任务分支并携带审核意见重做；
  超过最大重做次数发布 `task_failed(retryable=false)`。
- 每次审核发布 `review_started` 与 `review_completed`，并写入 `tn_task_review`；
  审核结论写库失败只记录日志，不阻断合并。
- 审核 agent 或解析失败可重试一次，仍失败按 `REVIEW_UNAVAILABLE` 终止该子任务。

### 8.7 Git 分支与回滚

- 运行开始时从原始分支创建 `tasknest/<mainTaskId>`；子任务每次尝试创建
  `tasknest/subtasks/<mainTaskId>/<taskId>-a<attempt>`，与主任务分支同级，
  避免 Git ref 文件 / 目录冲突。
- 子任务成功后以 `--no-ff` 合并回主任务分支并删除子分支；失败或重做时丢弃子分支。
- 全部子任务完成后，主任务分支以 `--no-ff` 合并回原始分支并删除主任务分支。
- 非冲突失败回滚主任务分支、恢复原始分支并发布 `rolled_back`；
  回滚失败以 `GIT_ROLLBACK_FAILED` 终止。
- 合并冲突时保留双方分支，发布 `git_checkpoint` 与 `error(GIT_MERGE_CONFLICT)`，
  不得强推或自动解决。
- Git 操作由编排器独占；Agent 只能通过 `shell.exec` 执行只读 Git 查询。

### 8.8 与事件契约的衔接

- `TaskOrchestrator` 只暴露 `start` / `submitInput`；SSE、REST 与前端只依赖事件契约。
- 事件契约不得包含编排器内部字段（脚本、延迟、分支等）；所有任务状态变化必须通过
  第 6 章的事件类型表达。
- `review_started` / `review_completed` / `git_checkpoint` 由真实编排器在审核与 Git
  动作处产生，前端只做投影。
- 编排器在创建运行前要求 `planTasks`、`workingDir`、`originalBranch` 与
  `mainTaskBranch`，缺失时同步抛出业务异常，不产生半成品运行。
- `// P4:` 注释只用于未来阶段扩展，不得留下无阶段标记的 TODO。

---

## 9. 前端工作台需求

> 9.1–9.5 保留为早期三栏原型的交互基线；页面原型评审通过后，正式布局、创建流程、
> 计划表、日志弹窗与通知以 **9.9 正式工作台交互** 为准，两者冲突时以 9.9 为准。

### 9.1 页面布局

桌面优先，最小支持 1280×720。

```
┌────────────────────────────────────────────────────────────────────────────┐
│ TaskNest 任务巢  [目标______] [新建主任务] [主任务库 3]                     │
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
- “新建主任务”按钮：切换到创建面板，输入目标并选择 Git 工作目录后进入规划会话；
  规划或运行进行中禁用。
- “主任务库”按钮：打开 MySQL 档案抽屉，展示已持久化主任务数量。
- 连接状态指示器（FR-FE-12）：
  - 未开始 / 连接中 / 已连接 / 重连中（第 n 次）/ 已断开；
  - 展示最近 seq 与最近事件时间（可选）。
- 运行总览：主任务 ID、runId、goal、主任务状态、子任务统计（完成数/总数/等待中）、
  计划版本。
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

- 开始规划：
  - 重置 store；
  - `POST /api/main-tasks/plan-sessions` 创建会话；
  - 成功后连接规划 SSE `afterSeq=0`；
  - 失败展示错误并恢复表单。
- 确认计划：
  - `POST /api/main-tasks/plan-sessions/{id}/confirm`；
  - 成功后连接运行 SSE `afterSeq=0`，失败保留会话并展示错误。
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
│ TaskNest 任务巢   主任务 3 · 运行中 1 · MySQL 档案   [LLM 设置] [深色页头] │
├──────────────────────┬───────────────────────────────────────────────────┤
│ 主任务目录            │ 任务工作区                                         │
│ [搜索________]        │ 标题 / 状态 / 目标 / 工作目录 / 计划版本 / 进度     │
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
- 页头右侧提供“LLM 设置”入口，打开设置弹窗；不在页头展示任何密钥内容。

#### 9.9.2 创建主任务与计划表

- 点击“新建主任务”后，右侧切换为创建面板，不立即创建运行。
- 创建步骤固定为：**描述目标 → 选择 Git 工作目录 → 开始规划 → 回答追问 / 修订计划
  （可选）→ 确认创建**。
- 目标使用大的多行文本框（最多 4000 字），支持 `Ctrl/Meta + Enter` 开始规划。
- 工作目录为只读路径输入框，点击“选择目录”打开 `DirectoryPickerDialog`，通过
  `GET /api/fs/directories` 逐级浏览后端本机文件夹；必须选择 Git 仓库根目录。
- “开始规划”调用 `POST /api/main-tasks/plan-sessions` 并连接规划 SSE；规划阶段展示
  Agent 思考、只读工具调用与 `waiting_input` 抉择面板。
- 计划就绪后展示 Markdown 计划表；未生成计划表不得确认创建。
- 用户可用自然语言提示修订：调用 `POST /api/main-tasks/plan-sessions/{id}/revisions`，
  展示版本号与修订记录；每次修订追加一条记录，不覆盖历史。
- 确认创建调用 `POST /api/main-tasks/plan-sessions/{id}/confirm`；成功后进入任务工作区
  并切换到运行 SSE。

#### 9.9.3 任务工作区

- 主任务头部展示标题、主任务状态、目标、工作目录、创建/更新时间、计划版本与
  完成进度；未指定工作目录时显示“未指定工作目录”，长路径省略并保留完整悬浮提示。
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

#### 9.9.6 目录选择与 LLM 设置弹窗

- **目录选择弹窗**：显示当前路径、上级目录、根目录快捷入口；只列文件夹，默认隐藏
  隐藏目录，可切换显示；最多 500 项，超出提示截断。确认后回填绝对路径，不读取文件。
- **LLM 设置弹窗**：打开时读取 `GET /api/settings/llm`；字段为 Base URL 与 API Key
  类型单选（`LITERAL` 直接写入 / `ENV` 读取环境变量）。
  - `LITERAL`：密码输入框；已保存时提示掩码（`****` + 末四位），留空保存表示沿用；
    明确说明明文保存在本机 MySQL，读取接口不会返回明文。
  - `ENV`：输入环境变量名，前端校验命名格式；保存后展示后端进程能否解析该变量。
- 保存调用 `PUT /api/settings/llm`，成功提示并关闭；失败保留表单并展示错误。
- 保存后立即用于规划、子 Agent、审核与汇总的真实模型请求，无需重启；
  模型不可用时前端展示明确错误与修复入口。

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
    REVIEW_STARTED("review_started"),
    REVIEW_COMPLETED("review_completed"),
    GIT_CHECKPOINT("git_checkpoint"),
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
    TaskCompletedPayload, TaskFailedPayload, ReviewStartedPayload,
    ReviewCompletedPayload, GitCheckpointPayload, RunCompletedPayload,
    ErrorPayload, EmptyPayload {}

public record RunStartedPayload(String goal) implements EventPayload {}
public record PlanPayload(
    List<PlannedTask> tasks,
    Integer revision,
    String reason,
    List<String> changedTaskIds
) implements EventPayload {}
public record PlannedTask(
    String taskId, String parentTaskId, String title, String agentName,
    List<String> dependsOn, String description,
    List<String> acceptanceCriteria, List<String> allowedTools,
    String testCommand
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
    String question, List<DecisionOption> options,
    Boolean allowCustom, String customPlaceholder
) implements EventPayload {}
public record DecisionOption(
    String id, String label, String description
) {}
public record InputReceivedPayload(
    String selectedOptionId, String customInput
) implements EventPayload {}
public record TaskCompletedPayload(String summary) implements EventPayload {}
public record TaskFailedPayload(String error, boolean retryable) implements EventPayload {}
public record ReviewStartedPayload(int attempt) implements EventPayload {}
public record ReviewCompletedPayload(
    int attempt, String verdict, String summary,
    List<ReviewIssue> issues, String testStatus,
    List<TestEvidence> testEvidence, String modelName, long durationMs
) implements EventPayload {}
public record ReviewIssue(
    String severity, String file, Integer line,
    String message, String suggestion
) {}
public record TestEvidence(
    String command, Integer exitCode, boolean timedOut, String summary
) {}
public record GitCheckpointPayload(
    String action, String branch, String targetBranch,
    String commit, Integer attempt, boolean noChanges, String message
) implements EventPayload {}
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
- 反序列化/测试必须覆盖全部 17 种类型。

### 10.2 MySQL 持久化模型

实体使用 MyBatis-Plus：每个实体一个 `BaseMapper<T>`，一个 `IService<T>` + `ServiceImpl`，
业务层只调用 `save / updateById / getOne / list / count` 等基础方法。表由
`backend/src/main/resources/schema.sql` 在启动时以 `CREATE TABLE IF NOT EXISTS` 创建。

| 表 | 实体 | 内容 |
| --- | --- | --- |
| `tn_main_task` | `MainTaskEntity` | 一条创建记录对应一个主任务与一次运行。 |
| `tn_main_task_plan` | `MainTaskPlanEntity` | 与主任务一对一的计划表、行数据与修订记录。 |
| `tn_main_task_workspace` | `MainTaskWorkspaceEntity` | 与主任务一对一的工作目录；未选择时不写行。 |
| `tn_subtask` | `SubtaskEntity` | 直属子任务；不保存更深层级。 |
| `tn_prompt` | `PromptEntity` | 每次 `prompt_assembled` 的完整提示词。 |
| `tn_task_artifact` | `TaskArtifactEntity` | 思考增量、工具调用与工具结果。 |
| `tn_event` | `RunEventEntity` | 每条事件的原始 JSON。 |
| `tn_llm_setting` | `LlmSettingEntity` | 单例 LLM 设置：Base URL、API Key 类型与值。 |
| `tn_llm_setting_model` | `LlmSettingModelEntity` | 单例模型名称，作为 `tn_llm_setting` 的配套表。 |
| `tn_plan_session` | `PlanSessionEntity` | 规划会话状态、计划 JSON、最后 seq 与错误。 |
| `tn_plan_session_event` | `PlanSessionEventEntity` | 规划会话事件原文，按 seq 唯一。 |
| `tn_agent_setting` | `AgentSettingEntity` | 单例审核设置：开关与最大重做次数。 |
| `tn_task_review` | `TaskReviewEntity` | 每次审核的结论、问题、测试证据与耗时。 |

#### 10.2.1 `tn_main_task`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `main_task_id` | VARCHAR(64) UNIQUE | 对外主任务 ID，`mt_` 前缀。 |
| `run_id` | VARCHAR(64) UNIQUE | 对应运行 ID。 |
| `root_task_id` | VARCHAR(64) | `plan` 根节点 taskId。 |
| `title` / `goal` | VARCHAR(255) / TEXT | 标题与用户目标。 |
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
| `plan_version` | INT | 当前版本；确认稿初始为 1，每次修订 +1。 |
| `plan_markdown` | LONGTEXT | 渲染用 Markdown 计划表，含约束与修订记录。 |
| `plan_rows` | LONGTEXT | 计划行 JSON 数组（title / agent / dependency / output）。 |
| `plan_revisions` | LONGTEXT | 修订记录 JSON 字符串数组。 |
| `created_at` / `updated_at` | DATETIME(3) | 创建与最近修订时间。 |

计划表在创建主任务时与 `tn_main_task` 同事务写入；运行中的提示词修订立即更新该行。
修订只改变计划档案，不会创建或删除 `tn_subtask` 记录。

#### 10.2.7 `tn_main_task_workspace`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `main_task_id` | VARCHAR(64) UNIQUE | 所属主任务；一个主任务最多一行。 |
| `working_dir` | VARCHAR(1024) | 规范化后的绝对真实路径。 |
| `created_at` / `updated_at` | DATETIME(3) | 创建时间。 |

使用独立表而非给 `tn_main_task` 加列，避免既有 MySQL 库因
`CREATE TABLE IF NOT EXISTS` 无法自动补列。未指定工作目录时不写入该表。

#### 10.2.8 `tn_llm_setting`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 固定为 1 的单例主键。 |
| `base_url` | VARCHAR(500) | OpenAI 兼容请求地址，保存时去除结尾 `/`。 |
| `api_key_type` | VARCHAR(16) | `LITERAL` / `ENV`。 |
| `api_key_value` | VARCHAR(2048) | 字面密钥，或环境变量名。 |
| `created_at` / `updated_at` | DATETIME(3) | 创建与最近保存时间。 |

服务启动时读取该行到内存快照；保存成功后先写库再替换快照。读取接口对 `LITERAL`
只返回掩码，日志不输出密钥。

#### 10.2.9 `tn_llm_setting_model`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 固定为 1 的单例主键。 |
| `model_name` | VARCHAR(128) | OpenAI 兼容模型 ID，如 `gpt-4o`。 |
| `created_at` / `updated_at` | DATETIME(3) | 创建与最近保存时间。 |

该表是 `tn_llm_setting` 的配套单例表。已有数据库中的 `tn_llm_setting` 不会被自动补列，
因此模型名称独立建表，保持 `CREATE TABLE IF NOT EXISTS` 可重复执行。读取时若没有该行，
返回默认 `gpt-4o`；保存设置时与 `tn_llm_setting` 在同一事务中写入。

#### 10.2.10 `tn_plan_session`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `plan_session_id` | VARCHAR(64) UNIQUE | 对外会话 ID，`ps_` 前缀。 |
| `goal` | TEXT | 用户目标。 |
| `working_dir` | VARCHAR(1024) | 规范化后的仓库根路径。 |
| `status` | VARCHAR(32) | `RUNNING / WAITING_INPUT / READY / CONFIRMED / CANCELLED / FAILED`。 |
| `plan_json` | LONGTEXT | 最近一次计划事件 payload JSON。 |
| `last_seq` | BIGINT | 会话最后事件序号。 |
| `error` | TEXT | `FAILED` 时的错误信息。 |
| `created_at` / `updated_at` | DATETIME(3) | 创建与最近更新时间。 |

#### 10.2.11 `tn_plan_session_event`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `plan_session_id` | VARCHAR(64) | 所属会话。 |
| `event_id` | VARCHAR(64) | 事件 ID。 |
| `seq` | BIGINT | 会话内序号；与 `plan_session_id` 联合唯一。 |
| `type` | VARCHAR(32) | 事件类型线上名。 |
| `event_json` | LONGTEXT | 完整事件信封 JSON，供补发。 |
| `event_time` / `created_at` | DATETIME(3) | 事件时间与落库时间。 |

#### 10.2.12 `tn_agent_setting`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 固定为 1 的单例主键。 |
| `review_enabled` | BOOLEAN | 是否启用审核 Agent。 |
| `review_max_reworks` | INT | 审核退回后的最大重做次数，0–5。 |
| `created_at` / `updated_at` | DATETIME(3) | 创建与最近保存时间。 |

#### 10.2.13 `tn_task_review`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AUTO | 自增主键。 |
| `main_task_id` / `task_id` | VARCHAR(64) | 归属主任务与子任务。 |
| `attempt` | INT | 子任务尝试序号；与归属联合唯一。 |
| `verdict` | VARCHAR(16) | `PASS` / `REWORK`。 |
| `summary` | TEXT | 审核摘要。 |
| `issues` | LONGTEXT | 问题 JSON 数组。 |
| `test_status` | VARCHAR(16) | `PASSED` / `FAILED` / `UNAVAILABLE`。 |
| `test_evidence` | LONGTEXT | 测试证据 JSON 数组。 |
| `model_name` | VARCHAR(128) | 审核所用模型。 |
| `duration_ms` | BIGINT | 审核耗时。 |
| `created_at` | DATETIME(3) | 落库时间。 |

#### 10.2.14 落库规则

- 规划会话确认时先写 `tn_main_task`（`PENDING`）与 `tn_main_task_workspace`，
  再写 `tn_main_task_plan`，最后启动编排；启动失败标记 `FAILED`；
  确认稿版本取规划会话确认时的 revision。
- LLM 设置在启动时加载 `tn_llm_setting` 与 `tn_llm_setting_model` 两个单例行；
  `PUT` 在同一事务中保存两表，成功后立即替换运行时快照，两者必须一致。
- 运行中的计划提示词修订直接更新 `tn_main_task_plan` 的 Markdown、行数据、修订记录、
  版本与 `updated_at`；终态后拒绝修订，返回 `40904`。
- 每条事件先进入内存事件表并更新投影，再同步落库，最后进入 SSE 队列；落库失败只记录日志，不阻断 SSE。
- 主任务与子任务的状态事件按 `rootTaskId` 分流，分别更新两张表。
- `run_completed` / `error` 会补齐主任务终态，并把仍未结束的子任务修复为 `FAILED`。
- 档案查询只读，按 `main_task_id` 取子任务、提示词、中间结果与事件；不依赖内存 `RunStore`。
- 规划会话创建时写 `tn_plan_session`；每条规划事件同步写 `tn_plan_session_event` 并更新
  `plan_json / last_seq / status`；确认后保留会话与事件供审计。
- 审核事件同步写 `tn_task_review`；写失败只记录日志，不阻断审核与合并。
- 动态重排更新 `tn_main_task_plan`，并把被取消的 `PENDING` 子任务状态写为 `CANCELLED`。
- 测试使用 H2 `MODE=MySQL`；MySQL 8 为运行环境，两者共用 `schema.sql`。

### 10.3 运行内存状态

```java
final class RunState {
    String runId;
    String mainTaskId;
    RunStatus status;                      // RUNNING / COMPLETED / FAILED
    Instant createdAt;
    Instant updatedAt;
    long seq;                              // 从 1 开始
    List<EventEnvelope> events;            // 按 seq 升序，含心跳/重复 id
    MainTaskState mainTask;                // 主任务独立状态
    Map<String, TaskNodeState> subtasks;   // 只含直属子任务
    Map<String, SseConnection> subscribers;
    WaitingInputState waitingInput;        // 当前等待抉择的任务
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
  | 'review_started' | 'review_completed' | 'git_checkpoint'
  | 'run_completed' | 'error' | 'heartbeat';

export interface TaskRef {
  taskId: string;
  parentTaskId: string | null;
  title: string;
  agentName: string;
}

export interface PlannedTask {
  taskId: string;
  parentTaskId: string | null;
  title: string;
  agentName: string;
  dependsOn: string[];
  description: string | null;
  acceptanceCriteria: string[] | null;
  allowedTools: string[] | null;
  testCommand: string | null;
}

export interface ContextSnippet { source: string; content: string; }
export interface DecisionOption { id: string; label: string; description: string; }
export type ReviewVerdict = 'PASS' | 'REWORK';
export type ReviewSeverity = 'BLOCKER' | 'MAJOR' | 'MINOR';
export type TestStatus = 'PASSED' | 'FAILED' | 'UNAVAILABLE';
export type GitAction =
  | 'branch_created' | 'committed' | 'merged' | 'rolled_back' | 'branch_deleted';

export interface ReviewIssue {
  severity: ReviewSeverity;
  file: string | null;
  line: number | null;
  message: string;
  suggestion: string | null;
}

export interface TestEvidence {
  command: string;
  exitCode: number | null;
  timedOut: boolean;
  summary: string;
}

export interface PayloadMap {
  run_started: { goal: string };
  plan: {
    tasks: PlannedTask[];
    revision: number | null;
    reason: string | null;
    changedTaskIds: string[] | null;
  };
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
  waiting_input: {
    question: string;
    options: DecisionOption[];
    allowCustom?: boolean | null;
    customPlaceholder?: string | null;
  };
  input_received: { selectedOptionId: string | null; customInput: string | null };
  task_completed: { summary: string };
  task_failed: { error: string; retryable: boolean };
  review_started: { attempt: number };
  review_completed: {
    attempt: number;
    verdict: ReviewVerdict;
    summary: string;
    issues: ReviewIssue[];
    testStatus: TestStatus;
    testEvidence: TestEvidence[];
    modelName: string | null;
    durationMs: number;
  };
  git_checkpoint: {
    action: GitAction;
    branch: string;
    targetBranch: string | null;
    commit: string | null;
    attempt: number | null;
    noChanges: boolean;
    message: string | null;
  };
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
  | 'RETRYING' | 'REVIEWING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';

export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export type ConnectionState =
  | 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED';

export interface WaitingInputSnapshot {
  question: string;
  options: DecisionOption[];
  allowCustom: boolean;
  customPlaceholder: string | null;
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
  taskId: string | null;
  description: string | null;
  acceptanceCriteria: string[] | null;
  allowedTools: string[] | null;
  testCommand: string | null;
}

export interface PlanDraftView {
  mainTaskId: string | null;
  title: string;
  goal: string;
  version: number;
  rows: PlanRowView[];
  revisions: string[];
  markdown: string;
}

export type PlanSessionStatus =
  | 'RUNNING' | 'WAITING_INPUT' | 'READY'
  | 'CONFIRMED' | 'CANCELLED' | 'FAILED';

export interface PlanSessionView {
  planSessionId: string;
  status: PlanSessionStatus;
  goal: string;
  workingDir: string;
  plan: PayloadMap['plan'] | null;
  lastSeq: number;
  error: string | null;
}

export interface ConfirmPlanSessionResponse {
  mainTaskId: string;
  runId: string;
  workingDir: string;
  planVersion: number;
}

export interface AgentSettingsView {
  reviewEnabled: boolean;
  reviewMaxReworks: number;
  updatedAt: string | null;
}

export interface RunSnapshot {
  run: {
    runId: string;
    mainTaskId: string;
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

interface GitCheckpointView extends PayloadMap['git_checkpoint'] {
  seq: number;
  timestamp: string;
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
  lastCustomInput: string | null;
  summary: string | null;
  error: string | null;
  selectedOptionId: string | null;
  review: PayloadMap['review_completed'] | null;
  gitCheckpoints: GitCheckpointView[];
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
  planRevision: number;
  planReason: string | null;
  planChangedTaskIds: string[] | null;
  mainCreatedAt: string | null;
  mainUpdatedAt: string | null;
  planSession: PlanSessionView | null;
  planningConnection: ConnectionState;
  planningEvents: TaskNestEvent[];
  planningSeen: Record<string, true>;
  planningLastSeq: number;
}
```

P3 创建流程：`startPlanning(goal, workingDir)` 建立规划会话并连接规划 SSE；
`revisePlanning(instruction)` 调修订接口；`confirmPlanning()` 成功后以确认响应初始化运行态并
连接运行 SSE；`cancelPlanning()` 释放会话。
打开档案时 `plan` 来自 `MainTaskDetail.plan`，档案模式下不允许修订。

规划事件复用同一套信封解析、去重与 seq 处理；`plan_root` 仅用于展示，不进入 `subtaskNodes`。

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

P3 事件补充：

```text
plan:
  首次 → 建主任务与子任务；重排 → 保留已有节点，新增 PENDING，
         被移除的 PENDING → CANCELLED，记录 revision/reason/changedTaskIds
review_started:
  子任务非终态 → REVIEWING
review_completed:
  保存审核结论；REWORK → RETRYING；PASS 保持 REVIEWING 等 task_completed
git_checkpoint:
  追加到任务级时间线，不改变任何状态
```

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
- 档案模式：只读、不重连、不提交、不启动编排；退出后回到实时工作台；
- 规划会话结束（确认/取消/失败）：关闭规划 SSE、清理定时器与缓冲；
- 切换主任务或新建会话：先取消未确认的规划会话，避免长期占用工作区锁；
- 页面卸载：尽力调用取消接口（`navigator.sendBeacon` 或同步关闭连接），随后由服务端锁超时兜底。

---

## 11. 非功能需求

| 编号 | 类别 | 要求 |
| --- | --- | --- |
| NFR-01 | 性能 | 本机环境下，创建运行接口 P95 < 200ms；1,000 条历史事件补发 < 500ms；事件到达至 UI 更新 < 100ms（心跳除外）。 |
| NFR-02 | 可靠性 | 断线重连后事件不丢、不重复应用；乱序与重复投递下最终状态一致；SSE 连接 1 小时不因空闲断开。 |
| NFR-03 | 容量 | 单实例支持 ≥10 个并发运行、每运行 ≥10 个订阅者、每运行 ≥10,000 条事件。 |
| NFR-04 | 兼容性 | Java 17+、Spring Boot 3.x、Maven；Node 20+、Vite、Vue 3、TypeScript strict；Chrome/Edge 最新版。 |
| NFR-05 | 安全 | 默认绑定 `127.0.0.1`；CORS 仅白名单开发源；文件工具限制在仓库内并拒绝敏感路径；shell 为本地白名单/黑名单双层策略；审核 Agent 无写权限；无鉴权是本期明确接受的约束。 |
| NFR-06 | 可观测性 | 后端日志包含 `runId`、`seq`、`type`、连接生命周期；前端控制台记录重复/缺口/解析失败；UI 事件日志可调试。 |
| NFR-07 | 可维护性 | 契约单一来源；事件类型枚举化；Controller 无业务逻辑；关键逻辑有测试；无裸字符串事件类型。 |
| NFR-08 | 可访问性 | 键盘可操作、aria 标签、对比度 AA、减少动效支持。 |
| NFR-09 | 禁止项 | 不得引入 Spring AI / 多模型路由 / RAG / 向量库 / 用户体系 / Redis / 消息队列 / 微服务；不得执行远程 Git 操作；不实现 token 成本预算。模型请求只发往已配置的 OpenAI 兼容 Base URL。 |
| NFR-10 | 状态与持久化 | 实时运行、规划会话与 SSE 游标仍在内存，后端重启后不能续传，前端必须优雅处理 404；主任务、计划表、子任务、提示词、中间结果、事件原文、规划会话、审核结论与 Agent 设置写入 MySQL，可在主任务目录中只读查询。 |

---

## 12. 测试与验收

### 12.1 后端测试（`mvn test`）

必须覆盖：

| 测试 | 内容 |
| --- | --- |
| 事件序列化 | 17 种事件逐字段序列化/反序列化；枚举输出小写下划线；空 payload 为 `{}`；`task` 为 null 的情况。 |
| seq 单调性 | 并发发布 N 条事件后，seq 集合恰为 1..N，无重复、无空洞；事件表按 seq 升序。 |
| eventId | 按 seq 生成且唯一；重复 eventId 写入不影响 seq。 |
| 补发 | `afterSeq=N` 只返回 seq > N；N=lastSeq 时只接实时；非法 N 返回 400。 |
| 心跳 | 连接后立即收到；间隔约 15s；占用 seq；无订阅者时不产生；终态后连接关闭。 |
| 抉择 | 正常提交 → 202 + `input_received` + 分支继续；错误任务/选项/状态 → 404/409；重复提交 → 409。 |
| 快照 | `run + mainTask + subtasks + events`；两层结构；无 plan 时主任务仍为 `PENDING`；终态修复。 |
| 编排器 | 缺少计划、工作目录或原分支时拒绝启动；正常启动后按依赖顺序执行并产生完整事件序列。 |
| 断连 | 客户端断开后编排继续，事件仍可补发。 |
| 包装体 | 所有非 SSE 响应为 `{code, message, data}`；异常被统一处理。 |
| 持久化 | 创建主任务后写入 `tn_main_task`；`plan` 写入直属子任务；`prompt_assembled` / 思考 / 工具调用写入对应表；事件原文写入 `tn_event`。 |
| 档案查询 | `GET /api/main-tasks` 按创建时间倒序；`GET /api/main-tasks/{id}` 返回主任务、计划表、子任务、提示词、中间结果与事件；未知 ID → 40403。 |
| 计划确认与持久化 | 规划会话确认后写入主任务与计划表；确认前不产生主任务；详情/列表暴露正确版本。 |
| 计划修订与持久化 | 运行中可修订已保存计划，版本 +1 且追加记录；终态后 → 40904。 |
| 修订语义 | 修订计划后实际子任务数量不变。 |
| 两层校验 | 合法计划产生 1 个主任务 + 直属子任务；更深层级或第二个根 → `PLAN_INVALID_OUTPUT`。 |
| 目录浏览 | 只返回文件夹，不返回文件；path 省略时使用用户主目录；文件路径/不存在路径 → 40004。 |
| 工作目录 | 规划会话创建时校验并规范化绝对真实路径，确认后详情与列表回传；非法目录 → 40004；非仓库根 → 40012。 |
| LLM 设置 | 模型名称默认 `gpt-4o` 并与连接设置一同落库；GET 只返回密钥掩码；LITERAL 留空沿用旧值；ENV 读取进程环境并报告解析状态；非法 URL / 空模型名 / 变量名 → 40001。 |
| 设置加载与热更新 | 模拟重启重新加载数据库设置；PUT 成功后运行时快照立即变化。 |
| Agent 设置 | 默认开启审核、最多重做 2 次；PUT 越界（-1 / 6）→ 40001；保存后运行时快照立即生效。 |
| 规划会话 | 创建后状态为 RUNNING/READY；非 Git 目录 → 40011；非仓库根 → 40012；脏工作区 → 40014；并发同仓库 → 40910；确认前无主任务；取消释放锁。 |
| 规划输入 | `waiting_input` 后提交选项/自由输入 → 202 并收到 `input_received`；非等待状态 → 40901；未知选项 → 40902；空输入 → 40006。 |
| 规划修订 | READY 才能修订；revision 递增；非 READY → 40905；空指令 → 40001。 |
| 计划解析 | 合法两层计划通过；更深层级、重复 ID、未知依赖、环、非法工具名 → PLAN_INVALID_OUTPUT。 |
| 动态重排 | 只能改/删 PENDING；删除的 PENDING → CANCELLED；依赖被取消任务 → 40907；已完成任务保留；revision 与 reason 正确。 |
| 工具沙箱 | 绝对路径、`..` 逃逸、敏感文件、工作区外符号链接均被拒绝；允许的读写落在仓库内。 |
| Shell 策略 | 危险命令（`rm -rf`、`git push`、`git reset --hard` 等）被拦截；审核命令只放行测试/lint/build/类型检查/只读 Git；`mvn -f pom.xml test` 允许。 |
| Git 编排 | 主分支创建/子分支创建/提交/`--no-ff` 合并/删除/回滚的 `git_checkpoint` 顺序正确；冲突时保留分支并报 40912。 |
| 审核 Agent | 平台测试失败强制 REWORK；BLOCKER/MAJOR 强制 REWORK；模型返回非法 JSON → 重试一次后 REVIEW_INVALID_OUTPUT；无写工具。 |
| 查询豁免 | 审核只读 Git 与测试命令不改变工作区；测试产生新文件时判定 REVIEW_SIDE_EFFECT。 |

SSE 集成测试可以使用 `WebTestClient` 异步请求或直接对 `SseService` 做单元测试；
至少必须覆盖事件序列化与 seq 单调性（`todo.md` 的底线）。

### 12.2 前端检查

- `npm run build` 必须通过（`vue-tsc` / tsc 严格模式）；
- `npm run lint` 必须通过（ESLint）；
- 应当使用 Vitest 覆盖 store 的纯函数 reducer：
  - 重复 eventId；
  - 乱序 → 缓冲 → 补齐；
  - 缺口 → 快照恢复（测试替身）；
  - thought 增量拼接；
  - waiting_input / input_received；
  - 终态修复；
  - 快照 + 增量去重；
  - 主任务/子任务双状态投影；
  - 档案回放只读、不建立 SSE；
  - 规划 SSE 的 de-dup / seq / 重连；
  - 计划重排：PENDING 移除 → CANCELLED，新增任务 → PENDING；
  - `review_started` / `review_completed` / `git_checkpoint` 投影；
  - 自由输入与 `allowCustom=false` 的提交校验。

### 12.3 端到端验收清单

| 编号 | 对应 todo 验收项 | 步骤 | 预期 |
| --- | --- | --- | --- |
| AT-01 | 确认规划后收到完整事件流含 heartbeat | 创建规划会话并确认取 runId；`curl -N /api/runs/{runId}/events?afterSeq=0` | 收到 run_started → plan → … → run_completed；至少 1 条 heartbeat；字段完整；终态后连接关闭。 |
| AT-02 | waiting_input 提交后继续 | 在规划或执行中触发 `ask_user`，分别提交选项与自由输入 | 无异常；waiting 状态可观测；`input_received` 到达后任务继续。 |
| AT-03 | 仅凭事件流还原两层任务结构 | 确认一个包含多个直属子任务的计划，观察树 | 1 个主任务 + 直属子任务；主任务状态 PENDING → RUNNING → COMPLETED，子任务 PENDING → RUNNING → SUCCESS；图标与文案正确。 |
| AT-04 | 思考增量拼接与提示词三段展示 | 执行一个真实子任务，选中任务 | thought 为多条 delta 拼接；prompt 的 system/user/context/final 完整；工具调用配对并显示耗时。 |
| AT-05 | kill SSE 后自动重连、afterSeq 续传、无重复丢失 | 运行中关闭连接（DevTools 离线 3s 或关闭 curl 后再连） | 自动重连；先拉快照；从 lastSeq 续传；树无重复节点；thought 无重复；最终状态正确。 |
| AT-06 | 开发态模拟断线后 UI 不崩、最终一致 | 运行中使用“模拟断线”，随后恢复 | 发生缺口与快照恢复；重复计数与解析失败可见；最终主任务与子任务状态与服务端一致；无白屏。 |
| AT-07 | docs/events.md 与前后端一致 | 对照检查 17 种类型、字段、错误码、状态机 | 三处一致；无遗漏、无多余。 |
| AT-08 | 模型请求仅走配置的兼容接口 | 使用已配置的真实模型；检查 pom 与日志 | 无 Spring AI；请求只发往配置的 Base URL；日志与响应不出现密钥明文。 |
| AT-09 | 运行丢失处理（补充） | 启动运行后重启后端，刷新/等待重连 | 实时快照 404；前端提示“运行已失效”；停止重连；不白屏。 |
| AT-10 | MySQL 持久化与档案查询 | 创建多个主任务（含 goal）；在左侧目录中打开历史条目；查询详情 | 列表含多个主任务与计划版本；详情含计划表、直属子任务、提示词、思考/工具调用与事件；打开档案为只读，不重新编排；重启后端后档案仍可打开。 |
| AT-11 | 计划表创建与修订 | 新建主任务：描述目标 → 开始规划 → 提示词增加风险复核 → 确认创建；打开日志弹窗；运行中再修订计划 | 未生成计划表不能确认；Markdown 表格正常渲染；确认后计划版本与修订记录落库；子任务不受修订影响；终态后修订提示只读。 |
| AT-12 | 通知与日志弹窗 | 分别触发等待抉择、子任务失败与成功；观察通知与「查询日志」 | 等待抉择/失败通知为粘性，成功通知自动关闭；点击通知定位子任务；提交后等待通知关闭；日志弹窗默认隐藏 heartbeat，支持多选过滤与滚动。 |
| AT-13 | 工作目录选择与持久化 | 新建主任务时打开目录选择器，逐级进入并选中 Git 仓库根目录；确认后查看头部、列表与详情；重启后端后再打开档案 | 必须选择仓库根；弹窗只列文件夹；确认响应、列表与详情回传同一绝对路径；重启后仍可查询。 |
| AT-14 | LLM / Agent 设置保存、加载与热更新 | 修改模型名称，分别保存 LITERAL 与 ENV；切换审核开关与重做次数；重启后端 | 设置持久化；模型名称默认 `gpt-4o`；LITERAL 只显示掩码且留空沿用；ENV 显示解析状态；重启自动加载；保存后无需重启生效。 |
| AT-15 | 规划会话闭环 | 选择 Git 仓库根目录 → 输入目标 → 观察规划 SSE → 回答歧义 → 修订计划 → 确认 | 规划阶段出现思考/只读工具/`waiting_input`；回答后可继续；计划为两层并含验收标准；确认后创建主任务且 SSE 收尾；取消后不产生主任务且锁释放。 |
| AT-16 | 真实执行与 Git 分支 | 在演示仓库执行一个两子任务计划 | 主分支 `tasknest/<mainTaskId>` 创建；每个子任务有独立分支；成功后 `--no-ff` 合并并删除子分支；最后合并回原始分支；`git_checkpoint` 时间线完整。 |
| AT-17 | 审核退回与重做 | 开启审核，提交一个测试失败或缺少验收的实现 | 收到 `review_started` 与 `review_completed(REWORK)`；子任务进入 `RETRYING` 后重新执行；超过上限 `FAILED`；分支被丢弃。 |
| AT-18 | 动态重排 | 让第一个子任务产出与计划冲突的结论 | 主 Agent 发出新的 `plan`（revision+1、reason）；被移除的 `PENDING` 任务显示“已取消”；已完成任务状态保持；后续按新依赖执行。 |
| AT-19 | 安全边界 | 让 Agent 尝试读取 `.env`、写出工作区、执行 `git push` | 工具返回错误事件；不泄露密钥；工作区外无文件写入；远程分支无变化。 |
| AT-20 | 审查命令白名单 | 计划 `testCommand` 分别为 `npm test`、`mvn -f pom.xml test`、`git push origin main` | 前两者执行；第三者被策略拦截为 `UNAVAILABLE` 且不执行。 |

### 12.4 验收补充规则

- 短运行中心跳可能很少；由于连接后立即发送一次心跳，AT-01 仍可验证。
- 观察 `RETRYING` 时，事件日志应能看到 `task_failed(retryable=true)`，且随后出现同一任务的 `task_started`。
- 断线恢复场景的“最终一致”以刷新后拉快照仍得到相同的主任务与子任务状态为准。

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
      orchestrator/              TaskOrchestrator、CodingTaskOrchestrator、
                                 agent/、llm/、tool/、review/、GitService、PlanRevisionService
      persistence/               MyBatis-Plus 实体 / Mapper / Service、事件落库、档案查询
      api/                       控制器与 DTO
    src/main/resources/
      application.yml
      schema.sql                 MySQL / H2 共用建表脚本（tn_ 前缀）
    src/test/java/com/tasknest/ 单元/集成测试（H2，MODE=MySQL）
  frontend/
    src/
      api/                       events.ts、client.ts、sse.ts
      stores/                    taskTree.ts
      components/                AppHeader、TaskDirectory、CreateTaskPanel、TaskWorkspace、
                                 SubtaskDetail、PlanSheet、EventLogDialog、MarkdownView、
                                 DirectoryPickerDialog、SettingsDialog
      views/                     WorkbenchView.vue
      App.vue、main.ts
    package.json、vite.config.ts、tsconfig*.json、eslint 配置
```

### 13.2 完成定义（DoD）

- [ ] 第 12.3 节 AT-01 ~ AT-20 全部通过；
- [ ] `mvn test` 通过，覆盖事件序列化、seq 单调性、两层投影、规划会话、Git、审核与持久化档案；
- [ ] `npm run build` 与 `npm run lint` 通过；
- [ ] `docs/events.md` 与 Java 枚举 / TS 类型 / 本文第 6 章一致；
- [ ] 没有 Spring AI / Redis / 消息队列 / 微服务拆分；模型请求只走配置的 Base URL；
- [ ] 主任务与子任务使用独立状态枚举，前端分别显示标签；
- [ ] `tn_main_task`、`tn_subtask`、`tn_prompt`、`tn_task_artifact`、`tn_event`、
      `tn_plan_session`、`tn_plan_session_event`、`tn_agent_setting`、`tn_task_review`
      均可查询到数据；工作目录与 LLM 设置同样落库；
- [ ] 没有无阶段标记的 TODO 空壳代码；
- [ ] 后端构造器注入、控制器只做校验与转发；
- [ ] 前端所有业务状态变更集中在 store；
- [ ] 断线、乱序、重复投递场景均验证通过；
- [ ] 文件工具、shell 与审核命令的拒绝路径均有测试覆盖。

---

## 14. 设计决策、风险与待确认问题

### 14.1 已决设计决策（评审时请确认）

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| D-01 | heartbeat 是正式事件：进入事件表、占用 seq、连接后立即发送一次、之后每 15s；仅在有订阅者时产生。 | 保证 `afterSeq` 续传不会因心跳造成误判缺口；立即心跳让“含 heartbeat”验收稳定可测。 |
| D-02 | `plan.tasks[]` 包含根任务；每份计划恰好一个根，其余任务必须直接挂在根下；根任务映射主任务，根任务也发送自己的 `task_started` / `task_completed`。 | 固定两层结构，主任务状态可由事件完整投影且不做隐式聚合；更深层级在计划校验阶段拒绝。 |
| D-03 | `tool_call` / `tool_result` 增加可选 `callId`；缺省时按 `taskId + toolName` FIFO 配对。 | 解决同名工具并发调用的配对歧义，同时向后兼容 `todo.md` 的最小字段集。 |
| D-04 | 断线重连采用“先拉快照 → 再以 `afterSeq` 续传”；活跃连接中的 seq 缺口则直接拉快照。 | 同时满足 `todo.md` 5.2 的“先重建再增量”和 3.4 的续传要求，覆盖事件丢失场景。 |
| D-05 | `run_completed` → 运行 `COMPLETED`（即使有任务失败）；`error` → 运行 `FAILED`。 | 区分“运行流程结束”和“系统级失败”；任务失败是任务级信息。 |
| D-06 | 前端只保留“目标 + Git 目录 + 规划会话”这一条创建链路。 | 真实执行由规划确认后启动，避免维护第二条演示链路。 |
| D-07 | 运行终态后 SSE 冲刷完即关闭，前端不再重连。 | 避免无意义的长连接与重连循环。 |
| D-08 | 重复 eventId 只跳过语义，不阻塞 seq 游标；未知类型同样推进游标。 | 防止幂等去重导致永久缺口和反复快照。 |
| D-09 | 乱序缓冲窗口 2s。 | 常规网络抖动可在窗口内补齐；真实丢失在 2s 后触发快照恢复。 |
| D-10 | 开发态“模拟断线”只断开前端 `EventSource`，不停止后端编排。 | 用于验证快照恢复和游标续传，且不影响真实执行。 |
| D-11 | 服务端在第一次 `plan` 前不创建子任务节点；快照始终有 `mainTask`（`PENDING`），`subtasks` 可为空数组。 | 主任务在创建时已存在，子任务随 `plan` 出现；前端已有空态。 |
| D-12 | 主任务与子任务使用两套状态枚举，分别持久化到 `tn_main_task` / `tn_subtask`。 | 状态语义不同（主任务无重试、子任务无 COMPLETED），分开处理避免前端与快照产生歧义。 |
| D-13 | MySQL 使用 MyBatis-Plus 的 `BaseMapper` + `IService` / `ServiceImpl` 基础方法，不写复杂 XML。 | 满足基础实体管理需求，保持实现轻量。 |
| D-14 | 采用自定义 OpenAI 兼容 `/chat/completions` 客户端与函数工具调用，不引入 Spring AI。 | 现有依赖无 Spring AI，Spring AI 1.0.x 要求 Boot 3.4+；自定义客户端可控且契约不扩散。 |
| D-15 | 真实执行只由 `CodingTaskOrchestrator` 承担，不存在脚本编排器或兼容运行入口。 | 单一实现避免事件契约与状态语义分叉。 |
| D-16 | 规划会话与运行共用事件信封与 SSE 语义，`runId = planSessionId`。 | 前端复用解析、去重、游标与重连逻辑，减少分叉。 |
| D-17 | 子任务失败不终止运行；只有 Git 冲突、回滚失败或系统错误终止运行。 | 与“主 Agent 汇总 + 动态重排”一致，尽量完成可执行任务。 |
| D-18 | 审核结论以平台测试证据为准，模型只能补充问题清单。 | 防止模型伪报 PASS；测试与副作用检测可复核。 |
| D-19 | 动态重排只动 `PENDING`；删除即 `CANCELLED`。 | 避免重写历史、重复执行已完成任务与 Git 分支冲突。 |

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
| 时间戳不可信/时钟回拨 | 排序错误 | 只用 seq 排序；timestamp 仅展示。 |
| 模型输出不可控 | 计划/代码/审核 JSON 不合法 | 严格解析 + 有限重试 + 确定性失败；审核以平台证据为准。 |
| 模型请求超时或不可用 | 规划停滞、运行失败 | 规划会话进入 `FAILED` 并释放锁；子任务按可重试失败处理；汇总降级为确定性摘要。 |
| Agent 误改工作区 | 用户代码丢失 | 每次尝试独立分支；失败丢弃分支；主任务失败回滚主分支并恢复原始分支。 |
| 合并冲突 | 运行无法完成 | 保留双方分支，报告 `GIT_MERGE_CONFLICT`，由用户手工处理；绝不强推。 |
| 审核测试耗时过长 | 长时间占用工作区 | 审核测试 10 分钟超时，运行总超时 60 分钟。 |
| 规划会话泄漏锁 | 仓库无法再次使用 | 确认/取消/失败均释放；工作区锁带 owner 与进程内清理。 |
| 自由输入注入提示词 | 诱导 Agent 越权 | 输入作为数据注入，不改变系统约束；工具层独立校验路径与命令。 |

### 14.3 待确认问题

| 编号 | 问题 | 默认方案 |
| --- | --- | --- |
| Q-01 | 是否需要在刷新页面后恢复当前运行？ | 本期不恢复；后续可用 `sessionStorage` 保存 runId，并支持 URL 直达。 |
| Q-02 | 是否需要“取消运行”接口？ | 本期不提供；`todo.md` 未列，避免扩大范围。 |
| Q-03 | 事件日志是否需要导出？ | 本期不提供；可复制单条 JSON。 |
| Q-04 | 是否使用 Vite 代理还是后端 CORS 直连？ | 推荐 Vite 代理，同时后端保留白名单 CORS。 |
| Q-05 | 是否把 `callId` 升级为必填？ | P3 真实 Agent 必须生成 `callId`；契约仍按可选处理以兼容历史事件。 |
| Q-06 | 审核关闭时是否保留一次瞬时重试？ | 保留：Agent/模型瞬时失败按可重试处理一次，审核重做次数独立计算。 |
| Q-07 | 规划会话是否需要历史列表？ | P3 不提供；只支持按 ID 打开，后续再加会话库。 |

---

## 15. 附录

### 15.1 完整事件序列示例（真实运行节选）

```text
seq  type               task              payload 摘要
1    run_started        null              goal="为订单服务增加缓存层并补测试"
2    plan               t_root            tasks=[t_root, t_inspect, t_cache, t_test]
3    task_started       t_root            {}
4    task_started       t_inspect         {}
5    task_thought       t_inspect         delta="先定位订单查询入口..."
6    prompt_assembled   t_inspect         system/user/context/final
7    tool_call          t_inspect         toolName="fs.find", callId="call_001"
8    tool_result        t_inspect         status="success", durationMs=120
9    tool_call          t_inspect         toolName="fs.read", callId="call_002"
10   tool_result        t_inspect         status="success", durationMs=18
11   git_checkpoint     t_inspect         action="committed", branch="tasknest/subtasks/...-a1"
12   review_started     t_inspect         attempt=1
13   review_completed   t_inspect         verdict="PASS", testStatus="PASSED"
14   git_checkpoint     t_inspect         action="merged", targetBranch="tasknest/<mainTaskId>"
15   task_completed     t_inspect         summary="已定位查询入口并补充缓存键说明"
...
16   task_started       t_cache           {}
...
N-5  task_completed     t_cache           summary="缓存层与失效逻辑已完成"
N-4  task_started       t_test            {}
N-3  task_completed     t_test            summary="测试通过，覆盖命中与失效"
N-2  git_checkpoint     t_root            action="merged", targetBranch="<originalBranch>"
N-1  task_completed     t_root            summary="缓存层与测试已合并回原分支"
N    run_completed      null              summary="全部子任务完成"
（heartbeat 可在任意位置插入，占用 seq）
```

任务失败或审核退回时，同一任务会先出现 `task_failed(retryable=true)`，
再次 `task_started` 时 `attempt` 递增并使用新的子任务分支。

### 15.2 `todo.md` 与本文档映射

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

**文档结束。** 评审通过后，建议按以下顺序落地：`docs/events.md` → 后端事件模型与 SSE 骨架 → 规划会话与真实 Agent 编排 → 前端 store → 前端组件 → 端到端验收。
