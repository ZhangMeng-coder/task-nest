/**
 * 与 docs/events.md、backend/com.tasknest.event.* 逐字段对齐。
 * 解析入口只接受 unknown，并做字段校验；不使用 any。
 */

export const EVENT_TYPES = [
  'run_started',
  'plan',
  'task_started',
  'task_thought',
  'prompt_assembled',
  'tool_call',
  'tool_result',
  'waiting_input',
  'input_received',
  'task_completed',
  'task_failed',
  'run_completed',
  'error',
  'heartbeat',
] as const

export type EventType = (typeof EVENT_TYPES)[number]

export type ToolStatus = 'success' | 'error'
export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED'
export type MainTaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_INPUT' | 'COMPLETED' | 'FAILED'
export type SubtaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_INPUT' | 'RETRYING' | 'SUCCESS' | 'FAILED'
export type TaskStatus = MainTaskStatus | SubtaskStatus
export type ConnectionState = 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED'

export interface TaskRef {
  taskId: string
  parentTaskId: string | null
  title: string
  agentName: string
}

export interface PlannedTask {
  taskId: string
  parentTaskId: string | null
  title: string
  agentName: string
  dependsOn: string[]
}

export interface DecisionOption {
  id: string
  label: string
  description: string
}

export interface ContextSnippet {
  source: string
  content: string
}

export interface ToolCallPayload {
  callId?: string
  toolName: string
  arguments: Record<string, unknown>
}

export interface ToolResultPayload {
  callId?: string
  toolName: string
  status: ToolStatus
  result: unknown
  durationMs: number
}

export type EmptyPayload = Record<string, unknown>

export interface EventPayloadMap {
  run_started: { goal: string }
  plan: { tasks: PlannedTask[] }
  task_started: EmptyPayload
  task_thought: { delta: string }
  prompt_assembled: {
    systemPrompt: string
    userPrompt: string
    contextSnippets: ContextSnippet[]
    finalPrompt: string
  }
  tool_call: ToolCallPayload
  tool_result: ToolResultPayload
  waiting_input: { question: string; options: DecisionOption[] }
  input_received: { selectedOptionId: string }
  task_completed: { summary: string }
  task_failed: { error: string; retryable: boolean }
  run_completed: { summary: string }
  error: { code: string; message: string }
  heartbeat: EmptyPayload
}

export interface EnvelopeBase {
  eventId: string
  runId: string
  seq: number
  timestamp: string
  task: TaskRef | null
}

export type EventFor<K extends EventType> = EnvelopeBase & {
  type: K
  payload: EventPayloadMap[K]
}

export type TaskNestEvent = { [K in EventType]: EventFor<K> }[EventType]

export interface WaitingInputSnapshot {
  question: string
  options: DecisionOption[]
}

/** 主任务与子任务只保留两层；快照里主任务单独给出，子任务是扁平直属列表。 */
export interface MainTaskSnapshot {
  mainTaskId: string
  taskId: string
  title: string
  agentName: string
  status: MainTaskStatus
  summary: string | null
  error: string | null
  selectedOptionId: string | null
  waitingInput: WaitingInputSnapshot | null
  startedAt: string | null
  completedAt: string | null
}

export interface SubtaskSnapshot {
  taskId: string
  title: string
  agentName: string
  status: SubtaskStatus
  summary: string | null
  error: string | null
  dependsOn: string[]
  order: number
  selectedOptionId: string | null
  waitingInput: WaitingInputSnapshot | null
  startedAt: string | null
  completedAt: string | null
}

export interface RunInfo {
  runId: string
  mainTaskId: string
  mockScript: string | null
  goal: string | null
  status: RunStatus
  createdAt: string
  updatedAt: string
  lastSeq: number
}

export interface RunSnapshot {
  run: RunInfo
  mainTask: MainTaskSnapshot
  subtasks: SubtaskSnapshot[]
  events: TaskNestEvent[]
}

/** 计划表中的一行，对应主任务下的一个直属子任务。 */
export interface PlanRowView {
  title: string
  agent: string
  dependency: string
  output: string
}

/** 计划表草案或持久化档案；markdown 直接用于详情展示。 */
export interface PlanDraftView {
  mainTaskId: string | null
  title: string
  goal: string
  mockScript: string
  version: number
  markdown: string
  rows: PlanRowView[]
  revisions: string[]
}

export interface CreateMainTaskResponse {
  mainTaskId: string
  runId: string
  mockScript: string
  status: MainTaskStatus
  createdAt: string
  plan: PlanDraftView | null
}

export interface MainTaskSummary {
  mainTaskId: string
  runId: string
  rootTaskId: string | null
  title: string
  goal: string | null
  mockScript: string
  planVersion: number
  status: MainTaskStatus
  summary: string | null
  error: string | null
  subtaskCount: number
  completedSubtaskCount: number
  createdAt: string | null
  updatedAt: string | null
  completedAt: string | null
}

