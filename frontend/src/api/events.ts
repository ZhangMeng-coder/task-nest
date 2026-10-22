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
  'review_started',
  'review_completed',
  'git_checkpoint',
  'run_completed',
  'error',
  'heartbeat',
] as const

export type EventType = (typeof EVENT_TYPES)[number]

export type ToolStatus = 'success' | 'error'
export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED'
export type MainTaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_INPUT' | 'COMPLETED' | 'FAILED'
export type SubtaskStatus =
  | 'PENDING' | 'RUNNING' | 'WAITING_INPUT' | 'RETRYING'
  | 'REVIEWING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
export type TaskStatus = MainTaskStatus | SubtaskStatus
export type ConnectionState = 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED'
export type PlanSessionStatus =
  | 'RUNNING' | 'WAITING_INPUT' | 'READY' | 'CONFIRMED' | 'CANCELLED' | 'FAILED'
export type ReviewVerdict = 'PASS' | 'REWORK'
export type ReviewSeverity = 'BLOCKER' | 'MAJOR' | 'MINOR'
export type TestStatus = 'PASSED' | 'FAILED' | 'UNAVAILABLE'
export type GitAction = 'branch_created' | 'committed' | 'merged' | 'rolled_back' | 'branch_deleted'

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
  description: string | null
  acceptanceCriteria: string[] | null
  allowedTools: string[] | null
  testCommand: string | null
}

export interface ReviewIssue {
  severity: ReviewSeverity
  file: string | null
  line: number | null
  message: string
  suggestion: string | null
}

export interface TestEvidence {
  command: string
  exitCode: number | null
  timedOut: boolean
  summary: string
}

export interface ReviewCompletedPayload {
  attempt: number
  verdict: ReviewVerdict
  summary: string
  issues: ReviewIssue[]
  testStatus: TestStatus
  testEvidence: TestEvidence[]
  modelName: string | null
  durationMs: number
}

export interface GitCheckpointPayload {
  action: GitAction
  branch: string
  targetBranch: string | null
  commit: string | null
  attempt: number | null
  noChanges: boolean
  message: string | null
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

export interface PlanPayload {
  tasks: PlannedTask[]
  revision: number | null
  reason: string | null
  changedTaskIds: string[] | null
}

export interface WaitingInputPayload {
  question: string
  options: DecisionOption[]
  allowCustom: boolean
  customPlaceholder: string | null
}

export interface EventPayloadMap {
  run_started: { goal: string }
  plan: PlanPayload
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
  waiting_input: WaitingInputPayload
  input_received: { selectedOptionId: string | null; customInput: string | null }
  task_completed: { summary: string }
  task_failed: { error: string; retryable: boolean }
  review_started: { attempt: number }
  review_completed: ReviewCompletedPayload
  git_checkpoint: GitCheckpointPayload
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
  allowCustom: boolean
  customPlaceholder: string | null
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
  taskId?: string | null
  description?: string | null
  acceptanceCriteria?: string[] | null
  allowedTools?: string[] | null
  testCommand?: string | null
}

/** 计划表档案或确认稿；markdown 直接用于详情展示。 */
export interface PlanDraftView {
  mainTaskId: string | null
  title: string
  goal: string
  version: number
  markdown: string
  rows: PlanRowView[]
  revisions: string[]
}

export interface PlanSessionView {
  planSessionId: string
  status: PlanSessionStatus
  goal: string
  workingDir: string
  plan: PlanPayload | null
  lastSeq: number
  error: string | null
}

export interface CreatePlanSessionResponse {
  planSessionId: string
  status: PlanSessionStatus
  lastSeq: number
}

export interface ConfirmPlanSessionResponse {
  mainTaskId: string
  runId: string
  workingDir: string
  planVersion: number
}

export interface AgentSettingsView {
  reviewEnabled: boolean
  reviewMaxReworks: number
  updatedAt: string | null
}

export interface MainTaskSummary {
  mainTaskId: string
  runId: string
  rootTaskId: string | null
  title: string
  goal: string | null
  workingDir: string | null
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
  selectedOptionId: string | null
  customInput: string | null
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
    tasks.push({
      taskId,
      parentTaskId,
      title,
      agentName,
      dependsOn,
      description: readNullableString(record, 'description'),
      acceptanceCriteria: parseOptionalStringArray(record.acceptanceCriteria),
      allowedTools: parseOptionalStringArray(record.allowedTools),
      testCommand: readNullableString(record, 'testCommand'),
    })
  }
  return tasks
}

