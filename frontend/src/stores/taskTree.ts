import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  ApiError,
  apiBase,
  cancelPlanSession,
  confirmPlanSession,
  createMainTask,
  createPlanSession,
  describeError,
  getMainTaskDetail,
  getPlanSession,
  getSnapshot,
  listMainTasks,
  reviseMainTaskPlan,
  revisePlanSession,
  submitInput,
  submitPlanSessionInput,
} from '@/api/client'
import { SseClient } from '@/api/sse'
import {
  parseEventValue,
  type ConnectionState,
  type EventPayloadMap,
  type GitCheckpointPayload,
  type MainTaskDetail,
  type MainTaskStatus,
  type MainTaskSummary,
  type PlanDraftView,
  type PlanPayload,
  type PlanSessionStatus,
  type PlanSessionView,
  type ReviewCompletedPayload,
  type RunSnapshot,
  type RunStatus,
  type SubtaskSnapshot,
  type SubtaskStatus,
  type TaskNestEvent,
  type TaskRef,
  type ToolResultPayload,
  type WaitingInputPayload,
} from '@/api/events'

export interface ToolCallView {
  key: string
  callId: string | null
  toolName: string
  args: Record<string, unknown> | null
  result: ToolResultPayload | null
  orphan: boolean
}

export type GitCheckpointView = GitCheckpointPayload & {
  seq: number
  timestamp: string
}

interface BaseNodeView {
  taskId: string
  title: string
  agentName: string
  thought: string
  prompts: EventPayloadMap['prompt_assembled'][]
  toolCalls: ToolCallView[]
  waiting: WaitingInputPayload | null
  lastDecisionId: string | null
  lastCustomInput: string | null
  summary: string | null
  error: string | null
  selectedOptionId: string | null
  review: ReviewCompletedPayload | null
  reviewAttempt: number | null
  gitCheckpoints: GitCheckpointView[]
  startedAt: string | null
  completedAt: string | null
}

/** 主任务节点：状态与子任务完全分开。 */
export interface MainNodeView extends BaseNodeView {
  kind: 'MAIN'
  mainTaskId: string
  status: MainTaskStatus
  planRevision: number | null
  planReason: string | null
  planChangedTaskIds: string[]
}

/** 子任务节点：固定直接挂在主任务下，不再有第三层。 */
export interface SubtaskNodeView extends BaseNodeView {
  kind: 'SUBTASK'
  parentTaskId: string
  status: SubtaskStatus
  dependsOn: string[]
  order: number
}

export type TaskNodeView = MainNodeView | SubtaskNodeView

export interface ParseErrorEntry {
  id: string
  raw: string
  reason: string
  at: string
}

function isTerminalMain(status: MainTaskStatus): boolean {
  return status === 'COMPLETED' || status === 'FAILED'
}

function isTerminalSubtask(status: SubtaskStatus): boolean {
  return status === 'SUCCESS' || status === 'FAILED' || status === 'CANCELLED'
}

function isTerminalPlanSession(status: PlanSessionStatus | null): boolean {
  return status === 'CONFIRMED' || status === 'CANCELLED' || status === 'FAILED'
}