export interface SubtaskView {
  taskId: string
  title: string
  agentName: string
  status: SubtaskStatus
  order: number
  dependsOn: string[]
  summary: string | null
  error: string | null
  waitingQuestion: string | null
  waitingOptions: DecisionOption[]
  selectedOptionId: string | null
  startedAt: string | null
  completedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface PromptView {
  taskId: string
  seq: number
  systemPrompt: string
  userPrompt: string
  contextSnippets: ContextSnippet[]
  finalPrompt: string
  createdAt: string | null
}

export type ArtifactKind = 'THOUGHT' | 'TOOL_CALL' | 'TOOL_RESULT'

export interface ArtifactView {
  taskId: string
  seq: number
  kind: ArtifactKind
  callId: string | null
  toolName: string | null
  status: string | null
  durationMs: number | null
  content: unknown
  createdAt: string | null
}

export interface MainTaskDetail {
  mainTask: MainTaskSummary
  plan: PlanDraftView | null
  subtasks: SubtaskView[]
  prompts: PromptView[]
  artifacts: ArtifactView[]
  /** 后端直接返回持久化的事件原文；调用方逐条做运行时校验。 */
  events: unknown[]
  lastSeq: number
}

export interface AcceptedResponse {
  runId: string
  taskId: string
  selectedOptionId: string
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null
  }
  return value as Record<string, unknown>
}

function readString(record: Record<string, unknown>, key: string): string | null {
  const value = record[key]
  return typeof value === 'string' ? value : null
}

function readNonEmptyString(record: Record<string, unknown>, key: string): string | null {
  const value = readString(record, key)
  return value !== null && value.trim().length > 0 ? value : null
}

