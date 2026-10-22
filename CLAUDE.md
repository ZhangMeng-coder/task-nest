# CLAUDE.md

TaskNest（任务巢）是本地 Agent 任务工作台。本文件给后续协作的 Claude 提供项目约束、
命令与架构定位；产品验收见 `todo.md`，事件字段以 `docs/events.md` 为准。

## 协作流程（每次接到需求先执行）

1. **先计划，再动手**：收到需求先给出简明执行计划，覆盖目标、范围、影响模块 / 文件、
   契约变更与验证方式；需求复杂或跨多个文件时先进入计划模式，确认后再修改代码。
2. **歧义必须提问**：目标、边界、交互、字段语义、兼容性或验收标准不明确时，集中列出
   问题并等待人工决定，禁止自行假设后直接实现。答案能从代码 / 文档中查证时先查证，
   不把可查证的问题抛给用户。
3. **契约先行**：需求同时涉及前后端时，先定义或修改契约，明确字段、类型、可空性、
   错误码、状态与示例；事件更新 `docs/events.md`，REST / DTO 更新
   `docs/requirements.md`，并同步后端 record / DTO 与前端 `api/events.ts` /
   `api/client.ts`。契约确认后再实现前端与后端。
4. **以契约为准**：实现与契约冲突时，先修改契约并同步上述各处，再改实现；禁止前后端
   各自形成临时约定。完成后逐字段核对后端类型、前端类型与文档一致，并按命令章节验证。

## 阶段边界（先读）

当前处于 **P3 软件开发 Agent 阶段**（设计见 `docs/p3-agent-platform.md`，已实现）：

- 允许：SSE 规划会话、主 / 子 / 审核 Agent、动态重排、工具调用（目录 / 查找 / 读取 /
  创建 / 写入 / 本地 shell / ask_user）、本地 Git 分支模型、独立审核 Agent 与测试执行、
  Vue 规划与审核界面；MySQL 持久化规划会话、审核结论与 Agent 设置。
- 禁止：Spring AI `ChatClient` / `TaskTool`（现有依赖无 Spring AI，且 1.0.x 要求
  Boot 3.4+；改用项目内 OpenAI 兼容 `/chat/completions` 客户端与函数工具调用）；
  多模型路由、RAG、向量库；用户体系、权限、登录；消息队列、Redis、微服务拆分；
  远程 Git 操作（fetch / pull / push / remote / force push）；token 成本预算。
- 不写空壳 TODO。阶段标记写成 `// P4: ...`。
- 实时运行与规划会话状态仍在内存 `RunStore` / `PlanSessionStore` 中；进程重启后不可
  续传，但已写入 MySQL 的主任务档案、规划会话与审核结论仍可查询，前端会显示
  “运行已失效”并在左侧目录中提供历史档案。

### 任务层级

- 只允许两层：一个主任务 + 其直属子任务。规划输出出现更深层级时计划被拒绝。
- 主任务与子任务分开建表、分开处理状态：主任务用 `MainTaskStatus`
  （`PENDING/RUNNING/WAITING_INPUT/COMPLETED/FAILED`），子任务用 `SubtaskStatus`
  （`PENDING/RUNNING/WAITING_INPUT/RETRYING/REVIEWING/SUCCESS/FAILED/CANCELLED`）。
- 快照结构为 `run + mainTask + subtasks + events`，不再返回递归 `taskTree`。
- 执行由唯一的 `CodingTaskOrchestrator` 承担，只通过规划会话确认后启动。
  `TaskOrchestrator` 接口、SSE 层与事件信封保持不变。

## 命令

```bash
# 后端（127.0.0.1:8080）
# 数据源按 TASKNEST_MYSQL_* 读取，回退到 MYSQL_*；默认库 task_nest。
# 密码只放环境变量，不要写进项目文件。
cd backend
mvn test          # 使用 test profile 的 H2（MODE=MySQL）
mvn spring-boot:run

# 前端（127.0.0.1:5173）
cd frontend
npm install
npm run dev
npm run build     # vue-tsc --noEmit + vite build
npm run lint      # eslint --max-warnings=0
```

前端 API 基地址由 `VITE_API_BASE` 控制，默认 `http://127.0.0.1:8080`，见
`frontend/.env.example`。

## 架构

### 后端

