# TaskNest

[简体中文](README.md) | [English](README.en.md)

TaskNest is a **local software-development agent workbench**. It first runs an SSE planning
session where a main agent inspects the repository and produces a Markdown plan for approval.
It then implements each direct subtask on temporary Git branches. Thoughts, assembled prompts,
tool calls, review verdicts, Git checkpoints, decision requests, and the event ledger are all
exposed live.

The project is at the **P3 software-development agent stage**. By default it uses a real
OpenAI-compatible model: the main agent plans and dynamically replans, sub-agents implement
tasks with sandboxed file and local shell tools, and an optional independent reviewer adds a
structured review on top of platform test execution. Every subtask attempt uses its own
temporary branch; branches are merged on success and deleted on rework or failure. The mock
script path remains available for exercising abnormal event streams.

Live run and planning-session state is kept in memory. Main tasks, plans, workspaces, subtasks,
prompts, intermediate artifacts, raw events, planning sessions, review results, and agent / LLM
settings are persisted to MySQL, so completed work can be reopened as a read-only archive from
the task directory.

## Features

- **Planning session**: before a main task is created, the main agent inspects the repository
  and splits work into two levels. Ambiguities are surfaced through `waiting_input` with
  options and a free-text input. Plans can be revised repeatedly and execution starts only
  after confirmation.
- **Two-level tasks**: exactly one main task plus direct subtasks, with separate state
  machines and tables for each level.
- **Controlled tools**: directory listing, file search, file read, file create, file write,
  local shell, and `ask_user`. All file operations are confined to the Git repository root.
- **Git branch model**: one main-task branch plus one branch per subtask attempt. Successful
  work is merged with `--no-ff`; reworked or failed attempts are discarded; a failed run rolls
  back the main-task branch; merge conflicts keep the recovery state.
- **Optional independent review**: the platform runs tests on the subtask branch first, then a
  read-only reviewer returns a structured verdict. Failed tests or test side effects force a
  rework.
- **Dynamic replanning**: between subtasks, the main agent may adjust untouched `PENDING`
  tasks and increment the plan revision.
- **Full observability**: 17 SSE event types, event ledger, prompt versions, tool-call pairing,
  review panel, Git timeline, and read-only archive playback.
- **Mock drills**: four scripts covering success, decisions, retryable failure, and
  jitter / duplicate / out-of-order / dropped delivery.
- **Local settings**: LLM connection and review policy are stored in MySQL, loaded at startup,
  and applied immediately after saving.

## Tech stack

| Layer | Technology |
| --- | --- |
| Backend | Spring Boot 3.3.5 / Java 17+ / Maven |
| Frontend | Vue 3.5 / TypeScript strict / Vite 5 / Pinia 2 / Element Plus |
| Transport | SSE (run events / planning session) + REST |
| Model | In-project OpenAI-compatible `/chat/completions` client with function tool calling |
| Storage | In-memory `RunStore` / `PlanSessionStore` + MySQL (MyBatis-Plus); H2 for tests |
| Version control | Local Git only (branches, commits, merges, rollbacks) |

## Quick start

### Prerequisites

- Java 17+ (the development environment runs on Java 21)
- Maven 3.9+
- Node.js 18+
- MySQL 8
- A working `git` executable

### 1. Prepare the database

Create only an empty database. Tables are created automatically from
`backend/src/main/resources/schema.sql` on startup:

```bash
mysql -h <host> -u <user> -p -e \
  "CREATE DATABASE IF NOT EXISTS task_nest CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

Export the backend credentials as environment variables. Never commit the password:

```bash
export TASKNEST_MYSQL_HOST=<host>
export TASKNEST_MYSQL_PORT=3306
export TASKNEST_MYSQL_DB=task_nest
export TASKNEST_MYSQL_USERNAME=<user>
export TASKNEST_MYSQL_PASSWORD=<password>
```

`MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` are also
accepted. `TASKNEST_MYSQL_*` takes precedence. Without any variables, the defaults are
`127.0.0.1:3306/task_nest`.

### 2. Start the backend

```bash
cd backend
mvn spring-boot:run
```

The backend listens on `127.0.0.1:8080`.

### 3. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

Open <http://127.0.0.1:5173>. Vite automatically picks the next free port if 5173 is in use;
the backend allows any `localhost` / `127.0.0.1` port.

The frontend calls `http://127.0.0.1:8080` by default. To change it, copy
`frontend/.env.example` to `frontend/.env.local` and set `VITE_API_BASE` without a trailing
slash:

