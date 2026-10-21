# TaskNest 任务巢

本地 Agent 任务工作台：先为一次主任务生成并确认 Markdown 计划表，再把执行过程拆成
可观察的直属子任务，用 SSE 进度、思考增量、提示词拼装、工具调用记录、抉择通知和
事件账本完整摊开 Agent 的执行过程。

当前是 **P0–P2 展示层 + MySQL 持久化阶段**：后端只运行代码化的 Mock 编排器，
不连接任何真实大模型。实时运行状态仍在内存中，但主任务、计划表、子任务、提示词、
中间结果和事件原文会同步写入 MySQL，可在左侧主任务目录中打开历史档案。

界面采用上下 + 左右布局：顶部是工作台标题与统计，左侧是主任务目录，右侧是任务工作区。
新建主任务时先在多行文本框中描述目标，系统强制生成 Markdown 计划表；确认后才会创建
任务并开始运行。子任务完成、失败或等待抉择时，会通过 Element Plus 通知提醒。

## 技术栈

- 后端：Spring Boot 3.3.5 / Java 17+ / Maven
- 前端：Vue 3.5 / TypeScript strict / Vite 5 / Pinia 2
- 传输：SSE（事件流）+ REST（创建主任务、快照、提交抉择）
- 存储：内存 `RunStore` + MySQL（MyBatis-Plus）；测试使用 H2（MODE=MySQL）

## 快速开始

需要 Java 17+、Maven 3.9+、Node.js 18+、MySQL 8。

先准备数据库，并为后端设置环境变量（密码不要写进仓库）：

```bash
# 只创建空库；表由 backend/src/main/resources/schema.sql 首次启动时自动创建
mysql -h <host> -u <user> -p -e "CREATE DATABASE IF NOT EXISTS task_nest CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

export TASKNEST_MYSQL_HOST=<host>
export TASKNEST_MYSQL_PORT=3306
export TASKNEST_MYSQL_DB=task_nest
export TASKNEST_MYSQL_USERNAME=<user>
export TASKNEST_MYSQL_PASSWORD=<password>
```

也可以直接使用 `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USERNAME` / `MYSQL_PASSWORD`，
数据库名默认 `task_nest`。`TASKNEST_MYSQL_*` 优先。

```bash
# 1. 启动后端（127.0.0.1:8080）
cd backend
mvn spring-boot:run

# 2. 另开终端启动前端（127.0.0.1:5173）
cd frontend
npm install
npm run dev
```

浏览器打开 <http://127.0.0.1:5173>（端口被占用时 Vite 会自动顺延，后端 CORS 已允许本机
任意开发端口）。从左侧「新建主任务」进入创建流程：选择演练脚本、
描述目标、生成计划表，可用提示词修订后确认创建。左侧目录同时列出 MySQL 中已保存的
主任务；打开历史条目是只读档案，不会建立 SSE，也不会重启编排。

任务详情固定分两个页签：

- **子任务**：按计划顺序展开，查看思考、提示词拼接版本、工具调用与抉择面板。
- **计划表**：展示随主任务保存的 Markdown 计划，并可在运行中继续用提示词修订。
  修订只更新档案中的计划表，不会删除或追加已经运行的子任务。

右上角「查询日志」打开事件账本弹窗，默认隐藏 `heartbeat`，支持按事件类型多选过滤，
并保留重复事件行供核对去重。

前端默认请求 `http://127.0.0.1:8080`。如需改动，复制 `frontend/.env.example` 为
`frontend/.env.local`，设置 `VITE_API_BASE`（不要带结尾斜杠）。

## 演练脚本

| 脚本 | 覆盖场景 |
| --- | --- |
| `happy-path` | 两层结构（1 主任务 + 5 直属子任务），全部成功 |
| `with-ambiguity` | 中途 `waiting_input`，提交选择后继续不同分支 |
| `with-failure` | `task_failed(retryable=true)`，重试后成功 |
| `with-noise` | 延迟抖动、重复 `eventId`、调度乱序、丢弃投递 |

切换脚本后生成计划表；确认后才会开始运行。遇到待选任务时，可以点击通知或展开对应
子任务提交选择。任务层级固定为两层：plan 中唯一没有 `parentTaskId` 的任务是主任务，
其余任务必须直接挂在它下面；更深的层级会让脚本加载失败。

## REST / SSE 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/main-tasks/plan-drafts` | body `{"mockScript":"happy-path","goal":"目标"}`，生成计划表草案，不启动运行 |
| `POST` | `/api/main-tasks/plan-drafts/revise` | 用 `instruction` 修订尚未确认的草案 |
| `POST` | `/api/main-tasks` | body `{"mockScript":"happy-path","goal":"可选目标","plan":{...}}`，可选提交确认稿；创建主任务并启动运行 |
| `GET` | `/api/main-tasks?limit=50` | 主任务列表（含状态、计划版本与子任务完成数） |
| `GET` | `/api/main-tasks/{mainTaskId}` | 持久化详情：主任务、计划表、子任务、提示词、中间结果、事件原文 |
| `POST` | `/api/main-tasks/{mainTaskId}/plan/revisions` | body `{"instruction":"..."}`，修订已保存计划；运行结束后返回 `40904` |
| `POST` | `/api/runs` | 兼容入口，body `{"mockScript":"happy-path"}`，等同于创建主任务 |
| `GET` | `/api/runs/{runId}` | 实时快照：`run + mainTask + subtasks + events` |
| `GET` | `/api/runs/{runId}/events?afterSeq=N` | SSE 事件流，先补发 `seq > N` 的历史事件 |
| `POST` | `/api/runs/{runId}/inputs` | body `{"taskId":"...","selectedOptionId":"..."}`，提交抉择 |

