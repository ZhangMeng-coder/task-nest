# TaskNest 任务巢

[简体中文](README.md) | [English](README.en.md)

TaskNest 是一个**本地运行的软件开发 Agent 工作台**。它先通过 SSE 规划会话让主 Agent
分析仓库、生成并确认 Markdown 计划表，再在 Git 临时分支上逐个实现直属子任务；思考增量、
提示词拼装、工具调用、审核结论、Git 检查点、抉择通知和事件账本都会完整展示。

当前处于 **P3 软件开发 Agent 平台阶段**：默认使用真实 OpenAI 兼容模型，由主 Agent
规划与动态重排、子 Agent 使用受控文件 / 本地 Shell 工具实现、可选独立审核 Agent
自行执行测试。所有子任务使用独立临时分支，成功合并、退回或失败时删除分支。Mock
脚本链路保留，可用于演练异常事件流。

实时运行与规划会话状态保存在内存中；主任务、计划表、工作目录、子任务、提示词、中间
结果、事件原文、规划会话、审核结论与 Agent / LLM 设置会同步写入 MySQL，可在左侧主任务
目录中打开历史档案。

## 功能概览

- **规划会话**：创建主任务前，主 Agent 读取仓库、拆分两层任务，含糊处通过
  `waiting_input` 展示选项与自由输入框；计划可反复修订，确认后才启动执行。
- **两层任务**：一个主任务 + 直属子任务；主任务与子任务使用独立状态机和数据库表。
- **受控工具**：目录查询、文件查找、文件读取、文件创建、文件写入、本地 Shell、
  `ask_user`；所有文件操作限制在 Git 仓库根目录内。
- **Git 分支模型**：主任务分支 + 每轮子任务分支；成功 `--no-ff` 合并，退回或失败删除
  子分支，整体失败回滚主任务分支，合并冲突保留现场。
- **可选独立审核**：平台先在子任务分支上执行测试，再由只读审核 Agent 输出结构化结论；
  测试失败或产生工作区副作用强制返工。
- **动态重排**：主 Agent 可在子任务边界调整未开始的 `PENDING` 任务，计划版本递增。
- **完整可观测**：17 种 SSE 事件、事件账本、提示词版本、工具调用配对、审核面板、
  Git 时间线与历史档案只读回放。
- **Mock 演练**：四个脚本覆盖成功、抉择、失败重试、抖动 / 重复 / 乱序 / 丢投递。
- **本地设置**：LLM 连接与审核策略写入 MySQL，启动自动加载，保存后立即生效。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.3.5 / Java 17+ / Maven |
| 前端 | Vue 3.5 / TypeScript strict / Vite 5 / Pinia 2 / Element Plus |
| 传输 | SSE（运行事件 / 规划会话）+ REST |
| 模型 | 项目内 OpenAI 兼容 `/chat/completions` 客户端，支持函数工具调用 |
| 存储 | 内存 `RunStore` / `PlanSessionStore` + MySQL（MyBatis-Plus）；测试使用 H2 |
| 版本管理 | 本机 Git（仅本地分支、提交、合并与回滚） |

## 快速开始

### 前置条件

- Java 17+（开发环境使用 Java 21 运行）
- Maven 3.9+
- Node.js 18+
- MySQL 8
- 可执行的 `git`

### 1. 准备数据库

只创建空库，表结构由 `backend/src/main/resources/schema.sql` 在启动时自动创建：

```bash
mysql -h <host> -u <user> -p -e \
  "CREATE DATABASE IF NOT EXISTS task_nest CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

为后端设置环境变量（密码不要写进仓库）：

```bash
export TASKNEST_MYSQL_HOST=<host>
export TASKNEST_MYSQL_PORT=3306
export TASKNEST_MYSQL_DB=task_nest
export TASKNEST_MYSQL_USERNAME=<user>
export TASKNEST_MYSQL_PASSWORD=<password>
```

也可以使用 `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` / `MYSQL_USERNAME` /
`MYSQL_PASSWORD`，`TASKNEST_MYSQL_*` 优先。未设置时默认连接
`127.0.0.1:3306/task_nest`。

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

后端监听 `127.0.0.1:8080`。

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 <http://127.0.0.1:5173>。Vite 端口被占用时会自动顺延；后端 CORS 只放行
`localhost` / `127.0.0.1` 的任意端口。

前端默认请求 `http://127.0.0.1:8080`。如需修改，复制 `frontend/.env.example` 为
`frontend/.env.local`，设置 `VITE_API_BASE`（不要带结尾斜杠）：