function readNumber(record: Record<string, unknown>, key: string): number | null {
  const value = record[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function readBoolean(record: Record<string, unknown>, key: string): boolean | null {
  const value = record[key]
  return typeof value === 'boolean' ? value : null
}

function parseTaskRef(value: unknown): TaskRef | null {
  const record = asRecord(value)
  if (!record) {
    return null
  }
  const taskId = readNonEmptyString(record, 'taskId')
  const title = readNonEmptyString(record, 'title')
  const agentName = readNonEmptyString(record, 'agentName')
  const parentRaw = record.parentTaskId
  const parentTaskId = parentRaw === null || parentRaw === undefined
    ? null
    : typeof parentRaw === 'string'
      ? parentRaw
      : undefined
  if (taskId === null || title === null || agentName === null || parentTaskId === undefined) {
    return null
  }
  return { taskId, parentTaskId, title, agentName }
}

function parsePlannedTasks(value: unknown): PlannedTask[] | null {
  if (!Array.isArray(value) || value.length === 0) {
    return null
  }
  const tasks: PlannedTask[] = []
  for (const item of value) {
    const record = asRecord(item)
    if (!record) {
      return null
    }
    const taskId = readNonEmptyString(record, 'taskId')
    const title = readNonEmptyString(record, 'title')
    const agentName = readNonEmptyString(record, 'agentName')
    const parentRaw = record.parentTaskId
    const parentTaskId = parentRaw === null || parentRaw === undefined
      ? null
      : typeof parentRaw === 'string'
        ? parentRaw
        : undefined
    const dependsOn = parseStringArray(record.dependsOn)
    if (taskId === null || title === null || agentName === null || parentTaskId === undefined || !dependsOn) {
      return null
    }
    tasks.push({ taskId, parentTaskId, title, agentName, dependsOn })
  }
  return tasks
}

function parseStringArray(value: unknown): string[] | null {
  if (value === undefined || value === null) {
    return []
  }
  if (!Array.isArray(value)) {
    return null
  }
  const result: string[] = []
  for (const item of value) {
    if (typeof item !== 'string') {
      return null
    }
    result.push(item)
  }
  return result
}

function parseOptions(value: unknown): DecisionOption[] | null {
  if (!Array.isArray(value) || value.length < 2) {
    return null
  }
  const options: DecisionOption[] = []
  for (const item of value) {
    const record = asRecord(item)
    if (!record) {
      return null
    }
    const id = readNonEmptyString(record, 'id')
    const label = readNonEmptyString(record, 'label')
    const description = readString(record, 'description') ?? ''
    if (id === null || label === null) {
      return null
    }
    options.push({ id, label, description })
  }
  return options
}

function parseSnippets(value: unknown): ContextSnippet[] | null {
  if (value === undefined || value === null) {
    return []
  }
  if (!Array.isArray(value)) {
    return null
  }
  const snippets: ContextSnippet[] = []
  for (const item of value) {
    const record = asRecord(item)
    if (!record) {
      return null
    }
    const source = readNonEmptyString(record, 'source')
    const content = readString(record, 'content')
    if (source === null || content === null) {
      return null
    }
    snippets.push({ source, content })
  }
  return snippets
}

function eventWith<K extends EventType>(
  base: EnvelopeBase,
  type: K,
  payload: EventPayloadMap[K],
): EventFor<K> {
  return { ...base, type, payload }
}

/**
 * 解析单条 SSE data。返回 null 表示该条事件不可用，调用方应记录日志并继续。
 */
export function parseEvent(raw: string): TaskNestEvent | null {
  let decoded: unknown
  try {
    decoded = JSON.parse(raw)
  } catch {
    return null
  }
  return parseEventValue(decoded)
}

/**
 * 解析已经反序列化的事件对象；持久化详情里的事件用它复用同一套字段校验。
 */
export function parseEventValue(decoded: unknown): TaskNestEvent | null {
  const record = asRecord(decoded)
  if (!record) {
    return null
  }
  const eventId = readNonEmptyString(record, 'eventId')
  const runId = readNonEmptyString(record, 'runId')
  const seq = readNumber(record, 'seq')
  const timestamp = readString(record, 'timestamp')
  const typeValue = readString(record, 'type')
  const payload = asRecord(record.payload)
  if (
    eventId === null
    || runId === null
    || seq === null
    || !Number.isInteger(seq)
    || seq < 1
    || timestamp === null
    || typeValue === null
    || !(EVENT_TYPES as readonly string[]).includes(typeValue)
    || payload === null
  ) {
    return null
  }

  const type = typeValue as EventType
  const task = record.task === null || record.task === undefined
    ? null
    : parseTaskRef(record.task)
  if (task === null && record.task !== null && record.task !== undefined) {
    return null
  }
  const base: EnvelopeBase = { eventId, runId, seq, timestamp, task }

  switch (type) {
    case 'run_started': {
      const goal = readNonEmptyString(payload, 'goal')
      return goal === null ? null : eventWith(base, type, { goal })
    }
    case 'plan': {
      const tasks = parsePlannedTasks(payload.tasks)
      return tasks === null ? null : eventWith(base, type, { tasks })
    }
    case 'task_started':
    case 'heartbeat':
      return eventWith(base, type, {})
    case 'task_thought': {
      const delta = readString(payload, 'delta')
      return delta === null ? null : eventWith(base, type, { delta })
    }
    case 'prompt_assembled': {
      const systemPrompt = readString(payload, 'systemPrompt')
      const userPrompt = readString(payload, 'userPrompt')
      const contextSnippets = parseSnippets(payload.contextSnippets)
      const finalPrompt = readString(payload, 'finalPrompt')
      if (systemPrompt === null || userPrompt === null || contextSnippets === null || finalPrompt === null) {
        return null
      }
      return eventWith(base, type, { systemPrompt, userPrompt, contextSnippets, finalPrompt })
    }
    case 'tool_call': {
      const toolName = readNonEmptyString(payload, 'toolName')
      const callId = readString(payload, 'callId') ?? undefined
      const args = asRecord(payload.arguments)
      if (toolName === null || args === null) {
        return null
      }
      return eventWith(base, type, { callId, toolName, arguments: args })
    }
    case 'tool_result': {
      const toolName = readNonEmptyString(payload, 'toolName')
      const callId = readString(payload, 'callId') ?? undefined
      const status = readString(payload, 'status')
      const durationMs = readNumber(payload, 'durationMs')
      if (
        toolName === null
        || (status !== 'success' && status !== 'error')
        || durationMs === null
        || !('result' in payload)
      ) {
        return null
      }
      return eventWith(base, type, {
        callId,
        toolName,
        status,
        result: payload.result,
        durationMs,
      })
    }
    case 'waiting_input': {
      const question = readNonEmptyString(payload, 'question')
      const options = parseOptions(payload.options)
      if (question === null || options === null) {
        return null
      }
      return eventWith(base, type, { question, options })
    }
    case 'input_received': {
      const selectedOptionId = readNonEmptyString(payload, 'selectedOptionId')
      return selectedOptionId === null
        ? null
        : eventWith(base, type, { selectedOptionId })
    }
    case 'task_completed': {
      const summary = readString(payload, 'summary')
      return summary === null ? null : eventWith(base, type, { summary })
    }
    case 'task_failed': {
      const error = readNonEmptyString(payload, 'error')
      const retryable = readBoolean(payload, 'retryable')
      return error === null || retryable === null
        ? null
        : eventWith(base, type, { error, retryable })
    }
    case 'run_completed': {
      const summary = readString(payload, 'summary')
      return summary === null ? null : eventWith(base, type, { summary })
    }
    case 'error': {
      const code = readNonEmptyString(payload, 'code')
      const message = readNonEmptyString(payload, 'message')
      return code === null || message === null
        ? null
        : eventWith(base, type, { code, message })
    }
    default:
      return null
  }
}