```bash
VITE_API_BASE=http://127.0.0.1:8080
```

### 4. First run

1. Open **Settings → LLM connection** in the header and fill in the Base URL, model name, and
   API key. Changes apply immediately.
2. Use the **Agent review** tab to enable or disable independent review and set the maximum
   number of reworks.
3. Click **New task**, choose the Git repository root, and describe the goal. A mock drill
   script is optional.
4. Wait for the planning agent to produce a plan. Answer any questions through the options or
   the free-text input, or revise the plan with an instruction.
5. Confirm the plan to create the main task and start execution.
6. Notifications appear when a subtask completes, fails, waits for a decision, or is sent back
   for rework. Click a notification to focus the relevant subtask.

## Workflow

```text
Enter goal + choose Git workspace
        ↓
SSE planning: inspect repository → split two-level plan → ask / revise
        ↓
Confirm plan (validate Git preconditions and acquire repository lock)
        ↓
Create main-task branch → execute subtasks serially by dependency
        ↓
Per subtask: create attempt branch → agent implements → commit → optional review
        ↓
PASS (or review disabled) → --no-ff merge → delete subtask branch
REWORK / failure          → delete subtask branch → retry with feedback or fail
        ↓
All done → main agent summarizes → merge main-task branch into original branch → delete it
```

The task workspace has two tabs:

- **Subtasks**: expanded in plan order, showing thoughts, prompt versions, tool calls,
  decision panels, review verdicts, and Git checkpoints. A `PENDING` task removed by dynamic
  replanning is shown as “Cancelled”.
- **Plan**: the Markdown plan saved with the main task. When the main agent replans during
  execution, `revision` increases and the UI highlights the reason and changed task IDs. The
  plan becomes read-only once the run ends.

**Query log** in the top-right corner opens the event ledger. `heartbeat` is hidden by default;
events can be filtered by type. Duplicate rows are intentionally retained so de-duplication can
be verified. Opening an archived main task from the left directory is read-only: it opens no SSE
connection and does not restart orchestration.

## Configuration

### Data source

The backend reads database credentials only from environment variables; passwords are never
stored in the repository.

| Variable | Default | Purpose |
| --- | --- | --- |
| `TASKNEST_MYSQL_HOST` | `MYSQL_HOST` or `127.0.0.1` | Database host |
| `TASKNEST_MYSQL_PORT` | `MYSQL_PORT` or `3306` | Database port |
| `TASKNEST_MYSQL_DB` | `MYSQL_DB` or `task_nest` | Database name |
| `TASKNEST_MYSQL_USERNAME` | `MYSQL_USERNAME` or `root` | Username |
| `TASKNEST_MYSQL_PASSWORD` | `MYSQL_PASSWORD` or empty | Password |

### Frontend

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_API_BASE` | `http://127.0.0.1:8080` | Backend API base URL, without a trailing slash |

### LLM connection

**Settings → LLM connection** stores:

- **Base URL**: an OpenAI-compatible endpoint such as `https://api.openai.com/v1`. Requests are
  sent to `{Base URL}/chat/completions`.
- **Model name**: free text, default `gpt-4o`, up to 128 characters.
- **API key source**:
  - `LITERAL`: the key is entered directly. It is stored in plain text in the local MySQL
    database; read APIs return only a mask. Leaving the field empty keeps the stored key.
    Logs never print the key.
  - `ENV`: only the environment variable name (for example `OPENAI_API_KEY`) is stored. The
    backend resolves it from its own process environment whenever settings are read. The
    settings dialog shows whether the variable is currently resolvable.