```bash
VITE_API_BASE=http://127.0.0.1:8080
```

### 4. 首次使用

1. 在顶栏“设置 → LLM 连接”填写 Base URL、模型名称与 API Key；保存后立即生效。
2. 在“Agent 审核”页签决定是否启用独立审核，并设置最大返工次数。
3. 左侧点击“新建主任务”，选择 Git 仓库根目录，填写目标；可选用 Mock 演练脚本。
4. 等待规划 Agent 生成计划；如有提问，在选项或自由输入框中回答，也可用提示词修订计划。
5. 确认计划后创建主任务并开始执行。
6. 子任务完成、失败、等待抉择或审核退回时会弹出通知；可点击通知定位到对应子任务。

## 使用流程

```text
填写目标 + 选择 Git 工作目录
        ↓
SSE 规划会话：读取仓库 → 拆分两层计划 → 提问 / 修订
        ↓
确认计划（校验 Git 前置条件与仓库锁）
        ↓
创建主任务分支 → 按依赖串行执行子任务
        ↓
每个子任务：创建尝试分支 → Agent 实现 → 提交 → 可选审核
        ↓
通过（或关闭审核）→ --no-ff 合并 → 删除子分支
退回 / 失败       → 删除子分支 → 携带反馈重试或终止
        ↓
全部完成 → 主 Agent 汇总 → 合并主任务分支回原分支 → 删除主任务分支
```

主任务详情固定分两个页签：

- **子任务**：按计划顺序展开，查看思考、提示词拼接版本、工具调用、抉择面板、审核结论
  与 Git 检查点；被动态重排移除的 `PENDING` 子任务显示为“已取消”。
- **计划表**：展示随主任务保存的 Markdown 计划；动态重排后 `revision` 递增，界面会
  标注重排原因与变更任务。运行结束后计划表只读。

右上角“查询日志”打开事件账本弹窗，默认隐藏 `heartbeat`，支持按事件类型多选过滤，
并保留重复事件行供核对去重。打开左侧历史条目是只读档案，不会建立 SSE，也不会重启编排。

## 配置

### 数据源

后端只从环境变量读取数据库凭据，代码与仓库不保存密码：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `TASKNEST_MYSQL_HOST` | `MYSQL_HOST` 或 `127.0.0.1` | 数据库主机 |
| `TASKNEST_MYSQL_PORT` | `MYSQL_PORT` 或 `3306` | 数据库端口 |
| `TASKNEST_MYSQL_DB` | `MYSQL_DB` 或 `task_nest` | 数据库名 |
| `TASKNEST_MYSQL_USERNAME` | `MYSQL_USERNAME` 或 `root` | 用户名 |
| `TASKNEST_MYSQL_PASSWORD` | `MYSQL_PASSWORD` 或空 | 密码 |

### 前端

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VITE_API_BASE` | `http://127.0.0.1:8080` | 后端 API 基地址，不要带结尾斜杠 |

### LLM 连接

顶栏“设置 → LLM 连接”保存以下内容：

- **Base URL**：OpenAI 兼容地址，例如 `https://api.openai.com/v1`；请求实际发往
  `{Base URL}/chat/completions`。
- **模型名称**：自由文本，默认 `gpt-4o`，最多 128 字。
- **API Key 来源**：
  - `LITERAL`：直接填写密钥。密钥以明文保存在本机 MySQL，查询接口只返回掩码；
    留空保存时沿用已保存值；日志不输出密钥。
  - `ENV`：只保存环境变量名（如 `OPENAI_API_KEY`），后端每次读取配置时从自己的进程
    环境解析；设置弹窗会提示当前进程能否读到该变量。