REST 统一返回 `{code, message, data}`；SSE 的 `data:` 是完整事件信封。

命令行创建并看一眼事件流：

```bash
RESPONSE=$(curl -s -X POST http://127.0.0.1:8080/api/main-tasks \
  -H 'Content-Type: application/json' \
  -d '{"mockScript":"happy-path","goal":"生成一份竞品分析"}')
MAIN_TASK_ID=$(echo "$RESPONSE" | sed -E 's/.*"mainTaskId":"([^"]+)".*/\1/')
RUN_ID=$(echo "$RESPONSE" | sed -E 's/.*"runId":"([^"]+)".*/\1/')

curl -N "http://127.0.0.1:8080/api/runs/${RUN_ID}/events?afterSeq=0"
curl -s "http://127.0.0.1:8080/api/main-tasks/${MAIN_TASK_ID}"
```

完整事件契约（信封、14 种事件、payload、seq 规则、重连语义）见
[docs/events.md](docs/events.md)。产品需求与验收矩阵见
[docs/requirements.md](docs/requirements.md)。

## 项目结构

```text
task-nest/
  backend/
    src/main/java/com/tasknest/
      event/          事件契约（record / sealed / enum）
      orchestrator/   TaskOrchestrator + MockTaskOrchestrator + 脚本加载
      sse/            SseEmitter、心跳、历史补发、连接管理
      store/          内存运行状态、两层任务投影、快照
      persistence/    MyBatis-Plus 实体 / Mapper / IService、事件落库、档案查询
      api/            REST 控制器与 DTO
      service/        RunService、PlanDraftService（计划生成 / 修订 / 持久化）
    src/main/resources/
      mock/           四个 Mock 事件脚本
      schema.sql      MySQL / H2 共用的建表脚本（表前缀 tn_）
  frontend/src/
    api/              REST 客户端、事件解析、SSE 手动重连
    stores/taskTree.ts  事件 → 主任务 + 子任务 / 计划表 / 账本 / 只读档案的状态机
    components/      页头、主任务目录、创建与计划草案、任务工作区、
                     子任务详情、计划表、事件日志弹窗、Markdown 渲染
    views/           正式工作台主视图（目录 + 工作区）
    styles/          Element Plus 主题与 Markdown 排版覆盖
    ui/              中文文案、双状态标签、脚本选项、目录条目、时间格式化
  docs/              事件契约与需求文档
```

## 校验命令

```bash
cd backend && mvn test
cd frontend && npm run build && npm run lint
```

后端测试覆盖事件序列化、`seq` 单调性、SSE 历史补发、抉择闭环、两层任务投影、
计划表生成 / 修订 / 持久化与主任务档案持久化（H2，MODE=MySQL）；前端构建包含
`vue-tsc --noEmit`，ESLint 以 `--max-warnings=0` 运行。

## 事件流关键不变量

- `seq` 在单次运行内从 1 开始严格递增，心跳也占序号；前端按 `seq` 排序、按
  `eventId` 去重，发现缺口先拉快照再用 `afterSeq` 续传。
- `task_thought.delta` 追加，不做覆盖；`prompt_assembled` 按任务保留多次拼接记录。
- `waiting_input` 只暂停该次编排，不关闭 SSE；提交后由 `input_received` 恢复。
- 事件先写入内存再由 SSE 推送，断线期间不会丢掉已经产生的事件；同一条事件同步写入
  MySQL，持久化失败只记日志，不影响 SSE。
- 主任务和子任务使用独立状态枚举；主任务失败不重试，子任务 `retryable=true` 进入
  `RETRYING`。
- Mock 不会发起任何真实模型、`ChatClient` 或 `TaskTool` 调用。

## 数据表

| 表 | 内容 |
| --- | --- |
| `tn_main_task` | 主任务标题、目标、脚本、状态、摘要、等待抉择、时间 |
| `tn_main_task_plan` | 计划标题、目标、脚本、版本、Markdown、行数据、修订记录；与主任务一对一 |
| `tn_subtask` | 直属子任务、排序、依赖、状态、摘要、等待抉择、时间 |
| `tn_prompt` | 每次 `prompt_assembled` 的系统 / 用户 / 上下文 / 最终提示词 |
| `tn_task_artifact` | 思考增量、工具调用与回填的工具结果 |
| `tn_event` | 每条事件的原始 JSON，供档案账本回放 |

主任务档案是历史快照：实时运行仍依赖内存 `RunStore`，进程重启后不能续传，
但已落库的记录仍可通过左侧主任务目录查询。

## 后续阶段

- **P3**：实现 `SpringAiTaskOrchestrator`，在
  `MockTaskOrchestrator` 标注的替换点替换实现；`TaskOrchestrator` 接口、SSE 层和前端零改动。
- **P4**：运行中的断点续传、跨进程恢复与更完整的历史检索。
- **P5**：多模型路由、子 Agent 隔离与失败降级。

当前明确不做：真实模型接入、多模型路由、RAG、向量库、用户/权限/登录、
消息队列、Redis、微服务拆分。