export const useTaskTreeStore = defineStore('taskTree', () => {
  const runId = ref<string | null>(null)
  const mainTaskId = ref<string | null>(null)
  const mockScript = ref<string | null>(null)
  const goal = ref<string | null>(null)
  const workingDir = ref<string | null>(null)
  const runStatus = ref<RunStatus | null>(null)
  const runSummary = ref<string | null>(null)
  const runTerminal = ref(false)
  const plan = ref<PlanDraftView | null>(null)
  const planError = ref<string | null>(null)
  const mainCreatedAt = ref<string | null>(null)
  const mainUpdatedAt = ref<string | null>(null)

  const mainNode = ref<MainNodeView | null>(null)
  const subtaskNodes = ref<Record<string, SubtaskNodeView>>({})
  const selectedTaskId = ref<string | null>(null)
  const collapsed = ref<Record<string, true>>({})

  const events = ref<TaskNestEvent[]>([])
  const duplicates = ref<TaskNestEvent[]>([])
  const seen = ref<Record<string, true>>({})
  const buffered = ref<Record<number, TaskNestEvent>>({})
  const lastSeq = ref(0)
  const lastEventAt = ref<string | null>(null)
  const unknownTaskIds = ref<Record<string, true>>({})
  const plannedTaskIds = new Set<string>()

  const connection = ref<ConnectionState>('IDLE')
  const reconnectAttempt = ref(0)
  const reconnectCount = ref(0)
  const recoveryCount = ref(0)
  const parseErrors = ref<ParseErrorEntry[]>([])

  /** 创建前的规划会话：与运行 SSE 完全隔离。 */
  const planSessionId = ref<string | null>(null)
  const planningStatus = ref<PlanSessionStatus | null>(null)
  const planningPlan = ref<PlanPayload | null>(null)
  const planningWaiting = ref<WaitingInputPayload | null>(null)
  const planningWaitingTaskId = ref<string | null>(null)
  const planningEvents = ref<TaskNestEvent[]>([])
  const planningConnection = ref<ConnectionState>('IDLE')
  const planningError = ref<string | null>(null)
  const planningLastSeq = ref(0)
  const planningLastEventAt = ref<string | null>(null)
  const planningSeen = ref<Record<string, true>>({})
  const planningBuffered = ref<Record<number, TaskNestEvent>>({})
  const planningStarting = ref(false)
  const planningRevising = ref(false)
  const planningConfirming = ref(false)
  const planningCancelling = ref(false)
  const planningDecisionSubmitting = ref(false)
  const planningTerminal = computed(() => isTerminalPlanSession(planningStatus.value))

  const starting = ref(false)
  const planRevising = ref(false)
  const decisionSubmitting = ref(false)
  const decisionError = ref<string | null>(null)
  const globalError = ref<string | null>(null)

  const archiveMode = ref(false)
  const archiveLoading = ref(false)
  const archiveError = ref<string | null>(null)
  const library = ref<MainTaskSummary[]>([])
  const libraryLoading = ref(false)
  const libraryError = ref<string | null>(null)
  const libraryOpen = ref(false)

  let sse: SseClient | null = null
  let planningSse: SseClient | null = null
  let gapTimer: number | null = null
  let planningGapTimer: number | null = null
  let recoveryPromise: Promise<void> | null = null
  let planningRecoveryPromise: Promise<void> | null = null
  let parseErrorCounter = 0
  let toolKeyCounter = 0
  let orderCounter = 0
  let wasReconnecting = false
  let archiveRequest = 0

  const selectedNode = computed<TaskNodeView | null>(() => {
    const id = selectedTaskId.value
    if (!id) {
      return null
    }
    if (mainNode.value?.taskId === id) {
      return mainNode.value
    }
    return subtaskNodes.value[id] ?? null
  })

  const subtaskList = computed<SubtaskNodeView[]>(() => Object.values(subtaskNodes.value)
    .sort((left, right) => left.order - right.order))

  const subtaskCount = computed(() => subtaskList.value.length)
  const successCount = computed(() => subtaskList.value
    .filter((node) => node.status === 'SUCCESS').length)
  const failedCount = computed(() => subtaskList.value
    .filter((node) => node.status === 'FAILED').length)
  const waitingNodes = computed<TaskNodeView[]>(() => {
    const list: TaskNodeView[] = []
    if (mainNode.value?.status === 'WAITING_INPUT' && mainNode.value.waiting) {
      list.push(mainNode.value)
    }
    for (const node of subtaskList.value) {
      if (node.status === 'WAITING_INPUT' && node.waiting) {
        list.push(node)
      }
    }
    return list
  })
  const waitingCount = computed(() => waitingNodes.value.length)
  const unknownTaskCount = computed(() => Object.keys(unknownTaskIds.value).length)
  const taskCount = computed(() => subtaskCount.value + (mainNode.value ? 1 : 0))

  const mainTaskLibraryCount = computed(() => library.value.length)

  function mainTaskNodeId(): string | null {
    return mainNode.value?.taskId ?? null
  }

  function nodeOf(taskId: string | null): TaskNodeView | null {
    if (!taskId) {
      return null
    }
    if (mainNode.value?.taskId === taskId) {
      return mainNode.value
    }
    return subtaskNodes.value[taskId] ?? null
  }

  function baseNode(refValue: TaskRef): BaseNodeView {
    return {
      taskId: refValue.taskId,
      title: refValue.title,
      agentName: refValue.agentName,
      thought: '',
      prompts: [],
      toolCalls: [],
      waiting: null,
      lastDecisionId: null,
      lastCustomInput: null,
      summary: null,
      error: null,
      selectedOptionId: null,
      review: null,
      reviewAttempt: null,
      gitCheckpoints: [],
      startedAt: null,
      completedAt: null,
    }
  }

  function ensureMain(refValue?: Partial<TaskRef>): MainNodeView {
    if (!mainNode.value) {
      const base = baseNode({
        taskId: refValue?.taskId ?? '',
        parentTaskId: null,
        title: refValue?.title ?? '待规划主任务',
        agentName: refValue?.agentName ?? '主任务',
      })
      mainNode.value = {
        ...base,
        kind: 'MAIN',
        mainTaskId: mainTaskId.value ?? '',
        status: 'PENDING',
        planRevision: null,
        planReason: null,
        planChangedTaskIds: [],
      }
    }
    if (refValue?.taskId) {
      mainNode.value.taskId = refValue.taskId
    }
    if (refValue?.title) {
      mainNode.value.title = refValue.title
    }
    if (refValue?.agentName) {
      mainNode.value.agentName = refValue.agentName
    }
    if (mainTaskId.value) {
      mainNode.value.mainTaskId = mainTaskId.value
    }
    return mainNode.value
  }

  function ensureSubtask(refValue: TaskRef | SubtaskSnapshot): SubtaskNodeView {
    const existing = subtaskNodes.value[refValue.taskId]
    if (existing) {
      existing.title = refValue.title
      existing.agentName = refValue.agentName
      return existing
    }
    const base = baseNode({
      taskId: refValue.taskId,
      parentTaskId: mainNode.value?.taskId ?? null,
      title: refValue.title,
      agentName: refValue.agentName,
    })
    subtaskNodes.value[refValue.taskId] = {
      ...base,
      kind: 'SUBTASK',
      parentTaskId: mainNode.value?.taskId ?? '',
      status: 'PENDING',
      dependsOn: 'dependsOn' in refValue ? [...refValue.dependsOn] : [],
      order: 'order' in refValue ? refValue.order : orderCounter++,
    }
    return subtaskNodes.value[refValue.taskId]
  }

  function ensureFromEvent(event: TaskNestEvent): TaskNodeView | null {
    if (!event.task) {
      return null
    }
    const existing = nodeOf(event.task.taskId)
    if (existing) {
      existing.title = event.task.title
      existing.agentName = event.task.agentName
      return existing
    }
    if (mainNode.value?.taskId === event.task.taskId) {
      return ensureMain(event.task)
    }
    if (!mainNode.value) {
      // plan 之前的异常顺序：先建立主任务占位，避免把根任务误建成子任务。
      return ensureMain(event.task)
    }
    if (!plannedTaskIds.has(event.task.taskId)) {
      // 协议要求事件只引用 plan 中的任务；其它情况仍建占位子任务，但在界面上告警。
      unknownTaskIds.value[event.task.taskId] = true
    }
    return ensureSubtask(event.task)
  }

  function expandAncestors(): void {
    if (mainNode.value) {
      delete collapsed.value[mainNode.value.taskId]
    }
  }

  function applyPlan(payload: PlanPayload, timestamp: string | null = null): void {
    const tasks = payload.tasks
    const root = tasks.find((task) => task.parentTaskId === null)
    if (!root) {
      return
    }
    tasks.forEach((task) => plannedTaskIds.add(task.taskId))
    const main = ensureMain(root)
    if (payload.revision !== null) {
      main.planRevision = payload.revision
    }
    main.planReason = payload.reason
    main.planChangedTaskIds = payload.changedTaskIds ?? []
    // 动态重排后同步计划页签；首次计划与确认前生成的草稿版本相同，不覆盖。
    if (
      plan.value
      && payload.revision !== null
      && payload.revision > plan.value.version
    ) {
      plan.value = planDraftFromPlan(payload, goal.value, mockScript.value)
    }

    const directChildren = tasks.filter((task) => task.parentTaskId === root.taskId)
    const plannedIds = new Set(directChildren.map((task) => task.taskId))
    // 重排后从计划中消失的 PENDING 任务由后端取消；前端同步投影。
    for (const node of Object.values(subtaskNodes.value)) {
      if (!plannedIds.has(node.taskId) && node.status === 'PENDING') {
        node.status = 'CANCELLED'
        node.error = '计划已重排，该子任务被取消'
        node.waiting = null
        node.completedAt = timestamp
      }
    }

    let order = 0
    for (const task of directChildren) {
      const node = ensureSubtask(task)
      node.order = order
      node.dependsOn = [...task.dependsOn]
      order += 1
    }
    if (!selectedTaskId.value || !nodeOf(selectedTaskId.value)) {
      selectedTaskId.value = mainNode.value?.taskId ?? null
    }
  }

  function pairToolResult(node: TaskNodeView, payload: ToolResultPayload): void {
    const callId = payload.callId ?? null
    let target: ToolCallView | undefined
    for (let index = node.toolCalls.length - 1; index >= 0; index -= 1) {
      const candidate = node.toolCalls[index]
      if (!candidate || candidate.result !== null) {
        continue
      }
      if (callId !== null && candidate.callId === callId) {
        target = candidate
        break
      }
      if (callId === null && candidate.toolName === payload.toolName) {
        target = candidate
        break
      }
    }
    if (!target) {
      node.toolCalls.push({
        key: `tool_${++toolKeyCounter}`,
        callId,
        toolName: payload.toolName,
        args: null,
        result: {
          callId: callId ?? undefined,
          toolName: payload.toolName,
          status: payload.status,
          result: payload.result,
          durationMs: payload.durationMs,
        },
        orphan: true,
      })
      return
    }
    target.result = {
      callId: callId ?? undefined,
      toolName: payload.toolName,
      status: payload.status,
      result: payload.result,
      durationMs: payload.durationMs,
    }
    target.orphan = false
  }

  function repairSubtasks(message: string): void {
    for (const node of Object.values(subtaskNodes.value)) {
      if (isTerminalSubtask(node.status)) {
        continue
      }
      node.status = 'FAILED'
      node.waiting = null
      if (!node.error) {
        node.error = message
      }
    }
  }

  function markMainTerminal(status: MainTaskStatus, summary: string | null, error: string | null): void {
    const node = ensureMain()
    node.status = status
    node.summary = summary
    node.error = error
    node.waiting = null
    node.completedAt = new Date().toISOString()
  }

  function applyStatusEvent(event: TaskNestEvent): void {
    const node = ensureFromEvent(event)
    if (!node) {
      return
    }
    const terminal = node.kind === 'MAIN'
      ? isTerminalMain(node.status)
      : isTerminalSubtask(node.status)
    if (terminal) {
      return
    }
    switch (event.type) {
      case 'task_started':
        expandAncestors()
        node.waiting = null
        node.startedAt ??= event.timestamp
        if (node.kind === 'MAIN') {
          node.status = 'RUNNING'
        } else {
          node.status = 'RUNNING'
        }
        break
      case 'waiting_input':
        node.waiting = event.payload
        node.status = 'WAITING_INPUT'
        expandAncestors()
        selectedTaskId.value = node.taskId
        decisionSubmitting.value = false
        decisionError.value = null
        break
      case 'input_received':
        node.waiting = null
        node.lastDecisionId = event.payload.selectedOptionId
        node.lastCustomInput = event.payload.customInput
        node.selectedOptionId = event.payload.selectedOptionId
        node.status = 'RUNNING'
        decisionSubmitting.value = false
        decisionError.value = null
        break
      case 'task_completed':
        node.status = node.kind === 'MAIN' ? 'COMPLETED' : 'SUCCESS'
        node.summary = event.payload.summary
        node.error = null
        node.waiting = null
        node.completedAt = event.timestamp
        break
      case 'task_failed':
        if (node.kind === 'MAIN') {
          node.status = 'FAILED'
          node.completedAt = event.timestamp
        } else {
          node.status = event.payload.retryable ? 'RETRYING' : 'FAILED'
          if (!event.payload.retryable) {
            node.completedAt = event.timestamp
          }
        }
        node.error = event.payload.error
        node.waiting = null
        break
      default:
        break
    }
  }

  function applySemantics(event: TaskNestEvent): void {
    mainUpdatedAt.value = event.timestamp
    switch (event.type) {
      case 'run_started':
        goal.value = event.payload.goal
        break
      case 'plan':
        applyPlan(event.payload, event.timestamp)
        break
      case 'task_started':
      case 'waiting_input':
      case 'input_received':
      case 'task_completed':
      case 'task_failed':
        applyStatusEvent(event)
        break
      case 'review_started': {
        const node = ensureFromEvent(event)
        if (node) {
          node.reviewAttempt = event.payload.attempt
          if (
            node.kind === 'SUBTASK'
            && !isTerminalSubtask(node.status)
          ) {
            node.status = 'REVIEWING'
          }
        }
        break
      }
      case 'review_completed': {
        const node = ensureFromEvent(event)
        if (node) {
          node.review = event.payload
          node.reviewAttempt = event.payload.attempt
          if (
            node.kind === 'SUBTASK'
            && event.payload.verdict === 'REWORK'
            && !isTerminalSubtask(node.status)
          ) {
            node.status = 'RETRYING'
          }
        }
        break
      }
      case 'git_checkpoint': {
        const node = ensureFromEvent(event)
        if (node) {
          node.gitCheckpoints.push({
            ...event.payload,
            seq: event.seq,
            timestamp: event.timestamp,
          })
        }
        break
      }
      case 'task_thought': {
        const node = ensureFromEvent(event)
        if (node) {
          node.thought += event.payload.delta
        }
        break
      }
      case 'prompt_assembled': {
        const node = ensureFromEvent(event)
        if (node) {
          node.prompts.push(event.payload)
        }
        break
      }
      case 'tool_call': {
        const node = ensureFromEvent(event)
        if (node) {
          node.toolCalls.push({
            key: `tool_${++toolKeyCounter}`,
            callId: event.payload.callId ?? null,
            toolName: event.payload.toolName,
            args: event.payload.arguments,
            result: null,
            orphan: false,
          })
        }
        break
      }
      case 'tool_result': {
        const node = ensureFromEvent(event)
        if (node) {
          pairToolResult(node, event.payload)
        }
        break
      }
      case 'run_completed':
        runStatus.value = 'COMPLETED'
        runSummary.value = event.payload.summary
        if (mainNode.value && !isTerminalMain(mainNode.value.status)) {
          markMainTerminal('COMPLETED', event.payload.summary, null)
        }
        repairSubtasks('运行已完成但该子任务未完成')
        finishRun()
        break
      case 'error':
        runStatus.value = 'FAILED'
        globalError.value = `${event.payload.code} · ${event.payload.message}`
        if (mainNode.value && !isTerminalMain(mainNode.value.status)) {
          markMainTerminal('FAILED', null, event.payload.message)
        }
        repairSubtasks(event.payload.message)
        finishRun()
        break
      case 'heartbeat':
        break
    }
  }

  function finishRun(): void {
    runTerminal.value = true
    clearGapTimer()
    buffered.value = {}
    decisionSubmitting.value = false
    if (sse) {
      const client = sse
      sse = null
      client.close()
    }
    connection.value = 'DISCONNECTED'
  }

  function recordAndApply(event: TaskNestEvent): void {
    seen.value[event.eventId] = true
    applySemantics(event)
    events.value.push(event)
    lastSeq.value = Math.max(lastSeq.value, event.seq)
    lastEventAt.value = event.timestamp
  }

  function logDuplicate(event: TaskNestEvent): void {
    if (
      events.value.some((item) => item.seq === event.seq)
      || duplicates.value.some((item) => item.seq === event.seq)
    ) {
      return
    }
    duplicates.value.push(event)
  }

  function drainBuffer(): void {
    let progressed = true
    while (progressed) {
      progressed = false
      const next = buffered.value[lastSeq.value + 1]
      if (!next) {
        break
      }
      delete buffered.value[lastSeq.value + 1]
      progressed = true
      if (seen.value[next.eventId]) {
        logDuplicate(next)
        lastSeq.value = Math.max(lastSeq.value, next.seq)
        continue
      }
      recordAndApply(next)
    }
    if (Object.keys(buffered.value).length === 0) {
      clearGapTimer()
    }
  }

  function handleEvent(event: TaskNestEvent): void {
    if (archiveMode.value || !runId.value || event.runId !== runId.value || runTerminal.value) {
      return
    }

    if (seen.value[event.eventId]) {
      logDuplicate(event)
      if (event.seq === lastSeq.value + 1) {
        lastSeq.value = event.seq
        drainBuffer()
      } else if (event.seq > lastSeq.value + 1) {
        buffered.value = { ...buffered.value, [event.seq]: event }
        scheduleGapRecovery()
      }
      return
    }

    if (event.seq <= lastSeq.value) {
      recordAndApply(event)
      return
    }
    if (event.seq > lastSeq.value + 1) {
      buffered.value = { ...buffered.value, [event.seq]: event }
      scheduleGapRecovery()
      return
    }

    recordAndApply(event)
    drainBuffer()
  }

  function scheduleGapRecovery(): void {
    if (gapTimer !== null || recoveryPromise !== null || runTerminal.value || archiveMode.value) {
      return
    }
    gapTimer = window.setTimeout(() => {
      gapTimer = null
      void recoverFromGap()
    }, 2_000)
  }

  async function recoverFromGap(): Promise<void> {
    if (!runId.value || runTerminal.value || archiveMode.value) {
      return
    }
    const target = runId.value
    await recoverSnapshot()
    if (runId.value !== target || runTerminal.value) {
      return
    }
    sse?.restart(lastSeq.value)
  }

  function clearGapTimer(): void {
    if (gapTimer !== null) {
      window.clearTimeout(gapTimer)
      gapTimer = null
    }
  }

  function applyMainSnapshot(snapshot: RunSnapshot['mainTask']): void {
    const node = ensureMain({
      taskId: snapshot.taskId,
      parentTaskId: null,
      title: snapshot.title,
      agentName: snapshot.agentName,
    })
    node.mainTaskId = snapshot.mainTaskId
    node.status = snapshot.status
    node.summary = snapshot.summary
    node.error = snapshot.error
    node.selectedOptionId = snapshot.selectedOptionId
    node.waiting = snapshot.waitingInput
    node.startedAt = snapshot.startedAt
    node.completedAt = snapshot.completedAt
  }

  function applySubtaskSnapshot(snapshot: SubtaskSnapshot): void {
    const node = ensureSubtask(snapshot)
    node.order = snapshot.order
    node.dependsOn = [...snapshot.dependsOn]
    node.status = snapshot.status
    node.summary = snapshot.summary
    node.error = snapshot.error
    node.selectedOptionId = snapshot.selectedOptionId
    node.waiting = snapshot.waitingInput
    node.startedAt = snapshot.startedAt
    node.completedAt = snapshot.completedAt
  }

  function applySnapshot(snapshot: RunSnapshot): void {
    if (snapshot.run.runId !== runId.value) {
      return
    }
    clearGapTimer()
    buffered.value = {}
    mainNode.value = null
    subtaskNodes.value = {}
    events.value = []
    duplicates.value = []
    seen.value = {}
    lastSeq.value = 0
    lastEventAt.value = null
    unknownTaskIds.value = {}
    plannedTaskIds.clear()
    orderCounter = 0
    toolKeyCounter = 0
    archiveMode.value = false

    mainTaskId.value = snapshot.run.mainTaskId
    goal.value = snapshot.run.goal
    mockScript.value = snapshot.run.mockScript
    runStatus.value = snapshot.run.status
    runSummary.value = null
    runTerminal.value = snapshot.run.status !== 'RUNNING'
    decisionSubmitting.value = false
    decisionError.value = null
    // 快照成功即说明连接已恢复；若运行本身失败，重放 error 事件会重新写入。
    globalError.value = null
    recoveryCount.value += 1

    applyMainSnapshot(snapshot.mainTask)
    for (const subtask of snapshot.subtasks) {
      applySubtaskSnapshot(subtask)
    }

    const ordered = [...snapshot.events].sort((left, right) => left.seq - right.seq)
    for (const event of ordered) {
      if (event.seq <= lastSeq.value) {
        continue
      }
      if (seen.value[event.eventId]) {
        logDuplicate(event)
        lastSeq.value = event.seq
        continue
      }
      seen.value[event.eventId] = true
      applySemantics(event)
      events.value.push(event)
      lastSeq.value = event.seq
      lastEventAt.value = event.timestamp
    }

    // 事件重放之后再用快照覆盖一次，保证等待态与终态以服务端投影为准。
    applyMainSnapshot(snapshot.mainTask)
    for (const subtask of snapshot.subtasks) {
      applySubtaskSnapshot(subtask)
    }
    lastSeq.value = Math.max(lastSeq.value, snapshot.run.lastSeq)

    const previousSelection = selectedTaskId.value
    const pendingDecision = waitingNodes.value[0]?.taskId ?? null
    selectedTaskId.value = pendingDecision
      ?? (previousSelection && nodeOf(previousSelection) ? previousSelection : null)
      ?? mainTaskNodeId()

    if (snapshot.run.status !== 'RUNNING') {
      finishRun()
    }
  }

  function markRunLost(): void {
    globalError.value = '运行已失效（后端可能已重启），请重新开始或在主任务库中查看已持久化记录。'
    runStatus.value = 'FAILED'
    runTerminal.value = true
    finishRun()
  }

  async function recoverSnapshot(): Promise<void> {
    if (!runId.value || archiveMode.value) {
      return
    }
    if (recoveryPromise) {
      return recoveryPromise
    }
    const target = runId.value
    recoveryPromise = (async () => {
      try {
        const snapshot = await getSnapshot(target)
        if (runId.value === target) {
          applySnapshot(snapshot)
        }
      } catch (error) {
        if (error instanceof ApiError && error.code === 40401) {
          markRunLost()
          return
        }
        globalError.value = `快照恢复失败：${describeError(error)}`
      } finally {
        recoveryPromise = null
      }
    })()
    return recoveryPromise
  }

  function connect(runIdValue: string, afterSeq: number): void {
    sse?.close()
    const client = new SseClient({
      runId: runIdValue,
      initialAfterSeq: afterSeq,
      getAfterSeq: () => lastSeq.value,
      beforeReconnect: async () => {
        await recoverSnapshot()
        return runTerminal.value ? null : lastSeq.value
      },
      onEvent: handleEvent,
      onStateChange: (state, attempt) => {
        // SseClient 每次连接成功都会把 attempt 归零，这里按“重连轮次”累计，
        // 而不是取最大 attempt，否则同一运行内的第二次断线会被漏算。
        if (state === 'RECONNECTING' && !wasReconnecting) {
          reconnectCount.value += 1
        }
        wasReconnecting = state === 'RECONNECTING'
        connection.value = state
        reconnectAttempt.value = attempt
      },
      onParseError: (raw, reason, seq) => {
        recordParseError(raw, reason)
        // 未知类型或坏载荷也要推进游标；否则后续事件会被永久卡在缺口里。
        if (
          seq !== null
          && !runTerminal.value
          && seq === lastSeq.value + 1
        ) {
          lastSeq.value = seq
          drainBuffer()
        }
      },
    })
    sse = client
    client.connect(afterSeq)
  }

  function recordParseError(raw: string, reason: string): void {
    parseErrorCounter += 1
    const entry: ParseErrorEntry = {
      id: `parse_${parseErrorCounter}`,
      raw: raw.slice(0, 500),
      reason,
      at: new Date().toISOString(),
    }
    parseErrors.value = [...parseErrors.value.slice(-19), entry]
  }

  function clearPlanningGapTimer(): void {
    if (planningGapTimer !== null) {
      window.clearTimeout(planningGapTimer)
      planningGapTimer = null
    }
  }

  function closePlanningConnection(): void {
    if (planningSse) {
      const client = planningSse
      planningSse = null
      client.close()
    }
    clearPlanningGapTimer()
    planningRecoveryPromise = null
    if (planningConnection.value !== 'IDLE') {
      planningConnection.value = 'DISCONNECTED'
    }
  }

  /** 清空规划会话状态；不影响运行投影与主任务库。 */
  function clearPlanningState(): void {
    closePlanningConnection()
    planSessionId.value = null
    planningStatus.value = null
    planningPlan.value = null
    planningWaiting.value = null
    planningWaitingTaskId.value = null
    planningEvents.value = []
    planningConnection.value = 'IDLE'
    planningError.value = null
    planningLastSeq.value = 0
    planningLastEventAt.value = null
    planningSeen.value = {}
    planningBuffered.value = {}
    planningStarting.value = false
    planningRevising.value = false
    planningConfirming.value = false
    planningCancelling.value = false
    planningDecisionSubmitting.value = false
  }

  /** 清掉当前运行/档案的全部投影，但保留主任务库列表。 */
  function clearRunState(): void {
    if (sse) {
      const client = sse
      sse = null
      client.close()
    }
    clearGapTimer()
    recoveryPromise = null
    runId.value = null
    mainTaskId.value = null
    mockScript.value = null
    goal.value = null
    workingDir.value = null
    runStatus.value = null
    runSummary.value = null
    runTerminal.value = false
    plan.value = null
    planError.value = null
    planRevising.value = false
    mainCreatedAt.value = null
    mainUpdatedAt.value = null
    mainNode.value = null
    subtaskNodes.value = {}
    selectedTaskId.value = null
    collapsed.value = {}
    events.value = []
    duplicates.value = []
    seen.value = {}
    buffered.value = {}
    lastSeq.value = 0
    lastEventAt.value = null
    unknownTaskIds.value = {}
    plannedTaskIds.clear()
    connection.value = 'IDLE'
    reconnectAttempt.value = 0
    reconnectCount.value = 0
    recoveryCount.value = 0
    wasReconnecting = false
    parseErrors.value = []
    decisionSubmitting.value = false
    decisionError.value = null
    globalError.value = null
    parseErrorCounter = 0
    toolKeyCounter = 0
    orderCounter = 0
  }

  function reset(): void {
    clearRunState()
    clearPlanningState()
    starting.value = false
    archiveMode.value = false
    archiveLoading.value = false
    archiveError.value = null
    libraryOpen.value = false
  }

  async function startRun(
    script: string,
    goalText?: string,
    planDraft?: PlanDraftView | null,
    workingDirValue?: string | null,
  ): Promise<boolean> {
    if (starting.value) {
      return false
    }
    reset()
    starting.value = true
    try {
      const response = await createMainTask(
        script,
        goalText,
        planDraft
          ? {
              title: planDraft.title,
              rows: planDraft.rows,
              revisions: planDraft.revisions,
            }
          : undefined,
        workingDirValue,
      )
      runId.value = response.runId
      mainTaskId.value = response.mainTaskId
      mockScript.value = response.mockScript
      workingDir.value = response.workingDir ?? workingDirValue?.trim() ?? null
      plan.value = response.plan
      mainCreatedAt.value = response.createdAt
      mainUpdatedAt.value = response.createdAt
      goal.value = response.plan?.goal?.trim() || goalText?.trim() || null
      runStatus.value = 'RUNNING'
      connection.value = 'CONNECTING'
      starting.value = false
      connect(response.runId, 0)
      return true
    } catch (error) {
      starting.value = false
      connection.value = 'IDLE'
      globalError.value = describeError(error)
      return false
    }
  }

  /** 修订已保存的计划表；返回是否成功，失败原因写入 planError。 */
  async function revisePlan(instruction: string): Promise<boolean> {
    const targetId = mainTaskId.value
    if (!targetId || planRevising.value) {
      return false
    }
    planRevising.value = true
    planError.value = null
    try {
      plan.value = await reviseMainTaskPlan(targetId, instruction)
      return true
    } catch (error) {
      planError.value = describeError(error)
      return false
    } finally {
      planRevising.value = false
    }
  }

  function selectTask(taskId: string): void {
    if (nodeOf(taskId)) {
      selectedTaskId.value = taskId
      decisionError.value = null
    }
  }

  function toggleCollapse(taskId: string): void {
    if (collapsed.value[taskId]) {
      delete collapsed.value[taskId]
    } else {
      collapsed.value[taskId] = true
    }
  }

  async function submitDecision(
    taskId: string,
    selectedOptionId: string | null,
    customInput: string | null = null,
  ): Promise<void> {
    if (archiveMode.value || !runId.value || decisionSubmitting.value) {
      return
    }
    if (!selectedOptionId && !customInput?.trim()) {
      decisionError.value = '请选择一个选项或输入自定义内容。'
      return
    }
    decisionSubmitting.value = true
    decisionError.value = null
    const target = runId.value
    try {
      await submitInput(target, taskId, selectedOptionId, customInput)
    } catch (error) {
      decisionSubmitting.value = false
      decisionError.value = describeError(error)
      if (
        error instanceof ApiError
        && (error.code === 40901 || error.code === 40903 || error.code === 40401)
      ) {
        void recoverSnapshot()
      }
    }
  }

  function dismissError(): void {
    globalError.value = null
  }

  function simulateDisconnect(): void {
    if (runTerminal.value || archiveMode.value) {
      return
    }
    sse?.simulateTransportError()
  }

  function closeConnection(): void {
    if (sse) {
      const client = sse
      sse = null
      client.close()
    }
    clearGapTimer()
  }

  function applyPlanningView(view: PlanSessionView): void {
    planningStatus.value = view.status
    if (view.plan) {
      planningPlan.value = view.plan
    }
    planningError.value = view.error
  }

  function applyPlanningEvent(event: TaskNestEvent): void {
    planningLastEventAt.value = event.timestamp
    switch (event.type) {
      case 'run_started':
        goal.value = event.payload.goal
        break
      case 'plan':
        planningPlan.value = event.payload
        planningStatus.value = 'READY'
        planningWaiting.value = null
        planningWaitingTaskId.value = null
        planningDecisionSubmitting.value = false
        planningError.value = null
        break
      case 'waiting_input':
        planningWaiting.value = event.payload
        planningWaitingTaskId.value = event.task?.taskId ?? null
        planningStatus.value = 'WAITING_INPUT'
        planningDecisionSubmitting.value = false
        break
      case 'input_received':
        planningWaiting.value = null
        planningWaitingTaskId.value = null
        planningStatus.value = 'RUNNING'
        planningDecisionSubmitting.value = false
        break
      case 'run_completed':
        // 确认与取消都会终结规划 SSE；确认结果由 REST 响应写入 CONFIRMED。
        if (!planningConfirming.value && !isTerminalPlanSession(planningStatus.value)) {
          planningStatus.value = 'CANCELLED'
        }
        planningWaiting.value = null
        planningWaitingTaskId.value = null
        closePlanningConnection()
        break
      case 'error':
        planningStatus.value = 'FAILED'
        planningError.value = `${event.payload.code} · ${event.payload.message}`
        planningWaiting.value = null
        planningWaitingTaskId.value = null
        closePlanningConnection()
        break
      default:
        break
    }
  }

  function recordPlanningEvent(event: TaskNestEvent): void {
    planningSeen.value[event.eventId] = true
    applyPlanningEvent(event)
    planningEvents.value.push(event)
    planningLastSeq.value = Math.max(planningLastSeq.value, event.seq)
  }

  function drainPlanningBuffer(): void {
    let progressed = true
    while (progressed) {
      progressed = false
      const next = planningBuffered.value[planningLastSeq.value + 1]
      if (!next) {
        break
      }
      delete planningBuffered.value[planningLastSeq.value + 1]
      progressed = true
      if (planningSeen.value[next.eventId]) {
        planningLastSeq.value = Math.max(planningLastSeq.value, next.seq)
        continue
      }
      recordPlanningEvent(next)
    }
    if (Object.keys(planningBuffered.value).length === 0) {
      clearPlanningGapTimer()
    }
  }

  function handlePlanningEvent(event: TaskNestEvent): void {
    const id = planSessionId.value
    if (!id || event.runId !== id || planningTerminal.value) {
      return
    }
    if (planningSeen.value[event.eventId]) {
      if (event.seq === planningLastSeq.value + 1) {
        planningLastSeq.value = event.seq
        drainPlanningBuffer()
      } else if (event.seq > planningLastSeq.value + 1) {
        planningBuffered.value = { ...planningBuffered.value, [event.seq]: event }
        schedulePlanningGapRecovery()
      }
      return
    }
    if (event.seq <= planningLastSeq.value) {
      recordPlanningEvent(event)
      return
    }
    if (event.seq > planningLastSeq.value + 1) {
      planningBuffered.value = { ...planningBuffered.value, [event.seq]: event }
      schedulePlanningGapRecovery()
      return
    }
    recordPlanningEvent(event)
    drainPlanningBuffer()
  }

  function schedulePlanningGapRecovery(): void {
    if (
      planningGapTimer !== null
      || planningRecoveryPromise !== null
      || planningTerminal.value
    ) {
      return
    }
    planningGapTimer = window.setTimeout(() => {
      planningGapTimer = null
      void recoverPlanningGap()
    }, 2_000)
  }

  async function recoverPlanningGap(): Promise<void> {
    const id = planSessionId.value
    if (!id || planningTerminal.value) {
      return
    }
    if (planningRecoveryPromise) {
      return planningRecoveryPromise
    }
    planningRecoveryPromise = (async () => {
      try {
        const view = await getPlanSession(id)
        if (planSessionId.value !== id) {
          return
        }
        applyPlanningView(view)
      } catch (error) {
        if (error instanceof ApiError && error.code === 40405) {
          planningStatus.value = 'FAILED'
          planningError.value = '规划会话已失效（后端可能已重启），请重新创建。'
          closePlanningConnection()
          return
        }
        planningError.value = `规划会话恢复失败：${describeError(error)}`
      } finally {
        planningRecoveryPromise = null
      }
      if (planSessionId.value !== id || planningTerminal.value) {
        return
      }
      planningSse?.restart(planningLastSeq.value)
    })()
    return planningRecoveryPromise
  }

  function connectPlanning(id: string, afterSeq: number): void {
    closePlanningConnection()
    const client = new SseClient({
      runId: id,
      initialAfterSeq: afterSeq,
      getAfterSeq: () => planningLastSeq.value,
      beforeReconnect: async () => {
        try {
          const view = await getPlanSession(id)
          if (planSessionId.value !== id) {
            return null
          }
          applyPlanningView(view)
          return planningTerminal.value ? null : planningLastSeq.value
        } catch (error) {
          if (error instanceof ApiError && error.code === 40405) {
            planningStatus.value = 'FAILED'
            planningError.value = '规划会话已失效（后端可能已重启），请重新创建。'
            return null
          }
          planningError.value = `规划会话恢复失败：${describeError(error)}`
          return planningLastSeq.value
        }
      },
      onEvent: handlePlanningEvent,
      onStateChange: (state) => {
        planningConnection.value = state
      },
      onParseError: (raw, reason, seq) => {
        recordParseError(raw, reason)
        if (seq !== null && seq === planningLastSeq.value + 1 && !planningTerminal.value) {
          planningLastSeq.value = seq
          drainPlanningBuffer()
        }
      },
      streamUrl: (after) => (
        `${apiBase()}/api/main-tasks/plan-sessions/${encodeURIComponent(id)}/events?afterSeq=${after}`
      ),
    })
    planningSse = client
    client.connect(afterSeq)
  }

  function planDraftFromPlan(
    payload: PlanPayload,
    goalText: string | null,
    script: string | null,
  ): PlanDraftView {
    const root = payload.tasks.find((task) => task.parentTaskId === null)
    const title = root?.title ?? '软件开发任务'
    const children = payload.tasks.filter((task) => task.parentTaskId === root?.taskId)
    const rows = children.map((task) => ({
      title: task.title,
      agent: task.agentName,
      dependency: task.dependsOn.join('、'),
      output: task.acceptanceCriteria?.[0] ?? task.description ?? '',
      taskId: task.taskId,
      description: task.description,
      acceptanceCriteria: task.acceptanceCriteria,
      allowedTools: task.allowedTools,
      testCommand: task.testCommand,
    }))
    const escapeCell = (value: string): string => value.replace(/\|/g, '\\|').replace(/\n/g, ' ')
    const lines = [
      `# ${title}`,
      '',
      goalText ?? '',
      '',
      '| 步骤 | 子任务 | Agent | 依赖 | 产出 |',
      '| --- | --- | --- | --- | --- |',
      ...rows.map((row, index) => (
        `| ${index + 1} | ${escapeCell(row.title)} | ${escapeCell(row.agent)} `
        + `| ${escapeCell(row.dependency || '—')} | ${escapeCell(row.output || '—')} |`
      )),
    ]
    return {
      mainTaskId: mainTaskId.value,
      title,
      goal: goalText ?? '',
      mockScript: script,
      version: payload.revision ?? 1,
      markdown: lines.join('\n'),
      rows,
      revisions: payload.reason ? [payload.reason] : [],
    }
  }

  async function startPlanning(
    goalText: string,
    workingDirValue: string,
    script?: string | null,
  ): Promise<boolean> {
    if (planningStarting.value) {
      return false
    }
    // 规划与已有运行互不影响；确认后才用新运行替换当前投影。
    clearPlanningState()
    planningStarting.value = true
    planningError.value = null
    try {
      const response = await createPlanSession(
        goalText,
        workingDirValue,
        script && script.trim().length > 0 ? script.trim() : null,
      )
      planSessionId.value = response.planSessionId
      planningStatus.value = response.status
      planningConnection.value = 'CONNECTING'
      goal.value = goalText.trim()
      workingDir.value = workingDirValue.trim()
      mockScript.value = script && script.trim().length > 0 ? script.trim() : null
      planningStarting.value = false
      // 从 0 开始补发历史，Mock 计划可能在创建响应前就已发布。
      connectPlanning(response.planSessionId, 0)
      return true
    } catch (error) {
      planningStarting.value = false
      planningConnection.value = 'IDLE'
      planningError.value = describeError(error)
      return false
    }
  }

  async function revisePlanning(instruction: string): Promise<boolean> {
    const id = planSessionId.value
    const text = instruction.trim()
    if (!id || !text || planningRevising.value || planningStatus.value !== 'READY') {
      return false
    }
    planningRevising.value = true
    planningError.value = null
    try {
      await revisePlanSession(id, text)
      planningStatus.value = 'RUNNING'
      return true
    } catch (error) {
      planningError.value = describeError(error)
      return false
    } finally {
      planningRevising.value = false
    }
  }

  async function submitPlanningDecision(
    taskId: string,
    selectedOptionId: string | null,
    customInput: string | null = null,
  ): Promise<void> {
    const id = planSessionId.value
    if (!id || planningDecisionSubmitting.value) {
      return
    }
    if (!selectedOptionId && !customInput?.trim()) {
      planningError.value = '请选择一个选项或输入自定义内容。'
      return
    }
    planningDecisionSubmitting.value = true
    planningError.value = null
    try {
      await submitPlanSessionInput(id, taskId, selectedOptionId, customInput)
    } catch (error) {
      planningDecisionSubmitting.value = false
      planningError.value = describeError(error)
      if (error instanceof ApiError && error.code === 40901) {
        void recoverPlanningGap()
      }
    }
  }

  async function confirmPlanning(): Promise<boolean> {
    const id = planSessionId.value
    if (!id || planningConfirming.value) {
      return false
    }
    if (planningStatus.value !== 'READY' || !planningPlan.value) {
      planningError.value = '计划尚未生成完成，不能确认。'
      return false
    }
    planningConfirming.value = true
    planningError.value = null
    const plannedPlan = planningPlan.value
    const goalText = goal.value
    const script = mockScript.value
    try {
      const response = await confirmPlanSession(id)
      planningStatus.value = 'CONFIRMED'
      planningWaiting.value = null
      planningWaitingTaskId.value = null
      closePlanningConnection()

      clearRunState()
      archiveMode.value = false
      runId.value = response.runId
      mainTaskId.value = response.mainTaskId
      workingDir.value = response.workingDir
      goal.value = goalText
      mockScript.value = script
      plan.value = planDraftFromPlan(plannedPlan, goalText, script)
      mainCreatedAt.value = new Date().toISOString()
      mainUpdatedAt.value = mainCreatedAt.value
      runStatus.value = 'RUNNING'
      connection.value = 'CONNECTING'

      const root = plannedPlan.tasks.find((task) => task.parentTaskId === null)
      if (root) {
        const main = ensureMain(root)
        main.planRevision = plannedPlan.revision
        main.planReason = plannedPlan.reason
        main.planChangedTaskIds = plannedPlan.changedTaskIds ?? []
      }

      planningConfirming.value = false
      connect(response.runId, 0)
      return true
    } catch (error) {
      planningConfirming.value = false
      planningError.value = describeError(error)
      if (error instanceof ApiError && error.code === 40405) {
        planningStatus.value = 'FAILED'
      }
      return false
    }
  }

  async function cancelPlanning(): Promise<boolean> {
    const id = planSessionId.value
    if (!id || planningCancelling.value) {
      return false
    }
    planningCancelling.value = true
    planningError.value = null
    try {
      await cancelPlanSession(id)
      planningStatus.value = 'CANCELLED'
      planningWaiting.value = null
      planningWaitingTaskId.value = null
      closePlanningConnection()
      return true
    } catch (error) {
      planningError.value = describeError(error)
      return false
    } finally {
      planningCancelling.value = false
    }
  }

  async function loadMainTasks(): Promise<void> {
    if (libraryLoading.value) {
      return
    }
    libraryLoading.value = true
    libraryError.value = null
    try {
      library.value = await listMainTasks()
    } catch (error) {
      libraryError.value = describeError(error)
    } finally {
      libraryLoading.value = false
    }
  }

  function toggleLibrary(): void {
    libraryOpen.value = !libraryOpen.value
    if (libraryOpen.value) {
      void loadMainTasks()
    }
  }

  function closeLibrary(): void {
    libraryOpen.value = false
  }

  async function openMainTask(targetId: string): Promise<void> {
    const request = ++archiveRequest
    clearRunState()
    archiveMode.value = true
    archiveLoading.value = true
    archiveError.value = null
    try {
      const detail = await getMainTaskDetail(targetId)
      if (request !== archiveRequest) {
        return
      }
      applyArchive(detail)
      libraryOpen.value = false
    } catch (error) {
      if (request === archiveRequest) {
        archiveError.value = describeError(error)
        archiveMode.value = false
      }
    } finally {
      if (request === archiveRequest) {
        archiveLoading.value = false
      }
    }
  }

  function applyArchive(detail: MainTaskDetail): void {
    const summary = detail.mainTask
    runId.value = summary.runId
    mainTaskId.value = summary.mainTaskId
    mockScript.value = summary.mockScript
    workingDir.value = summary.workingDir
    goal.value = summary.goal
    plan.value = detail.plan
    planError.value = null
    mainCreatedAt.value = summary.createdAt
    mainUpdatedAt.value = summary.updatedAt
    runStatus.value = summary.status === 'COMPLETED'
      ? 'COMPLETED'
      : summary.status === 'FAILED'
        ? 'FAILED'
        : 'RUNNING'
    runSummary.value = summary.summary
    connection.value = 'DISCONNECTED'
    runTerminal.value = true
    archiveMode.value = true

    const node = ensureMain({
      taskId: summary.rootTaskId ?? summary.mainTaskId,
      parentTaskId: null,
      title: summary.title,
      agentName: '主任务',
    })
    node.status = summary.status
    node.summary = summary.summary
    node.error = summary.error
    node.completedAt = summary.completedAt

    for (const subtask of detail.subtasks) {
      const child = ensureSubtask({
        taskId: subtask.taskId,
        title: subtask.title,
        agentName: subtask.agentName,
        status: subtask.status,
        summary: subtask.summary,
        error: subtask.error,
        dependsOn: subtask.dependsOn,
        order: subtask.order,
        selectedOptionId: subtask.selectedOptionId,
        waitingInput: null,
        startedAt: subtask.startedAt,
        completedAt: subtask.completedAt,
      })
      child.status = subtask.status
      child.summary = subtask.summary
      child.error = subtask.error
      child.selectedOptionId = subtask.selectedOptionId
      child.startedAt = subtask.startedAt
      child.completedAt = subtask.completedAt
      if (subtask.waitingQuestion) {
        child.waiting = {
          question: subtask.waitingQuestion,
          options: subtask.waitingOptions,
          allowCustom: true,
          customPlaceholder: null,
        }
      }
    }

    const ordered = [...detail.events]
      .map((raw) => parseEventValue(raw))
      .filter((event): event is TaskNestEvent => event !== null)
      .sort((left, right) => left.seq - right.seq)
    for (const event of ordered) {
      if (event.runId !== runId.value || seen.value[event.eventId]) {
        continue
      }
      seen.value[event.eventId] = true
      applySemantics(event)
      events.value.push(event)
      lastSeq.value = Math.max(lastSeq.value, event.seq)
      lastEventAt.value = event.timestamp
    }
    lastSeq.value = Math.max(lastSeq.value, detail.lastSeq)

    // 事件回放后重新写入权威档案状态，避免历史事件缺失导致状态回退。
    const archivedMain = ensureMain()
    archivedMain.status = summary.status
    archivedMain.summary = summary.summary
    archivedMain.error = summary.error
    for (const subtask of detail.subtasks) {
      const child = ensureSubtask({
        taskId: subtask.taskId,
        title: subtask.title,
        agentName: subtask.agentName,
        status: subtask.status,
        summary: subtask.summary,
        error: subtask.error,
        dependsOn: subtask.dependsOn,
        order: subtask.order,
        selectedOptionId: subtask.selectedOptionId,
        waitingInput: null,
        startedAt: subtask.startedAt,
        completedAt: subtask.completedAt,
      })
      child.status = subtask.status
      child.summary = subtask.summary
      child.error = subtask.error
      child.selectedOptionId = subtask.selectedOptionId
      if (subtask.waitingQuestion) {
        child.waiting = {
          question: subtask.waitingQuestion,
          options: subtask.waitingOptions,
          allowCustom: true,
          customPlaceholder: null,
        }
      }
    }

    if (events.value.length !== detail.events.length) {
      recordParseError(
        `持久化事件 ${detail.events.length} 条`,
        `有 ${detail.events.length - events.value.length} 条事件字段不合法，已跳过`,
      )
    }

    runTerminal.value = true
    connection.value = 'DISCONNECTED'
    mainUpdatedAt.value = summary.updatedAt
    selectedTaskId.value = archivedMain.taskId || mainNode.value?.taskId || null
  }

  function backToLive(): void {
    reset()
    void loadMainTasks()
  }

  return {
    runId,
    mainTaskId,
    mockScript,
    goal,
    workingDir,
    runStatus,
    runSummary,
    runTerminal,
    plan,
    planError,
    planRevising,
    mainCreatedAt,
    mainUpdatedAt,
    mainNode,
    subtaskNodes,
    subtaskList,
    selectedTaskId,
    collapsed,
    events,
    duplicates,
    lastSeq,
    lastEventAt,
    unknownTaskIds,
    connection,
    reconnectAttempt,
    reconnectCount,
    recoveryCount,
    parseErrors,
    starting,
    decisionSubmitting,
    decisionError,
    globalError,
    planSessionId,
    planningStatus,
    planningPlan,
    planningWaiting,
    planningWaitingTaskId,
    planningEvents,
    planningConnection,
    planningError,
    planningLastSeq,
    planningLastEventAt,
    planningStarting,
    planningRevising,
    planningConfirming,
    planningCancelling,
    planningDecisionSubmitting,
    planningTerminal,
    archiveMode,
    archiveLoading,
    archiveError,
    library,
    libraryLoading,
    libraryError,
    libraryOpen,
    mainTaskLibraryCount,
    selectedNode,
    waitingNodes,
    subtaskCount,
    taskCount,
    successCount,
    failedCount,
    waitingCount,
    unknownTaskCount,
    startRun,
    revisePlan,
    startPlanning,
    revisePlanning,
    submitPlanningDecision,
    confirmPlanning,
    cancelPlanning,
    clearPlanningState,
    selectTask,
    toggleCollapse,
    submitDecision,
    dismissError,
    simulateDisconnect,
    closeConnection,
    loadMainTasks,
    toggleLibrary,
    closeLibrary,
    openMainTask,
    backToLive,
  }
})