设置写入 `tn_llm_setting` 与 `tn_llm_setting_model` 单例表，应用启动时自动加载；
`PUT /api/settings/llm` 写库成功后立即替换运行时快照。

### Agent 审核

同一弹窗的“Agent 审核”页签提供：

- **启用独立审核**：默认开启；关闭后子任务实现成功即合并，不产生审核事件。
- **最大返工次数**：0–5，默认 2。审核判定 `REWORK` 后回退分支并携带问题与测试输出重做；
  超过上限后子任务失败、删除分支并终止运行。

审核设置保存在 `tn_agent_setting` 单例表，`PUT /api/settings/agent` 保存后立即生效。

## Git 分支模型

| 分支 | 命名 | 生命周期 |
| --- | --- | --- |
| 原分支 | 规划确认时所在分支 | 执行期间不变；全部成功后接收主任务分支的 `--no-ff` 合并 |
| 主任务分支 | `tasknest/<mainTaskId>` | 从原分支起始提交创建；成功合并回原分支后删除；整体失败时回滚删除 |
| 子任务尝试分支 | `tasknest/subtasks/<mainTaskId>/<taskId>-a<n>` | 从主任务分支创建；审核通过（或关闭审核）后合并回主任务分支并删除；退回 / 失败 / 取消时删除 |

说明：

- 子任务分支使用与主任务分支**同级**的命名空间。Git ref 不能同时是文件与目录，因此
  不能写成 `tasknest/<mainTaskId>/...`。
- 每个子任务每次尝试都有独立分支；重做不会覆盖此前已合并的成功提交。
- 合并统一使用 `--no-ff`；合并冲突时中止合并、保留双方分支与提交现场，并以
  `GIT_MERGE_CONFLICT` 终止运行，不自动删除可恢复内容。
- 只允许本地 Git 操作；不执行 `fetch` / `pull` / `push` / `remote` / 强制推送。

## 执行模式与演练脚本

创建面板默认使用**真实 Agent**：主 Agent 读取仓库并生成计划，子 Agent 使用目录 / 查找 /
读取 / 创建 / 写入 / 本地 Shell / `ask_user` 工具实现任务。任一 Agent 遇到模糊选择时，
通过 `waiting_input` 推送选项与自由输入框。

也可以选择 Mock 脚本演练异常事件流：

| 脚本 | 覆盖场景 |
| --- | --- |
| `happy-path` | 两层结构（1 主任务 + 5 直属子任务），全部成功 |
| `with-ambiguity` | 中途 `waiting_input`，提交选择后继续不同分支 |
| `with-failure` | `task_failed(retryable=true)`，重试后成功 |
| `with-noise` | 延迟抖动、重复 `eventId`、调度乱序、丢弃投递 |

两种模式都先生成计划表，确认后才开始运行。任务层级固定为两层：plan 中唯一没有
`parentTaskId` 的任务是主任务，其余任务必须直接挂在它下面；更深的层级会让脚本加载失败。

## 安全边界

- **工作区**：工作目录必须是 Git 仓库根目录；同一个仓库同时只允许一个运行（仓库锁）。
- **文件沙箱**：`WorkspaceSandbox` 拒绝路径逃逸、符号链接逃逸与敏感路径
  （`.env`、密钥、`.ssh`、`.aws`、`.git` 等）。
- **命令策略**：Shell 只在本机执行；`CommandPolicy` 拦截格式化磁盘、关机、递归删除、
  `git reset --hard`、`git push --force`、管道下载执行等危险命令，并禁止子 Agent 执行
  Git 写操作。
- **审核隔离**：审核 Agent 没有文件写入工具；Shell 受 `ReviewCommandPolicy` 限制为测试 /
  构建 / lint / 类型检查 / 只读 Git，且不允许管道、重定向与命令串联。
- **密钥**：数据库密码只来自环境变量；LLM literal 密钥写入本机 MySQL 但接口只返回掩码，
  日志不输出密钥。
- **阶段边界**：不接入远程 Git、不作 token 成本预算；不包含多模型路由、RAG、向量库、
  用户体系 / 权限 / 登录、消息队列、Redis 或微服务拆分。

## REST / SSE 接口