Settings are written to the `tn_llm_setting` and `tn_llm_setting_model` singleton tables and
loaded at startup. `PUT /api/settings/llm` writes the database and then replaces the runtime
snapshot.

### Agent review

The **Agent review** tab provides:

- **Enable independent review**: on by default. When disabled, a successful subtask is merged
  immediately and no review events are produced.
- **Maximum reworks**: 0–5, default 2. A `REWORK` verdict discards the branch and retries with
  the issues and test output; exceeding the limit fails the subtask, deletes the branch, and
  terminates the run.

Review settings live in the `tn_agent_setting` singleton table and take effect immediately
after `PUT /api/settings/agent`.

## Git branch model

| Branch | Name | Lifecycle |
| --- | --- | --- |
| Original branch | The branch checked out when the plan is confirmed | Unchanged during execution; receives a `--no-ff` merge of the main-task branch on full success |
| Main-task branch | `tasknest/<mainTaskId>` | Created from the original branch's starting commit; merged back and deleted on success; rolled back and deleted when the whole run fails |
| Subtask attempt branch | `tasknest/subtasks/<mainTaskId>/<taskId>-a<n>` | Created from the main-task branch; merged back and deleted after review passes (or review is disabled); deleted on rework, failure, or cancellation |

Notes:

- Subtask branches use a **sibling** namespace to the main-task branch. A Git ref cannot be both
  a file and a directory, so `tasknest/<mainTaskId>/...` is invalid when
  `tasknest/<mainTaskId>` also exists.
- Every subtask attempt gets a fresh branch; reworks do not overwrite earlier merged commits.
- Merges always use `--no-ff`. On conflict, the merge is aborted, both branches and the commit
  state are preserved, and the run fails with `GIT_MERGE_CONFLICT`; recoverable work is not
  deleted automatically.
- Only local Git operations are allowed. No `fetch`, `pull`, `push`, `remote`, or force push.

## Execution modes and mock scripts

The creation panel uses the **real agent** by default: the main agent inspects the repository
and produces the plan, while sub-agents implement work with directory, search, read, create,
write, local shell, and `ask_user` tools. Any agent can surface an ambiguity through
`waiting_input` with options and a free-text box.

Mock scripts can be selected to exercise abnormal event streams:

| Script | Scenario |
| --- | --- |
| `happy-path` | Two-level structure (1 main task + 5 direct subtasks), all successful |
| `with-ambiguity` | A mid-run `waiting_input`; execution branches after a choice is submitted |
| `with-failure` | `task_failed(retryable=true)` followed by a successful retry |
| `with-noise` | Jittered delays, duplicate `eventId`s, out-of-order scheduling, dropped delivery |

Both modes generate a plan first and start only after confirmation. The hierarchy is strictly
two levels: the single task without a `parentTaskId` is the main task, and every other task must
be its direct child. Deeper nesting makes mock scripts fail to load.

## Security boundaries

- **Workspace**: the working directory must be the Git repository root. Only one run per
  repository is allowed at a time (repository lock).
- **File sandbox**: `WorkspaceSandbox` rejects path escapes, symlink escapes, and sensitive
  paths such as `.env`, keys, `.ssh`, `.aws`, and `.git`.
- **Command policy**: the shell runs locally only. `CommandPolicy` blocks disk formatting,
  shutdown, recursive deletion, `git reset --hard`, `git push --force`, piping downloads into a
  shell, and similar dangerous commands. Sub-agents may not perform Git write operations.
- **Review isolation**: the reviewer has no file-write tools. Its shell is limited by
  `ReviewCommandPolicy` to tests, builds, lint, type checks, and read-only Git. Pipes,
  redirection, and command chaining are rejected.
- **Secrets**: database credentials come only from environment variables. A literal LLM key is
  stored in the local MySQL database but APIs return only a mask, and logs never print it.