```text
api/MainTaskController        REST：主任务列表、持久化详情、已保存计划修订
api/PlanSessionController     REST：规划会话创建 / 查询 / 输入 / 修订 / 确认 / 取消 / SSE
api/DirectoryController       REST：本机目录浏览（只列文件夹，供工作目录选择器）
api/SettingsController        REST：读取 / 更新 LLM 连接与 Agent 审核设置
api/RunController             REST：运行快照、提交抉择、SSE
service/RunService            用例编排、mainTaskId / runId 生成、工作目录校验、启动前保存计划
service/PlanningSessionService 规划会话生命周期、Git 前置校验、工作区锁、确认创建
service/PlanDraftService      确认稿持久化、已保存计划修订、运行期计划更新
service/AgentSettingsService  审核开关与最大重做次数，启动加载、保存后热更新
service/DirectoryBrowserService 本地目录解析、根目录 / 父目录 / 子目录列表
service/LlmSettingsService    启动加载 MySQL 设置、保存后热更新运行时快照、密钥掩码 / 环境变量解析
orchestrator/CodingTaskOrchestrator  执行：依赖调度、Git 分支、审核、动态重排
orchestrator/agent/            PlanParser、PlanningAgent、SubAgent、MainAgent、AgentLoop、PromptFactory
orchestrator/llm/              OpenAI 兼容 /chat/completions 客户端、函数工具调用、模型设置
orchestrator/tool/             ToolExecutor、WorkspaceSandbox、CommandPolicy、ReviewCommandPolicy
orchestrator/review/           ReviewAgent、ReviewTestRunner、审核上下文与结果
orchestrator/GitService       本地分支 / 提交 / 合并 / 回滚；WorkspaceLockService 仓库锁
orchestrator/PlanRevisionService 动态重排校验与计划合并
event/                        事件信封、payload record、EventType、EventPublisher
sse/SseService, SseConnection  SseEmitter、15s 心跳、历史补发、按 seq 推送
sse/PlanSessionSseService      规划会话 SSE
store/RunStore, RunState      内存运行、两层任务投影、快照
persistence/entity            MyBatis-Plus 实体（主任务 / 计划 / 工作目录 / 子任务 / 提示词 /
                              中间结果 / 事件 / LLM 设置 / 规划会话 / Agent 设置 / 审核）
persistence/mapper            BaseMapper
persistence/service           IService + ServiceImpl 基础实体管理
persistence/RunPersistenceService   事件 -> MySQL 映射（事务）、创建时保存工作目录与审核
persistence/MainTaskQueryService    主任务列表与详情（MySQL 查询，含计划版本 / 工作目录）
```

- `CodingTaskOrchestrator` 是唯一 `TaskOrchestrator` 实现：要求已确认的计划、工作目录
  与原始分支；遇 `waiting_input` 阻塞等待 `submitInput`。
- 真实执行流程：创建 `tasknest/<mainTaskId>` 分支 → 按 `dependsOn` 串行跑子任务 →
  每次尝试建 `tasknest/subtasks/<mainTaskId>/<taskId>-a<n>`（与主任务分支同级，
  Git ref 不能同时是文件与目录）→ 提交 → 可选审核 → `--no-ff` 合并 →
  删除子分支 → 全部完成后合并回原始分支。失败丢弃子分支；非冲突失败回滚主分支；
  合并冲突保留双方分支并以 `GIT_MERGE_CONFLICT` 终止。
- Agent 通过自定义 OpenAI 兼容客户端调用函数工具；`WorkspaceSandbox` 限制在仓库根，
  敏感路径（`.env`、密钥、`.ssh`、`.aws`、`.git`）与越界路径一律拒绝；shell 仅本地，
  由 `CommandPolicy` 拦截危险命令；审核 Agent 无写工具，shell 额外受
  `ReviewCommandPolicy` 限制为测试 / lint / build / 类型检查 / 只读 Git。
- 审核由平台先跑测试（计划 `testCommand`，`none` 时自动探测），再让模型输出严格 JSON；
  平台证据优先，测试失败 / 超时 / 产生工作区副作用强制 `REWORK`。
- 动态重排只允许修改或删除 `PENDING` 子任务；被移除的 `PENDING` 标记 `CANCELLED`，
  新计划发布新的 `plan`（`revision` 递增）。`PlanRevisionService` 负责依赖与环校验。
- 事件先进入内存事件列表并更新投影，再同步落库，最后进入 SSE 连接队列，保证快照和
  `afterSeq` 补发一致；
  `EventPublisher` 同步写 MySQL，持久化失败只记日志，不阻断 SSE。