REST 统一返回 `{code, message, data}`；SSE 的 `data:` 是完整事件信封。

### 主任务与规划

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/main-tasks/plan-sessions` | body `{"goal":"目标","workingDir":"仓库内路径","mockScript":"可选"}`，建立 SSE 规划会话 |
| `GET` | `/api/main-tasks/plan-sessions/{planSessionId}` | 规划会话快照（状态、计划、事件游标） |
| `GET` | `/api/main-tasks/plan-sessions/{planSessionId}/events?afterSeq=N` | 规划会话 SSE 事件流 |
| `POST` | `/api/main-tasks/plan-sessions/{planSessionId}/inputs` | 提交规划阶段抉择 `{taskId, selectedOptionId?, customInput?}` |
| `POST` | `/api/main-tasks/plan-sessions/{planSessionId}/revisions` | 用 `instruction` 修订规划草案 |
| `POST` | `/api/main-tasks/plan-sessions/{planSessionId}/confirm` | 校验 Git 前置条件并确认计划，创建主任务、启动运行 |
| `POST` | `/api/main-tasks/plan-sessions/{planSessionId}/cancel` | 取消规划会话，释放仓库锁，不创建主任务 |
| `POST` | `/api/main-tasks/plan-drafts` | 兼容入口：生成确定性计划草案 |
| `POST` | `/api/main-tasks/plan-drafts/revise` | 兼容入口：用 `instruction` 修订草案 |
| `POST` | `/api/main-tasks` | 兼容入口：直接创建主任务并启动运行 |
| `GET` | `/api/main-tasks?limit=50` | 主任务列表（状态、计划版本、工作目录、子任务完成数） |
| `GET` | `/api/main-tasks/{mainTaskId}` | 持久化详情：主任务、计划表、子任务、提示词、中间结果、事件原文 |
| `POST` | `/api/main-tasks/{mainTaskId}/plan/revisions` | 修订已保存计划；运行结束后返回 `40904 PLAN_READ_ONLY` |

### 运行与事件

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/runs` | 兼容入口，body `{"mockScript":"happy-path"}`，等同于创建主任务 |
| `GET` | `/api/runs/{runId}` | 实时快照：`run + mainTask + subtasks + events` |
| `GET` | `/api/runs/{runId}/events?afterSeq=N` | 运行 SSE，先补发 `seq > N` 的历史事件 |
| `POST` | `/api/runs/{runId}/inputs` | 提交运行抉择 `{taskId, selectedOptionId?, customInput?}` |

### 目录与设置

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/fs/directories?path=...&showHidden=false` | 浏览后端本机目录；只返回文件夹、父目录与根目录 |
| `GET` | `/api/settings/llm` | 读取 LLM 设置；literal 密钥只返回掩码 |
| `PUT` | `/api/settings/llm` | 保存 LLM 设置并立即更新运行时配置 |
| `GET` | `/api/settings/agent` | 读取审核开关、最大返工次数与更新时间 |
| `PUT` | `/api/settings/agent` | 保存审核设置并立即生效 |

### 命令行示例

```bash
# 兼容入口：用 Mock 脚本创建任务
RESPONSE=$(curl -s -X POST http://127.0.0.1:8080/api/main-tasks \
  -H 'Content-Type: application/json' \
  -d '{"mockScript":"happy-path","goal":"生成一份竞品分析"}')
MAIN_TASK_ID=$(echo "$RESPONSE" | sed -E 's/.*"mainTaskId":"([^"]+)".*/\1/')
RUN_ID=$(echo "$RESPONSE" | sed -E 's/.*"runId":"([^"]+)".*/\1/')

