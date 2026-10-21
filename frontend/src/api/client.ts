import {
  parsePlanSessionView,
  type AcceptedResponse,
  type AgentSettingsView,
  type ConfirmPlanSessionResponse,
  type CreateMainTaskResponse,
  type CreatePlanSessionResponse,
  type MainTaskDetail,
  type MainTaskSummary,
  type PlanDraftView,
  type PlanRowView,
  type PlanSessionView,
  type RunSnapshot,
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

function jsonInit(method: 'POST' | 'PUT', payload: unknown): RequestInit {
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
  workingDir?: string | null,
): Promise<CreateMainTaskResponse> {
  const payload: {
    mockScript: string
    goal?: string
    workingDir?: string
    plan?: PlanDraftInput
  } = { mockScript }
  if (goal && goal.trim().length > 0) {
    payload.goal = goal.trim()
  }
  if (workingDir && workingDir.trim().length > 0) {
    payload.workingDir = workingDir.trim()
  }
  if (plan) {
    payload.plan = plan
  }
  return request<CreateMainTaskResponse>('/api/main-tasks', jsonInit('POST', payload))
}

/** 创建规划会话；不传 mockScript 时使用真实规划 Agent。 */
export function createPlanSession(
  goal: string,
  workingDir: string,
  mockScript?: string | null,
): Promise<CreatePlanSessionResponse> {
  const payload: { goal: string; workingDir: string; mockScript?: string } = {
    goal: goal.trim(),
    workingDir: workingDir.trim(),
  }
  if (mockScript && mockScript.trim().length > 0) {
    payload.mockScript = mockScript.trim()
  }
  return request<CreatePlanSessionResponse>(
    '/api/main-tasks/plan-sessions',
    jsonInit('POST', payload),
  )
}

export async function getPlanSession(planSessionId: string): Promise<PlanSessionView> {
  const value = await request<unknown>(
    `/api/main-tasks/plan-sessions/${encodeURIComponent(planSessionId)}`,
  )
  const parsed = parsePlanSessionView(value)
  if (!parsed) {
    throw invalidPayload()
  }
  return parsed
}

export function revisePlanSession(planSessionId: string, instruction: string): Promise<null> {
  return request<null>(
    `/api/main-tasks/plan-sessions/${encodeURIComponent(planSessionId)}/revisions`,
    jsonInit('POST', { instruction }),
  )
}

export function submitPlanSessionInput(
  planSessionId: string,
  taskId: string,
  selectedOptionId: string | null,
  customInput: string | null,
): Promise<AcceptedResponse> {
  const payload: { taskId: string; selectedOptionId?: string; customInput?: string } = { taskId }
  if (selectedOptionId) {
    payload.selectedOptionId = selectedOptionId
  }
  if (customInput && customInput.trim().length > 0) {
    payload.customInput = customInput.trim()
  }
  return request<AcceptedResponse>(
    `/api/main-tasks/plan-sessions/${encodeURIComponent(planSessionId)}/inputs`,
    jsonInit('POST', payload),
  )
}

export function confirmPlanSession(
  planSessionId: string,
): Promise<ConfirmPlanSessionResponse> {
  return request<ConfirmPlanSessionResponse>(
    `/api/main-tasks/plan-sessions/${encodeURIComponent(planSessionId)}/confirm`,
    jsonInit('POST', {}),
  )
}

export function cancelPlanSession(planSessionId: string): Promise<null> {
  return request<null>(
    `/api/main-tasks/plan-sessions/${encodeURIComponent(planSessionId)}/cancel`,
    jsonInit('POST', {}),
  )
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
  selectedOptionId: string | null,
  customInput: string | null = null,
): Promise<AcceptedResponse> {
  const payload: { taskId: string; selectedOptionId?: string; customInput?: string } = { taskId }
  if (selectedOptionId) {
    payload.selectedOptionId = selectedOptionId
  }
  if (customInput && customInput.trim().length > 0) {
    payload.customInput = customInput.trim()
  }
  return request<AcceptedResponse>(
    `/api/runs/${encodeURIComponent(runId)}/inputs`,
    jsonInit('POST', payload),
  )
}

export interface DirectoryEntry {
  name: string
  path: string
  hidden: boolean
}

export interface DirectoryListing {
  path: string
  parent: string | null
  name: string
  roots: string[]
  directories: DirectoryEntry[]
  truncated: boolean
}

export type ApiKeyType = 'LITERAL' | 'ENV'

/** 与后端 LlmSettingsView 对齐；literal 密钥只返回掩码，不返回明文。 */
export interface LlmSettingsView {
  baseUrl: string
  modelName: string
  apiKeyType: ApiKeyType
  apiKeyValue: string | null
  apiKeyPreview: string | null
  apiKeyConfigured: boolean
  envResolved: boolean
  updatedAt: string | null
}

export interface UpdateLlmSettingsInput {
  baseUrl: string
  /** OpenAI 兼容模型 ID，自由文本，最长 128 字。 */
  modelName: string
  apiKeyType: ApiKeyType
  /** LITERAL 留空表示沿用已保存密钥；ENV 必填环境变量名。 */
  apiKeyValue?: string
}

function invalidPayload(): ApiError {
  return new ApiError('后端返回的数据格式不正确。', -1, 200)
}

function readNullableString(record: Record<string, unknown>, key: string): string | null {
  const value = record[key]
  if (value === null || value === undefined) {
    return null
  }
  if (typeof value !== 'string') {
    throw invalidPayload()
  }
  return value
}

function parseDirectoryListing(value: unknown): DirectoryListing {
  if (!isRecord(value) || !Array.isArray(value.directories)) {
    throw invalidPayload()
  }
  const parent = value.parent
  const roots = value.roots
  if (
    typeof value.path !== 'string'
    || typeof value.name !== 'string'
    || !(parent === null || typeof parent === 'string')
    || !Array.isArray(roots)
    || !roots.every((root) => typeof root === 'string')
    || typeof value.truncated !== 'boolean'
  ) {
    throw invalidPayload()
  }
  const directories: DirectoryEntry[] = []
  for (const item of value.directories) {
    if (
      !isRecord(item)
      || typeof item.name !== 'string'
      || typeof item.path !== 'string'
      || typeof item.hidden !== 'boolean'
    ) {
      throw invalidPayload()
    }
    directories.push({ name: item.name, path: item.path, hidden: item.hidden })
  }
  return {
    path: value.path,
    parent,
    name: value.name,
    roots: roots as string[],
    directories,
    truncated: value.truncated,
  }
}

function parseLlmSettings(value: unknown): LlmSettingsView {
  if (!isRecord(value)) {
    throw invalidPayload()
  }
  const type = value.apiKeyType
  if (
    typeof value.baseUrl !== 'string'
    || typeof value.modelName !== 'string'
    || value.modelName.trim().length === 0
    || (type !== 'LITERAL' && type !== 'ENV')
    || typeof value.apiKeyConfigured !== 'boolean'
    || typeof value.envResolved !== 'boolean'
  ) {
    throw invalidPayload()
  }
  return {
    baseUrl: value.baseUrl,
    modelName: value.modelName,
    apiKeyType: type,
    apiKeyValue: readNullableString(value, 'apiKeyValue'),
    apiKeyPreview: readNullableString(value, 'apiKeyPreview'),
    apiKeyConfigured: value.apiKeyConfigured,
    envResolved: value.envResolved,
    updatedAt: readNullableString(value, 'updatedAt'),
  }
}

/** 浏览后端机器上的本地目录；只返回文件夹，不读取文件内容。 */
export async function browseDirectories(
  path?: string | null,
  showHidden = false,
): Promise<DirectoryListing> {
  const params = new URLSearchParams()
  if (path && path.trim().length > 0) {
    params.set('path', path.trim())
  }
  if (showHidden) {
    params.set('showHidden', 'true')
  }
  const query = params.toString()
  const listing = await request<unknown>(`/api/fs/directories${query ? `?${query}` : ''}`)
  return parseDirectoryListing(listing)
}

export async function getLlmSettings(): Promise<LlmSettingsView> {
  return parseLlmSettings(await request<unknown>('/api/settings/llm'))
}

export async function updateLlmSettings(
  input: UpdateLlmSettingsInput,
): Promise<LlmSettingsView> {
  return parseLlmSettings(await request<unknown>(
    '/api/settings/llm',
    jsonInit('PUT', {
      baseUrl: input.baseUrl,
      modelName: input.modelName,
      apiKeyType: input.apiKeyType,
      apiKeyValue: input.apiKeyValue,
    }),
  ))
}

function parseAgentSettings(value: unknown): AgentSettingsView {
  if (
    !isRecord(value)
    || typeof value.reviewEnabled !== 'boolean'
    || typeof value.reviewMaxReworks !== 'number'
    || !Number.isInteger(value.reviewMaxReworks)
  ) {
    throw invalidPayload()
  }
  return {
    reviewEnabled: value.reviewEnabled,
    reviewMaxReworks: value.reviewMaxReworks,
    updatedAt: readNullableString(value, 'updatedAt'),
  }
}

export async function getAgentSettings(): Promise<AgentSettingsView> {
  return parseAgentSettings(await request<unknown>('/api/settings/agent'))
}

export async function updateAgentSettings(
  reviewEnabled: boolean,
  reviewMaxReworks: number,
): Promise<AgentSettingsView> {
  return parseAgentSettings(await request<unknown>(
    '/api/settings/agent',
    jsonInit('PUT', { reviewEnabled, reviewMaxReworks }),
  ))
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