- 任务创建只能走规划会话：`POST /api/main-tasks/plan-sessions`（`goal + workingDir`）
  → 规划 SSE → `waiting_input` / 修订 → `confirm` 才创建主任务。
  规划期间持有仓库工作区锁；确认前重新校验 Git 并把当前分支记为原始分支。
  规划事件写入 `tn_plan_session_event`，会话状态写入 `tn_plan_session`。
- 计划表单独存 `tn_main_task_plan`（与主任务一对一），不放进 `tn_main_task`，
  避免既有库的 `CREATE TABLE IF NOT EXISTS` 无法补列。规划会话确认时把
  `title + rows + revisions` 作为确认稿保存；后端据此生成 Markdown。
  运行中的档案修订只更新计划表（`POST /api/main-tasks/{id}/plan/revisions`），
  不会增删已运行的子任务；`COMPLETED/FAILED` 后返回 `40904 PLAN_READ_ONLY`。
- `schema.sql` 使用 `CREATE TABLE IF NOT EXISTS`，MySQL 与 H2（MODE=MySQL）共用；
  表前缀 `tn_`。事件原文以 `LONGTEXT` 保存，供档案账本回放。
- 工作目录保存到一对一的 `tn_main_task_workspace`，不向既有 `tn_main_task` 补列；
  创建时后端校验目录存在并把路径规范化为绝对真实路径，未选择则不写记录。
- LLM 设置保存在单例表 `tn_llm_setting`（`id=1`）；`base_url` 为 OpenAI 兼容 Base URL，
  `api_key_type` 为 `LITERAL` / `ENV`。模型名称保存在配套单例表
  `tn_llm_setting_model`（`id=1`，默认 `gpt-4o`，最多 128 字），避免对既有表补列。
  `LlmSettingsService` 用 `@PostConstruct` 启动加载两张表，`PUT /api/settings/llm`
  在同一事务写库后立即替换 volatile 运行时快照。literal 密钥写入数据库但读取接口只返回
  掩码，日志不得输出密钥；ENV 模式只保存变量名并在读取时解析。
- Agent 审核设置保存在单例表 `tn_agent_setting`（`id=1`）：`review_enabled` 与
  `review_max_reworks`（0–5，默认开启 / 2）；`PUT /api/settings/agent` 保存后立即热更新。
  审核结论写入 `tn_task_review`，失败只记日志，不阻断合并。
- `GET /api/fs/directories` 只返回目录（不读文件内容），用于页面工作目录选择器；浏览器
  `showDirectoryPicker()` 拿不到绝对路径，因此目录浏览必须走后端。
- `SseEmitter` 超时 1 小时；`application.yml` 的 `tasknest.sse.heartbeat-ms` 控制心跳。
- REST 统一返回 `{code, message, data}`；错误码在 `common/ErrorCode.java`。

### 前端

```text
api/events.ts       17 种事件类型与严格解析 + 主任务档案 / 计划 / 规划会话 / 审核 DTO
api/sse.ts          EventSource 封装：关闭原生重连、指数退避、afterSeq、开发态断线模拟
api/client.ts       REST 客户端与 ApiError（规划会话、创建、计划、列表、详情、抉择、设置）
stores/taskTree.ts  唯一领域状态机：事件 → 主任务 + 子任务 / 计划 / 审核 / Git / 账本 / 档案
components/         AppHeader / TaskDirectory / CreateTaskPanel（规划会话） /
                    TaskWorkspace / SubtaskDetail（审核 / Git 面板）/ PlanSheet /
                    EventLogDialog / MarkdownView / DirectoryPickerDialog / SettingsDialog
views/WorkbenchView.vue  目录 + 工作区编排、规划 / 创建流程、Element Plus 通知
styles/             element-plus.css（主题变量、弹窗 / 通知 / Markdown 排版）
ui/labels.ts        中文文案、双状态标签、时间与耗时格式化
ui/directory.ts     目录条目类型（MySQL 档案 + 当前实时运行合并）
```

- 组件只读 store 或调用 store action，状态变更集中在 `stores/taskTree.ts`；
  目录筛选、创建面板草稿、展开项等纯 UI 状态留在组件内。
- 主任务与子任务节点分开建模，界面固定两层；不再递归渲染任务树。
- 正式工作台：左侧主任务目录（MySQL 列表 + 当前运行），右侧任务工作区；
  子任务用 `el-collapse` 展开，计划表单独页签，事件账本用 `el-dialog` 弹窗。
