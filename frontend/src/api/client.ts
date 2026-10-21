import type {
  AcceptedResponse,
  CreateMainTaskResponse,
  MainTaskDetail,
  MainTaskSummary,
  PlanDraftView,
  PlanRowView,
  RunSnapshot,
} from './events'

const API_BASE = (import.meta.env.VITE_API_BASE ?? 'http://127.0.0.1:8080').replace(/\/+$/, '')

export function apiBase(): string {
  return API_BASE
}

export class ApiError extends Error {
  readonly code: number
  readonly status: number

  constructor(message: string, code: number, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, init)
  } catch {
    throw new ApiError(`无法连接后端 ${API_BASE}，请确认服务已启动。`, -1, 0)
  }

  let body: unknown
  try {
    body = await response.json()
  } catch {
    throw new ApiError(`后端返回了无法解析的响应（HTTP ${response.status}）。`, -1, response.status)
  }

  if (!isRecord(body) || typeof body.code !== 'number' || typeof body.message !== 'string') {
    throw new ApiError('后端响应不符合统一包装格式。', -1, response.status)
  }
  if (!response.ok || body.code !== 0) {
    throw new ApiError(body.message, body.code, response.status)
  }
  return body.data as T
}

function jsonInit(method: 'POST', payload: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }
}

/** 创建主任务时提交的确认稿；Markdown 由后端按 rows 重新生成。 */
export interface PlanDraftInput {
  title: string
  rows: PlanRowView[]
  revisions: string[]
}

/** 创建前生成计划表草案，不启动运行。 */
export function generatePlanDraft(mockScript: string, goal: string): Promise<PlanDraftView> {
  return request<PlanDraftView>(
    '/api/main-tasks/plan-drafts',
    jsonInit('POST', { mockScript, goal }),
  )
}

/** 用自然语言提示修订尚未确认的计划表草案。 */
export function revisePlanDraft(
  draft: PlanDraftView,
  instruction: string,
): Promise<PlanDraftView> {
  return request<PlanDraftView>(
    '/api/main-tasks/plan-drafts/revise',
    jsonInit('POST', {
      mockScript: draft.mockScript,
      title: draft.title,
      goal: draft.goal,
      rows: draft.rows,
      revisions: draft.revisions,
      instruction,
    }),
  )
}

/** 修订已保存到主任务档案中的计划表。 */
export function reviseMainTaskPlan(
  mainTaskId: string,
  instruction: string,
): Promise<PlanDraftView> {
  return request<PlanDraftView>(
    `/api/main-tasks/${encodeURIComponent(mainTaskId)}/plan/revisions`,
    jsonInit('POST', { instruction }),
  )
}

/** 创建一个会写入 MySQL 的主任务，并立即启动 Mock 编排。 */
export function createMainTask(
  mockScript: string,
  goal?: string,
  plan?: PlanDraftInput,
): Promise<CreateMainTaskResponse> {
  const payload: { mockScript: string; goal?: string; plan?: PlanDraftInput } = { mockScript }
  if (goal && goal.trim().length > 0) {
    payload.goal = goal.trim()
  }
  if (plan) {
    payload.plan = plan
  }
  return request<CreateMainTaskResponse>('/api/main-tasks', jsonInit('POST', payload))
}

export function listMainTasks(limit = 50): Promise<MainTaskSummary[]> {
  return request<MainTaskSummary[]>(`/api/main-tasks?limit=${limit}`)
}

export function getMainTaskDetail(mainTaskId: string): Promise<MainTaskDetail> {
  return request<MainTaskDetail>(`/api/main-tasks/${encodeURIComponent(mainTaskId)}`)
}

export function getSnapshot(runId: string): Promise<RunSnapshot> {
  return request<RunSnapshot>(`/api/runs/${encodeURIComponent(runId)}`)
}

export function submitInput(
  runId: string,
  taskId: string,
  selectedOptionId: string,
): Promise<AcceptedResponse> {
  return request<AcceptedResponse>(
    `/api/runs/${encodeURIComponent(runId)}/inputs`,
    jsonInit('POST', { taskId, selectedOptionId }),
  )
}

export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return String(error)
}
