import type { MainTaskStatus } from '@/api/events'

/** 目录条目：合并 MySQL 主任务档案与当前内存中的实时运行。 */
export interface DirectoryTask {
  id: string
  title: string
  goal: string | null
  mockScript: string
  workingDir: string | null
  status: MainTaskStatus
  subtaskCount: number
  completedCount: number
  planVersion: number
  createdAt: string | null
  updatedAt: string | null
  live: boolean
}
