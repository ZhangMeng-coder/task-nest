# CLAUDE.md

TaskNest（任务巢）是本地 Agent 任务工作台。本文件给后续协作的 Claude 提供项目约束、
命令与架构定位；产品验收见 `todo.md`，事件字段以 `docs/events.md` 为准。

## 阶段边界（先读）

当前处于 **P0–P2 展示层 + MySQL 持久化阶段**，验证事件契约、前端工作台与历史档案：

- 允许：代码化 SSE 契约、Mock 编排器、SSE 出口、抉择回传、Vue 工作台；
  MySQL + MyBatis-Plus 持久化主任务、计划表、子任务、提示词、中间结果与事件原文。
- 禁止：接入真实大模型 / Spring AI `ChatClient` / `TaskTool`；多模型路由、RAG、向量库；
  用户体系、权限、登录；消息队列、Redis、微服务拆分。
- 不写空壳 TODO。阶段标记写成 `// P3: ...`。
- 实时运行状态仍在内存 `RunStore` 中；进程重启后运行不可续传，但已写入 MySQL 的
  主任务档案仍可查询，前端会显示“运行已失效”并在左侧目录中提供历史档案。

### 任务层级

- 只允许两层：一个主任务 + 其直属子任务。`mock/*.json` 中出现更深层级时脚本加载失败。
- 主任务与子任务分开建表、分开处理状态：主任务用 `MainTaskStatus`
  （`PENDING/RUNNING/WAITING_INPUT/COMPLETED/FAILED`），子任务用 `SubtaskStatus`
  （`PENDING/RUNNING/WAITING_INPUT/RETRYING/SUCCESS/FAILED`）。
- 快照结构为 `run + mainTask + subtasks + events`，不再返回递归 `taskTree`。

P3 的替换点是 `backend/.../orchestrator/MockTaskOrchestrator.java` 上方的
`// P3:` 注释：新增 `SpringAiTaskOrchestrator implements TaskOrchestrator`，替换该 Bean。
`TaskOrchestrator` 接口、SSE 层、事件契约和前端不应改动。

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
api/MainTaskController        REST：创建主任务、计划草案 / 修订、主任务列表、持久化详情
api/RunController             REST：运行快照、提交抉择、SSE
service/RunService            用例编排、mainTaskId / runId 生成、启动前保存计划
service/PlanDraftService      计划生成 / 提示词修订 / 持久化（确定性 Mock 实现）
orchestrator/                 TaskOrchestrator 接口、MockTaskOrchestrator、脚本加载
event/                        事件信封、payload record、EventType、EventPublisher
sse/SseService, SseConnection  SseEmitter、15s 心跳、历史补发、按 seq 推送
store/RunStore, RunState      内存运行、两层任务投影、快照
persistence/entity            MyBatis-Plus 实体（主任务 / 计划 / 子任务 / 提示词 / 中间结果 / 事件）
persistence/mapper            BaseMapper
persistence/service           IService + ServiceImpl 基础实体管理
persistence/RunPersistenceService   事件 -> MySQL 映射（事务）
persistence/MainTaskQueryService    主任务列表与详情（MySQL 查询，含计划版本）
```

- 单一 `TaskOrchestrator` 实现；`MockTaskOrchestrator` 读取
  `resources/mock/{script}.json`，按 `delayMs` 执行，遇 `waiting_input` 阻塞等待
  `submitInput`。
- 事件先进入内存事件列表并更新投影，再同步落库，最后进入 SSE 连接队列，保证快照和
  `afterSeq` 补发一致；
  `EventPublisher` 同步写 MySQL，持久化失败只记日志，不阻断 SSE。
- 计划表单独存 `tn_main_task_plan`（与主任务一对一），不放进 `tn_main_task`，
  避免既有库的 `CREATE TABLE IF NOT EXISTS` 无法补列。创建流程先调
  `POST /api/main-tasks/plan-drafts` 生成草案，可用 `plan-drafts/revise` 按提示修订，
  确认后把 `title + rows + revisions` 作为 `plan` 随创建请求提交；后端重新生成 Markdown。
  运行中的档案修订只更新计划表（`POST /api/main-tasks/{id}/plan/revisions`），
  不会增删已运行的子任务；`COMPLETED/FAILED` 后返回 `40904 PLAN_READ_ONLY`。
- `schema.sql` 使用 `CREATE TABLE IF NOT EXISTS`，MySQL 与 H2（MODE=MySQL）共用；
  表前缀 `tn_`。事件原文以 `LONGTEXT` 保存，供档案账本回放。
- `SseEmitter` 超时 1 小时；`application.yml` 的 `tasknest.sse.heartbeat-ms` 控制心跳。
- REST 统一返回 `{code, message, data}`；错误码在 `common/ErrorCode.java`。

### 前端

```text
api/events.ts       事件类型与严格解析（与后端逐字段对齐）+ 主任务档案 / 计划表 DTO
api/sse.ts          EventSource 封装：关闭原生重连、指数退避、afterSeq、开发态断线模拟
api/client.ts       REST 客户端与 ApiError（创建、计划草案 / 修订、列表、详情、抉择）
stores/taskTree.ts  唯一领域状态机：事件 → 主任务 + 子任务 / 计划 / 账本 / 只读档案
components/         AppHeader / TaskDirectory / CreateTaskPanel / TaskWorkspace /
                    SubtaskDetail / PlanSheet / EventLogDialog / MarkdownView
views/WorkbenchView.vue  目录 + 工作区编排、创建流程、Element Plus 通知
styles/             element-plus.css（主题变量、弹窗 / 通知 / Markdown 排版）
ui/labels.ts        中文文案、双状态标签、脚本选项、时间与耗时格式化
ui/directory.ts     目录条目类型（MySQL 档案 + 当前实时运行合并）
```

- 组件只读 store 或调用 store action，状态变更集中在 `stores/taskTree.ts`；
  目录筛选、创建面板草稿、展开项等纯 UI 状态留在组件内。
- 主任务与子任务节点分开建模，界面固定两层；不再递归渲染任务树。
- 正式工作台：左侧主任务目录（MySQL 列表 + 当前运行），右侧任务工作区；
  子任务用 `el-collapse` 展开，计划表单独页签，事件账本用 `el-dialog` 弹窗。
- 创建流程强制先生成 Markdown 计划表：选择脚本 + 目标 → `plan-drafts` →
  提示词修订 → 确认；确认后 `startRun(script, goal, draft)` 携带 rows/revisions。
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

- 信封：`{eventId, runId, seq, type, timestamp, task, payload}`；14 种类型见
  `docs/events.md`。`seq` 从 1 严格递增，心跳也占号。
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