- 创建流程：目标 + Git 工作目录 → `POST plan-sessions` → 连接规划 SSE →
  展示规划思考 / 只读工具 / 抉择 / 计划表 → 提示修订 → `confirm` 创建主任务并切换到运行 SSE。
  工作目录通过 `DirectoryPickerDialog` 调用 `/api/fs/directories` 选择，不用浏览器目录 API。
- 规划与运行共用事件解析、去重、seq 缓冲与重连；`plan_root` 不做成子任务节点。
- 子任务展示 `REVIEWING` / `CANCELLED`；审核面板展示 verdict、问题、测试证据；
  Git 时间线按 `git_checkpoint` 展示分支 / 提交 / 合并 / 回滚；重排后刷新计划行。
- 顶部“设置”打开 `SettingsDialog`，含“LLM 连接”与“Agent 审核”页签：Base URL、模型名称
  （自由文本，默认 `gpt-4o`）、`LITERAL` / `ENV` 单选、密钥或变量名；审核开关与最大重做
  次数（0–5）。保存后后端立即应用，已保存的 literal 密钥以掩码展示且留空不覆盖。
- 通知由 `WorkbenchView` 监听新增事件触发：子任务 `task_completed` 成功、
  `task_failed(retryable=false)` 失败（粘性）、`waiting_input` 等待抉择（粘性）；
  按 `eventId` 去重，档案模式与快照重放不通知，提交选择或运行结束后关闭等待通知。
  相邻通知错开 420ms 创建，避免 Element Plus 堆叠 offset 相同。
- 事件处理顺序：按 `eventId` 去重 → 按 `seq` 缓冲排序 → 应用语义 → 写入账本。
- 缺口超过约 2s 或重连前：先 `GET /api/runs/{runId}` 拉快照重建，再用
  `afterSeq=lastSeq` 续传；重连使用 1s 起、×2、30s 封顶、0–20% 抖动的退避。
- `waiting_input` 通知可点击定位到对应子任务并展开；提交后可继续。
- 目录读取 `GET /api/main-tasks`，打开条目用 `GET /api/main-tasks/{id}`；档案为只读模式，
  不建立 SSE、不提交抉择，计划表与事件账本由持久化数据重建。
- 账本默认隐藏 heartbeat、按事件类型多选过滤；保留重复行用于核对去重。
- 设计系统：浅色绘图纸工作台 + 深色账本/页头，信号橙 `#ee5a2a`，直角、细线网格、
  Bahnschrift / Aptos / Cascadia 字体栈；中文文案，支持键盘焦点与 reduced motion。

## 事件不变量

- 信封：`{eventId, runId, seq, type, timestamp, task, payload}`；17 种类型见
  `docs/events.md`。`seq` 从 1 严格递增，心跳也占号。规划会话的 `runId = planSessionId`。
- `plan` 首次建立两层任务；动态重排再次发送并带 `revision/reason/changedTaskIds`，
  被移除的 `PENDING` 子任务进入 `CANCELLED`。
- `review_started` → `REVIEWING`；`review_completed(REWORK)` → `RETRYING`，
  `PASS` 后由 `task_completed` 收尾；`git_checkpoint` 只记录时间线，不改变状态。
- `task_thought.delta` 追加；`prompt_assembled` 可能在重试时多次出现，前端保留历史并可切换。
- `tool_call` / `tool_result` 按 `callId` 配对，缺失时按工具名 FIFO；任务终止后未返回的
  调用显示“未返回”，无对应调用的结果显示“孤立结果”。
- `run_completed` / `error` 终止运行；子任务 `task_failed(retryable=true)` 只把子任务置为
  `RETRYING`，不是运行终止；主任务失败没有重试语义，直接 `FAILED`。
- 根任务事件更新 `tn_main_task`，直属子任务事件更新 `tn_subtask`；非直属任务不落库。
- 开发态的“模拟断线”只断前端 EventSource，不停止后端编排，用于验证快照恢复和游标续传。

## 编码约定

- Java 17+、构造器注入、record / sealed / enum；Controller 只做校验与转发。
- TypeScript strict；REST 包装与 SSE 解析都要做运行时字段校验，坏事件只记录不崩溃。
- 所有新增事件字段必须同时更新后端 record、`frontend/src/api/events.ts` 与
  `docs/events.md`。
- 提交前至少跑：`mvn test`、`npm run build`、`npm run lint`。
