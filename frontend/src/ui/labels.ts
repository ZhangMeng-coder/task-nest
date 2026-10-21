import type {
  ConnectionState,
  EventType,
  GitAction,
  MainTaskStatus,
  PlanSessionStatus,
  ReviewSeverity,
  ReviewVerdict,
  RunStatus,
  SubtaskStatus,
  TaskStatus,
  TestStatus,
  ToolStatus,
} from '@/api/events'

export const MAIN_TASK_STATUS_LABEL: Record<MainTaskStatus, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  WAITING_INPUT: '等待抉择',
  COMPLETED: '已完成',
  FAILED: '失败',
}

export const SUBTASK_STATUS_LABEL: Record<SubtaskStatus, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  WAITING_INPUT: '等待抉择',
  RETRYING: '重试中',
  REVIEWING: '审核中',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
}

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  WAITING_INPUT: '等待输入',
  RETRYING: '重试中',
  REVIEWING: '审核中',
  SUCCESS: '已完成',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

export const PLAN_SESSION_STATUS_LABEL: Record<PlanSessionStatus, string> = {
  RUNNING: '规划中',
  WAITING_INPUT: '等待抉择',
  READY: '待确认',
  CONFIRMED: '已确认',
  CANCELLED: '已取消',
  FAILED: '规划失败',
}

export const REVIEW_VERDICT_LABEL: Record<ReviewVerdict, string> = {
  PASS: '通过',
  REWORK: '需要返工',
}

export const REVIEW_SEVERITY_LABEL: Record<ReviewSeverity, string> = {
  BLOCKER: '阻断',
  MAJOR: '重要',
  MINOR: '次要',
}

export const TEST_STATUS_LABEL: Record<TestStatus, string> = {
  PASSED: '测试通过',
  FAILED: '测试失败',
  UNAVAILABLE: '测试不可用',
}

export const GIT_ACTION_LABEL: Record<GitAction, string> = {
  branch_created: '创建分支',
  committed: '提交改动',
  merged: '合并分支',
  rolled_back: '回滚分支',
  branch_deleted: '删除分支',
}

export const RUN_STATUS_LABEL: Record<RunStatus, string> = {
  RUNNING: '进行中',
  COMPLETED: '已完成',
  FAILED: '失败',
}

export const CONNECTION_LABEL: Record<ConnectionState, string> = {
  IDLE: '未开始',
  CONNECTING: '连接中',
  CONNECTED: '已连接',
  RECONNECTING: '重连中',
  DISCONNECTED: '已断开',
}

export const TOOL_STATUS_LABEL: Record<ToolStatus, string> = {
  success: '成功',
  error: '失败',
}

export const EVENT_TYPE_LABEL: Record<EventType, string> = {
  run_started: '运行开始',
  plan: '任务规划',
  task_started: '任务开始',
  task_thought: '思考增量',
  prompt_assembled: '提示词拼接',
  tool_call: '工具调用',
  tool_result: '工具返回',
  waiting_input: '等待抉择',
  input_received: '选择已提交',
  task_completed: '任务完成',
  task_failed: '任务失败',
  review_started: '开始审核',
  review_completed: '审核完成',
  git_checkpoint: 'Git 检查点',
  run_completed: '运行完成',
  error: '系统错误',
  heartbeat: '心跳',
}

export interface ScriptOption {
  id: string
  name: string
  summary: string
}

export const SCRIPT_OPTIONS: ScriptOption[] = [
  {
    id: 'happy-path',
    name: '顺利路径',
    summary: '两层任务全部成功，先看清结构、思考与工具调用。',
  },
  {
    id: 'with-ambiguity',
    name: '需要抉择',
    summary: '执行到中途暂停，等你选择一个缓存策略再继续。',
  },
  {
    id: 'with-failure',
    name: '失败与重试',
    summary: '访问凭据首次失效，重试后恢复，观察重试状态。',
  },
  {
    id: 'with-noise',
    name: '噪声投递',
    summary: '重复事件、乱序到达与丢投，验证工作台能否收敛。',
  },
]

export function scriptName(id: string | null): string {
  if (!id) {
    return '真实 Agent'
  }
  return SCRIPT_OPTIONS.find((option) => option.id === id)?.name ?? id
}

export function formatClock(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  const millis = String(date.getMilliseconds()).padStart(3, '0')
  return `${hours}:${minutes}:${seconds}.${millis}`
}

export function isTerminalTaskStatus(status: TaskStatus): boolean {
  return status === 'SUCCESS'
    || status === 'COMPLETED'
    || status === 'FAILED'
    || status === 'CANCELLED'
}

/** 主任务和子任务各自使用独立状态枚举，标签按节点类型取。 */
export function nodeStatusLabel(kind: 'MAIN' | 'SUBTASK', status: TaskStatus): string {
  if (kind === 'MAIN') {
    return MAIN_TASK_STATUS_LABEL[status as MainTaskStatus] ?? status
  }
  return SUBTASK_STATUS_LABEL[status as SubtaskStatus] ?? status
}

export function mainTagType(
  status: MainTaskStatus,
): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'WAITING_INPUT') return 'warning'
  if (status === 'RUNNING') return 'primary'
  return 'info'
}

export function subtaskTagType(
  status: SubtaskStatus,
): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'WAITING_INPUT' || status === 'RETRYING') return 'warning'
  if (status === 'REVIEWING' || status === 'RUNNING') return 'primary'
  return 'info'
}

export function formatDuration(ms: number | null): string {
  if (ms === null) {
    return '—'
  }
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

export function formatDateTime(iso: string | null): string {
  if (!iso) {
    return '—'
  }
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 次数标签统一两位对齐，目录里的序号与事件序号共用。 */
export function pad2(value: number): string {
  return String(value).padStart(2, '0')
}