curl -N "http://127.0.0.1:8080/api/runs/${RUN_ID}/events?afterSeq=0"
curl -s "http://127.0.0.1:8080/api/main-tasks/${MAIN_TASK_ID}"
```

完整事件契约（信封、17 种事件、payload、`seq` 规则、重连语义、规划会话）见
[docs/events.md](docs/events.md)。产品需求与验收矩阵见
[docs/requirements.md](docs/requirements.md)，P3 设计见
[docs/p3-agent-platform.md](docs/p3-agent-platform.md)。这些设计文档目前为中文。

## 事件流关键不变量

- `seq` 在单次运行内从 1 开始严格递增，心跳也占序号；规划会话的 `runId` 等于
  `planSessionId`。
- 前端按 `eventId` 去重、按 `seq` 排序；发现缺口先拉快照，再用 `afterSeq` 续传。
  重连使用 1s 起、×2、30s 封顶、0–20% 抖动的退避。
- `task_thought.delta` 追加，不做覆盖；`prompt_assembled` 可按任务保留多次拼接记录。
- `tool_call` / `tool_result` 按 `callId` 配对；缺失时按工具名 FIFO，任务终止后未返回的
  调用显示“未返回”。
- `waiting_input` 只暂停该次编排，不关闭 SSE；提交后由 `input_received` 恢复。
- 事件先写入内存并更新投影，再同步写入 MySQL，最后进入 SSE 队列；持久化失败只记日志，
  不阻断 SSE。
- 主任务和子任务使用独立状态枚举；主任务失败不重试；子任务 `retryable=true` 进入
  `RETRYING`；审核中进入 `REVIEWING`；被动态重排移除的未开始子任务进入 `CANCELLED`。
- 审核结论由平台测试证据强制执行：测试失败或出现工作区副作用必定 `REWORK`，模型输出
  不能绕过。
- 动态重排只允许修改或删除 `PENDING` 子任务；计划 `revision` 递增并携带原因与变更任务。
- `run_completed` / `error` 终止运行；规划会话在确认或取消后终止并关闭 SSE。

## 数据模型

| 表 | 内容 |
| --- | --- |
| `tn_main_task` | 主任务标题、目标、脚本、状态、摘要、等待抉择、时间 |
| `tn_main_task_plan` | 计划标题、目标、脚本、版本、Markdown、行数据、修订记录；与主任务一对一 |
| `tn_main_task_workspace` | 主任务工作目录的绝对路径；与主任务一对一 |
| `tn_subtask` | 直属子任务、排序、依赖、状态、摘要、等待抉择、时间 |
| `tn_prompt` | 每次 `prompt_assembled` 的系统 / 用户 / 上下文 / 最终提示词 |
| `tn_task_artifact` | 思考增量、工具调用与回填的工具结果 |
| `tn_event` | 每条运行事件的原始 JSON，供档案账本回放 |
| `tn_llm_setting` | 单例 LLM 设置：Base URL、API Key 类型（LITERAL / ENV）与值 |
| `tn_llm_setting_model` | 单例模型名称；与 `tn_llm_setting` 配套 |
| `tn_plan_session` | 规划会话：工作目录、脚本、状态、计划与确认结果 |
| `tn_plan_session_event` | 规划会话事件原文，供刷新 / 重连补发 |
| `tn_agent_setting` | 单例审核设置：是否启用审核、最大返工次数 |
| `tn_task_review` | 每次审核的 verdict、问题、测试证据与耗时 |

所有表与字段均带中文注释，定义在 `backend/src/main/resources/schema.sql`。既有库升级时
执行一次
`backend/src/main/resources/db/migration/20261021_add_table_and_column_comments.sql`
即可补齐注释（新库无需执行）。`schema.sql` 使用 `CREATE TABLE IF NOT EXISTS`，MySQL 与
H2（MODE=MySQL）共用，事件原文以 `LONGTEXT` 保存。

主任务档案是历史快照：实时运行与规划会话状态仍在内存中，进程重启后不能续传，但已落库
的记录仍可通过左侧主任务目录查询；前端会提示“运行已失效”。

## 项目结构

```text
task-nest/
  backend/
    src/main/java/com/tasknest/
      api/            REST 控制器与 DTO
      event/          事件契约（record / sealed / enum）
      orchestrator/   CodingTaskOrchestrator（@Primary）、MockTaskOrchestrator、
                      planning/ 规划会话、agent/ 主 / 子 / 审核 Agent 与工具循环、
                      review/ 平台测试与审核、tool/ 沙箱与命令策略、GitService
      sse/            SseEmitter、心跳、历史补发、连接管理、规划 SSE
      store/          内存运行 / 规划会话状态、两层任务投影、快照
      persistence/    MyBatis-Plus 实体 / Mapper / IService、事件落库、档案查询
      service/        RunService、PlanDraftService、DirectoryBrowserService、
                      LlmSettingsService、AgentSettingsService
    src/main/resources/
      mock/           四个 Mock 事件脚本
      schema.sql      MySQL / H2 共用的建表脚本（表前缀 tn_）
      db/migration/   既有库迁移脚本（表 / 字段注释）
  frontend/src/
    api/              REST 客户端、事件解析、SSE 手动重连
    stores/taskTree.ts 运行与规划事件 → 主任务 + 子任务 / 计划 / 审核 / Git /
                       账本 / 只读档案的唯一状态机
    components/       页头、主任务目录、规划创建面板、任务工作区、子任务详情
                      （审核结论 / Git 时间线）、计划表、事件日志、设置弹窗、
                      Markdown 渲染、目录选择
    views/            正式工作台主视图（目录 + 工作区）
    styles/           Element Plus 主题与 Markdown 排版覆盖
    ui/               中文文案、双状态标签、脚本选项、目录条目、时间格式化
  docs/               事件契约、需求文档与 P3 设计