function readNullableString(record: Record<string, unknown>, key: string): string | null {
  const value = record[key]
  return typeof value === 'string' ? value : null
}

function parseOptionalStringArray(value: unknown): string[] | null {
  if (value === undefined || value === null) {
    return null
  }
  return parseStringArray(value)
}

function parsePlanPayload(payload: Record<string, unknown>): PlanPayload | null {
  const tasks = parsePlannedTasks(payload.tasks)
  if (tasks === null) {
    return null
  }
  const revisionRaw = payload.revision
  const revision = typeof revisionRaw === 'number' && Number.isInteger(revisionRaw)
    ? revisionRaw
    : null
  const reason = readNullableString(payload, 'reason')
  const changedTaskIds = parseOptionalStringArray(payload.changedTaskIds)
  return { tasks, revision, reason, changedTaskIds }
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
  // 真实 ask_user 允许 0 个选项（只让用户自由输入），最多 8 个。
  if (!Array.isArray(value) || value.length > 8) {
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

function parseWaitingInput(payload: Record<string, unknown>): WaitingInputPayload | null {
  const question = readNonEmptyString(payload, 'question')
  const options = parseOptions(payload.options)
  if (question === null || options === null) {
    return null
  }
  const allowCustomRaw = payload.allowCustom
  const allowCustom = allowCustomRaw === undefined || allowCustomRaw === null
    ? true
    : readBoolean(payload, 'allowCustom')
  if (allowCustom === null) {
    return null
  }
  return {
    question,
    options,
    allowCustom,
    customPlaceholder: readNullableString(payload, 'customPlaceholder'),
  }
}

function parseReviewIssues(value: unknown): ReviewIssue[] | null {
  if (!Array.isArray(value)) {
    return null
  }
  const issues: ReviewIssue[] = []
  for (const item of value) {
    const record = asRecord(item)
    if (!record) {
      return null
    }
    const severity = readString(record, 'severity')
    const message = readString(record, 'message')
    const lineValue = record.line
    const line = typeof lineValue === 'number' && Number.isInteger(lineValue) ? lineValue : null
    if (
      severity !== 'BLOCKER' && severity !== 'MAJOR' && severity !== 'MINOR'
      || message === null
    ) {
      return null
    }
    issues.push({
      severity,
      file: readNullableString(record, 'file'),
      line,
      message,
      suggestion: readNullableString(record, 'suggestion'),
    })
  }
  return issues
}

function parseTestEvidence(value: unknown): TestEvidence[] | null {
  if (!Array.isArray(value)) {
    return null
  }
  const evidence: TestEvidence[] = []
  for (const item of value) {
    const record = asRecord(item)
    if (!record) {
      return null
    }
    const command = readString(record, 'command')
    const timedOut = readBoolean(record, 'timedOut')
    const summary = readString(record, 'summary')
    const exitRaw = record.exitCode
    const exitCode = typeof exitRaw === 'number' && Number.isInteger(exitRaw) ? exitRaw : null
    if (command === null || timedOut === null || summary === null) {
      return null
    }
    evidence.push({ command, exitCode, timedOut, summary })
  }
  return evidence
}

function parseReviewCompleted(payload: Record<string, unknown>): ReviewCompletedPayload | null {
  const attempt = readNumber(payload, 'attempt')
  const verdict = readString(payload, 'verdict')
  const summary = readString(payload, 'summary')
  const issues = parseReviewIssues(payload.issues)
  const testStatus = readString(payload, 'testStatus')
  const testEvidence = parseTestEvidence(payload.testEvidence)
  const durationMs = readNumber(payload, 'durationMs')
  if (
    attempt === null || !Number.isInteger(attempt)
    || (verdict !== 'PASS' && verdict !== 'REWORK')
    || summary === null
    || issues === null
    || (testStatus !== 'PASSED' && testStatus !== 'FAILED' && testStatus !== 'UNAVAILABLE')
    || testEvidence === null
    || durationMs === null
  ) {
    return null
  }
  return {
    attempt,
    verdict,
    summary,
    issues,
    testStatus,
    testEvidence,
    modelName: readNullableString(payload, 'modelName'),
    durationMs,
  }
}

function parseGitCheckpoint(payload: Record<string, unknown>): GitCheckpointPayload | null {
  const action = readString(payload, 'action')
  const branch = readNonEmptyString(payload, 'branch')
  const noChanges = readBoolean(payload, 'noChanges')
  const attemptRaw = payload.attempt
  const attempt = typeof attemptRaw === 'number' && Number.isInteger(attemptRaw) ? attemptRaw : null
  if (
    (action !== 'branch_created' && action !== 'committed' && action !== 'merged'
      && action !== 'rolled_back' && action !== 'branch_deleted')
    || branch === null
    || noChanges === null
  ) {
    return null
  }
  return {
    action,
    branch,
    targetBranch: readNullableString(payload, 'targetBranch'),
    commit: readNullableString(payload, 'commit'),
    attempt,
    noChanges,
    message: readNullableString(payload, 'message'),
  }
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

/** 规划会话视图的运行时校验；plan 允许为 null。 */
export function parsePlanSessionView(value: unknown): PlanSessionView | null {
  const record = asRecord(value)
  if (!record) {
    return null
  }
  const planSessionId = readNonEmptyString(record, 'planSessionId')
  const status = readString(record, 'status')
  const goal = readString(record, 'goal')
  const workingDir = readNonEmptyString(record, 'workingDir')
  const lastSeq = readNumber(record, 'lastSeq')
  const error = readNullableString(record, 'error')
  const planRaw = record.plan
  if (
    planSessionId === null
    || (status !== 'RUNNING' && status !== 'WAITING_INPUT' && status !== 'READY'
      && status !== 'CONFIRMED' && status !== 'CANCELLED' && status !== 'FAILED')
    || goal === null
    || workingDir === null
    || lastSeq === null
  ) {
    return null
  }
  let plan: PlanPayload | null = null
  if (planRaw !== null && planRaw !== undefined) {
    const planRecord = asRecord(planRaw)
    if (!planRecord) {
      return null
    }
    plan = parsePlanPayload(planRecord)
    if (plan === null) {
      return null
    }
  }
  return { planSessionId, status, goal, workingDir, plan, lastSeq, error }
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
      const plan = parsePlanPayload(payload)
      return plan === null ? null : eventWith(base, type, plan)
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
      const waiting = parseWaitingInput(payload)
      return waiting === null ? null : eventWith(base, type, waiting)
    }
    case 'input_received': {
      const selectedOptionId = readNullableString(payload, 'selectedOptionId')
      const customInput = readNullableString(payload, 'customInput')
      if (selectedOptionId === null && customInput === null) {
        return null
      }
      return eventWith(base, type, { selectedOptionId, customInput })
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
    case 'review_started': {
      const attempt = readNumber(payload, 'attempt')
      return attempt === null || !Number.isInteger(attempt)
        ? null
        : eventWith(base, type, { attempt })
    }
    case 'review_completed': {
      const review = parseReviewCompleted(payload)
      return review === null ? null : eventWith(base, type, review)
    }
    case 'git_checkpoint': {
      const checkpoint = parseGitCheckpoint(payload)
      return checkpoint === null ? null : eventWith(base, type, checkpoint)
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