- **Stage boundaries**: no remote Git operations and no token-cost budgeting. Multi-model
  routing, RAG, vector stores, user accounts / permissions / login, message queues, Redis, and
  microservices are out of scope.

## REST / SSE API

All REST responses use `{code, message, data}`. SSE `data:` lines contain the full event
envelope.

### Main tasks and planning

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/main-tasks/plan-sessions` | Body `{"goal":"...","workingDir":"...","mockScript":"optional"}`; starts an SSE planning session |
| `GET` | `/api/main-tasks/plan-sessions/{planSessionId}` | Planning-session snapshot (status, plan, event cursor) |
| `GET` | `/api/main-tasks/plan-sessions/{planSessionId}/events?afterSeq=N` | Planning SSE stream |
| `POST` | `/api/main-tasks/plan-sessions/{planSessionId}/inputs` | Submit a planning decision `{taskId, selectedOptionId?, customInput?}` |
| `POST` | `/api/main-tasks/plan-sessions/{planSessionId}/revisions` | Revise the draft with an `instruction` |
| `POST` | `/api/main-tasks/plan-sessions/{planSessionId}/confirm` | Validate Git preconditions, confirm the plan, create the main task, and start the run |
| `POST` | `/api/main-tasks/plan-sessions/{planSessionId}/cancel` | Cancel the session, release the repository lock, create no main task |
| `POST` | `/api/main-tasks/plan-drafts` | Compatibility endpoint: generate a deterministic plan draft |
| `POST` | `/api/main-tasks/plan-drafts/revise` | Compatibility endpoint: revise a draft with an `instruction` |
| `POST` | `/api/main-tasks` | Compatibility endpoint: create a main task and start it directly |
| `GET` | `/api/main-tasks?limit=50` | Main-task list (status, plan revision, workspace, completed subtasks) |
| `GET` | `/api/main-tasks/{mainTaskId}` | Persisted detail: main task, plan, subtasks, prompts, artifacts, raw events |
| `POST` | `/api/main-tasks/{mainTaskId}/plan/revisions` | Revise the saved plan; returns `40904 PLAN_READ_ONLY` after the run ends |

### Runs and events

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/runs` | Compatibility endpoint, body `{"mockScript":"happy-path"}`; equivalent to creating a main task |
| `GET` | `/api/runs/{runId}` | Live snapshot: `run + mainTask + subtasks + events` |
| `GET` | `/api/runs/{runId}/events?afterSeq=N` | Run SSE; replays history with `seq > N` first |
| `POST` | `/api/runs/{runId}/inputs` | Submit a run decision `{taskId, selectedOptionId?, customInput?}` |

### Directories and settings

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/fs/directories?path=...&showHidden=false` | Browse local directories on the backend host; returns folders, parent, and roots only |
| `GET` | `/api/settings/llm` | Read LLM settings; a literal key is returned masked |
| `PUT` | `/api/settings/llm` | Save LLM settings and update the runtime configuration immediately |
| `GET` | `/api/settings/agent` | Read review enablement, maximum reworks, and update time |
| `PUT` | `/api/settings/agent` | Save review settings and apply them immediately |

### curl example

```bash
# Compatibility path: create a task with a mock script
RESPONSE=$(curl -s -X POST http://127.0.0.1:8080/api/main-tasks \
  -H 'Content-Type: application/json' \
  -d '{"mockScript":"happy-path","goal":"Draft a competitive analysis"}')
MAIN_TASK_ID=$(echo "$RESPONSE" | sed -E 's/.*"mainTaskId":"([^"]+)".*/\1/')
RUN_ID=$(echo "$RESPONSE" | sed -E 's/.*"runId":"([^"]+)".*/\1/')