```

## 开发与校验

```bash
cd backend && mvn test
cd frontend && npm run build && npm run lint
```

- 后端测试使用 H2 `MODE=MySQL`，覆盖事件序列化、`seq` 单调性、SSE 历史补发、抉择闭环、
  两层任务投影、规划会话与计划表生成 / 修订 / 持久化、动态重排约束、工作区沙箱与敏感路径
  拒绝、审核命令白名单、取消时跳过测试、工作目录持久化与校验、目录浏览、LLM / Agent 设置
  落库 / 掩码 / 环境变量解析 / 启动加载与热更新。
- 前端构建包含 `vue-tsc --noEmit`；ESLint 以 `--max-warnings=0` 运行。

新增或修改事件字段时，必须同时更新后端 record、`frontend/src/api/events.ts` 与
`docs/events.md`；REST / DTO 变更同步更新 `docs/requirements.md` 与前端
`frontend/src/api/client.ts`。

## 故障排查

| 现象 | 排查方向 |
| --- | --- |
| 后端启动时报数据库连接失败 | 检查 `TASKNEST_MYSQL_*` / `MYSQL_*` 环境变量、数据库是否已创建、账号权限 |
| 创建任务提示 `LLM_NOT_CONFIGURED` | 在“设置 → LLM 连接”填写可用的 Base URL、模型与密钥；ENV 模式确认后端进程能读到变量 |
| 提示 `GIT_NOT_REPOSITORY` / `GIT_NOT_REPO_ROOT` | 工作目录必须是 Git 仓库根目录，且本机可执行 `git` |
| 提示 `GIT_DIRTY_WORKTREE` | 先提交或清理工作区；运行期间要求干净状态 |
| 提示 `GIT_DETACHED_HEAD` | 切换到普通分支后再创建任务 |
| 提示 `GIT_MERGE_CONFLICT` | 合并冲突已中止并保留双方分支，手工解决后再重新运行 |
| 提示“运行已失效” | 后端进程重启导致内存运行丢失；已落库档案仍可查看，重新创建任务即可 |
| 页面无法连接后端 | 检查 `VITE_API_BASE`、后端端口与 CORS（只允许本机来源） |

## 阶段状态

- **P0–P2（已完成）**：展示层、事件契约、SSE、MySQL 持久化与计划表工作台。
- **P3（已完成）**：SSE 规划会话、主 Agent 规划与动态重排、子 Agent 文件 / Shell
  工具、可选审核 Agent（独立执行测试）、本地 Git 分支回滚、选项 + 自由输入抉择。
- **P4（计划）**：运行中的断点续传、跨进程恢复与更完整的历史检索。
- **P5（计划）**：多模型路由、子 Agent 隔离与失败降级。

当前明确不做：远程 Git 操作、token 成本预算、多模型路由、RAG、向量库、用户 / 权限 /
登录、消息队列、Redis、微服务拆分。