curl -N "http://127.0.0.1:8080/api/runs/${RUN_ID}/events?afterSeq=0"
curl -s "http://127.0.0.1:8080/api/main-tasks/${MAIN_TASK_ID}"
```

The complete event contract (envelope, 17 event types, payloads, `seq` rules, reconnect
semantics, and the planning session) is documented in [docs/events.md](docs/events.md).
Requirements and acceptance matrices are in [docs/requirements.md](docs/requirements.md), and
the P3 design is in [docs/p3-agent-platform.md](docs/p3-agent-platform.md). These design
documents are currently written in Chinese.

## Event invariants

- `seq` starts at 1 and increases strictly within a run; heartbeats consume sequence numbers
  too. A planning session uses `planSessionId` as its `runId`.
- The frontend de-duplicates by `eventId` and orders by `seq`. On a gap it fetches a snapshot
  first, then resumes with `afterSeq`. Reconnects back off from 1s, doubling to a 30s cap, with
  0–20% jitter.
- `task_thought.delta` is appended, never replaced. `prompt_assembled` may appear multiple
  times per task and every version is retained.
- `tool_call` / `tool_result` pair by `callId`; when missing, they pair by tool name in FIFO
  order. A call that never returns before the task ends is shown as “not returned”.
- `waiting_input` pauses only that piece of orchestration; the SSE stream stays open. The
  subsequent `input_received` resumes it.
- Events are appended to memory and applied to the projection first, persisted to MySQL
  synchronously, and then queued for SSE. Persistence failures are logged without blocking SSE.
- Main tasks and subtasks have separate status enums. Main-task failures are not retried.
  A subtask with `retryable=true` enters `RETRYING`; review moves it to `REVIEWING`; a removed
  `PENDING` task enters `CANCELLED`.
- Review verdicts are enforced by platform evidence: failed tests or workspace side effects
  force `REWORK`; model output cannot override this.
- Dynamic replanning may only modify or remove `PENDING` subtasks. The plan `revision`
  increases and carries the reason and changed task IDs.
- `run_completed` / `error` end the run. A planning session ends and closes its SSE stream on
  confirmation or cancellation.

## Data model

| Table | Contents |
| --- | --- |
| `tn_main_task` | Main-task title, goal, script, status, summary, pending decision, timestamps |
| `tn_main_task_plan` | Plan title, goal, script, revision, Markdown, rows, revisions; one-to-one with the main task |
| `tn_main_task_workspace` | Absolute workspace path; one-to-one with the main task |
| `tn_subtask` | Direct subtasks: order, dependencies, status, summary, pending decision, timestamps |
| `tn_prompt` | System / user / context / final prompts for each `prompt_assembled` event |
| `tn_task_artifact` | Thought deltas, tool calls, and returned tool results |
| `tn_event` | Raw JSON of every run event, used for archive playback |
| `tn_llm_setting` | Singleton LLM settings: Base URL, API key type (LITERAL / ENV), and value |
| `tn_llm_setting_model` | Singleton model name, paired with `tn_llm_setting` |
| `tn_plan_session` | Planning session: workspace, script, status, plan, confirmation result |
| `tn_plan_session_event` | Raw planning events, used for refresh / reconnect replay |
| `tn_agent_setting` | Singleton review settings: enabled flag and maximum reworks |
| `tn_task_review` | Verdict, issues, test evidence, and duration for each review |

All tables and columns carry Chinese comments in
`backend/src/main/resources/schema.sql`. For an existing database, run
`backend/src/main/resources/db/migration/20261021_add_table_and_column_comments.sql` once to
backfill the comments; fresh databases need no migration. `schema.sql` uses
`CREATE TABLE IF NOT EXISTS` and is shared by MySQL and H2 (MODE=MySQL); raw events are stored
as `LONGTEXT`.

A persisted main task is a historical snapshot. Live run and planning-session state remains in
memory and cannot be resumed after a process restart, but persisted records remain queryable
from the left-hand directory. The UI marks such runs as “run expired”.

## Project structure

```text
task-nest/
  backend/
    src/main/java/com/tasknest/
      api/            REST controllers and DTOs
      event/          Event contract (records / sealed types / enums)
      orchestrator/   CodingTaskOrchestrator (@Primary), MockTaskOrchestrator,
                      planning/ planning session, agent/ main, sub, and reviewer agents plus
                      the tool loop, review/ platform tests and review, tool/ sandbox and
                      command policies, GitService
      sse/            SseEmitter, heartbeat, history replay, connections, planning SSE
      store/          In-memory run / planning state, two-level projection, snapshots
      persistence/    MyBatis-Plus entities / mappers / IServices, event persistence, archives
      service/        RunService, PlanDraftService, DirectoryBrowserService,
                      LlmSettingsService, AgentSettingsService
    src/main/resources/
      mock/           Four mock event scripts
      schema.sql      Shared MySQL / H2 schema (tn_ prefix)
      db/migration/   Migration scripts for existing databases (table / column comments)
  frontend/src/
    api/              REST client, event parsing, manual SSE reconnect
    stores/taskTree.ts Single domain state machine: run and planning events → main task +
                       subtasks / plan / review / Git / ledger / read-only archive
    components/       Header, task directory, planning creation panel, task workspace,
                       subtask detail (review / Git timeline), plan sheet, event log,
                       settings dialog, Markdown renderer, directory picker
    views/            Main workbench view (directory + workspace)
    styles/           Element Plus theme and Markdown typography overrides
    ui/               Chinese labels, dual status tags, script options, directory entries,
                       time and duration formatting
  docs/               Event contract, requirements, and P3 design
```

## Development and verification

```bash
cd backend && mvn test
cd frontend && npm run build && npm run lint
```

- Backend tests use H2 `MODE=MySQL` and cover event serialization, `seq` monotonicity, SSE
  history replay, the decision loop, two-level projection, planning-session and plan
  generation / revision / persistence, dynamic-replan constraints, workspace sandbox and
  sensitive-path rejection, the review command allowlist, skipping tests on cancellation,
  workspace persistence and validation, directory browsing, and LLM / agent settings
  persistence / masking / environment-variable resolution / startup loading / hot updates.
- The frontend build runs `vue-tsc --noEmit`; ESLint runs with `--max-warnings=0`.

When adding or changing event fields, update the backend records,
`frontend/src/api/events.ts`, and `docs/events.md` together. REST / DTO changes must also update
`docs/requirements.md` and `frontend/src/api/client.ts`.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Database connection failure at startup | Check `TASKNEST_MYSQL_*` / `MYSQL_*`, database existence, and account permissions |
| `LLM_NOT_CONFIGURED` when creating a task | Fill in a usable Base URL, model, and key under **Settings → LLM connection**; for `ENV`, verify the backend process can read the variable |
| `GIT_NOT_REPOSITORY` / `GIT_NOT_REPO_ROOT` | The workspace must be a Git repository root and `git` must be executable |
| `GIT_DIRTY_WORKTREE` | Commit or clean the workspace; runs require a clean tree |
| `GIT_DETACHED_HEAD` | Check out a normal branch before creating a task |
| `GIT_MERGE_CONFLICT` | The merge was aborted and both branches were kept; resolve manually and rerun |
| “Run expired” | The backend restarted and lost in-memory run state; archived records remain available—create a new task to continue |
| The page cannot reach the backend | Check `VITE_API_BASE`, the backend port, and CORS (local origins only) |

## Roadmap

- **P0–P2 (completed)**: presentation layer, event contract, SSE, MySQL persistence, and the
  plan-table workbench.
- **P3 (completed)**: SSE planning sessions, main-agent planning and dynamic replanning,
  sub-agent file / shell tools, optional independent reviewer with test execution, local Git
  branch rollback, and option + free-text decisions.
- **P4 (planned)**: resumable runs, cross-process recovery, and richer history search.
- **P5 (planned)**: multi-model routing, sub-agent isolation, and failure degradation.

Explicitly out of scope: remote Git operations, token-cost budgeting, multi-model routing, RAG,
vector stores, user accounts / permissions / login, message queues, Redis, and microservices.
