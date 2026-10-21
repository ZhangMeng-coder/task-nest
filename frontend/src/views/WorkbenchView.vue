<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import AppHeader from '@/components/AppHeader.vue'
import CreateTaskPanel from '@/components/CreateTaskPanel.vue'
import EventLogDialog from '@/components/EventLogDialog.vue'
import TaskDirectory from '@/components/TaskDirectory.vue'
import TaskWorkspace from '@/components/TaskWorkspace.vue'
import type { PlanDraftView, TaskNestEvent } from '@/api/events'
import { useTaskTreeStore } from '@/stores/taskTree'
import type { DirectoryTask } from '@/ui/directory'

type NotificationOptions = Parameters<typeof ElNotification>[0]
interface NotificationHandle {
  close: () => void
}

const store = useTaskTreeStore()

const mode = ref<'task' | 'create'>('task')
const logOpen = ref(false)
const focusTaskId = ref<string | null>(null)
const pendingId = ref<string | null>(null)

const selectedId = computed(() => store.mainTaskId ?? pendingId.value)

const entries = computed<DirectoryTask[]>(() => {
  const map = new Map<string, DirectoryTask>()
  for (const summary of store.library) {
    map.set(summary.mainTaskId, {
      id: summary.mainTaskId,
      title: summary.title,
      goal: summary.goal,
      mockScript: summary.mockScript,
      status: summary.status,
      subtaskCount: summary.subtaskCount,
      completedCount: summary.completedSubtaskCount,
      planVersion: summary.planVersion,
      createdAt: summary.createdAt,
      updatedAt: summary.updatedAt,
      live: false,
    })
  }

  const liveId = store.mainTaskId
  if (liveId && store.mainNode) {
    const existing = map.get(liveId)
    map.set(liveId, {
      id: liveId,
      title: store.mainNode.title,
      goal: store.goal ?? existing?.goal ?? null,
      mockScript: store.mockScript ?? existing?.mockScript ?? '',
      status: store.mainNode.status,
      subtaskCount: store.subtaskCount || existing?.subtaskCount || 0,
      completedCount: store.successCount,
      planVersion: store.plan?.version ?? existing?.planVersion ?? 0,
      createdAt: store.mainCreatedAt ?? existing?.createdAt ?? null,
      updatedAt: store.mainUpdatedAt ?? existing?.updatedAt ?? null,
      live: !store.archiveMode,
    })
  }

  return [...map.values()].sort((left, right) => {
    if (left.live !== right.live) {
      return left.live ? -1 : 1
    }
    return (right.createdAt ?? '').localeCompare(left.createdAt ?? '')
  })
})

const selectedEntry = computed(() => (
  entries.value.find((entry) => entry.id === selectedId.value) ?? null
))

const activeCount = computed(() => (
  entries.value.filter((entry) => (
    entry.status === 'RUNNING' || entry.status === 'WAITING_INPUT'
  )).length
))

const logTitle = computed(() => selectedEntry.value?.title ?? store.mainNode?.title ?? '主任务')

let nextNotificationAt = 0
const waitingHandles = new Map<string, NotificationHandle>()
const notified = new Set<string>()
let eventPointer = 0

/** Element Plus 通知需要错开创建时间，避免同一 tick 里算出相同 offset。 */
function notify(options: NotificationOptions): NotificationHandle {
  const now = Date.now()
  const delay = Math.max(0, nextNotificationAt - now)
  nextNotificationAt = now + delay + 420
  let handle: NotificationHandle | null = null
  let cancelled = false
  const timer = window.setTimeout(() => {
    if (!cancelled) {
      handle = ElNotification(options)
    }
  }, delay)
  return {
    close: () => {
      cancelled = true
      window.clearTimeout(timer)
      handle?.close()
    },
  }
}

function closeWaiting(taskId: string): void {
  const handle = waitingHandles.get(taskId)
  if (!handle) {
    return
  }
  waitingHandles.delete(taskId)
  handle.close()
}

function closeAllWaiting(): void {
  for (const handle of waitingHandles.values()) {
    handle.close()
  }
  waitingHandles.clear()
}

function focusTask(taskId: string): void {
  mode.value = 'task'
  logOpen.value = false
  store.selectTask(taskId)
  focusTaskId.value = taskId
}

function handleEvent(event: TaskNestEvent): void {
  if (store.archiveMode || notified.has(event.eventId)) {
    return
  }
  notified.add(event.eventId)

  switch (event.type) {
    case 'waiting_input': {
      if (!event.task) {
        break
      }
      const taskId = event.task.taskId
      closeWaiting(taskId)
      const handle = notify({
        title: '子任务需要你的抉择',
        message: `${event.task.title}：${event.payload.question}（点击通知处理）`,
        type: 'warning',
        duration: 0,
        onClick: () => {
          closeWaiting(taskId)
          focusTask(taskId)
        },
        onClose: () => waitingHandles.delete(taskId),
      })
      waitingHandles.set(taskId, handle)
      break
    }
    case 'input_received': {
      if (event.task) {
        closeWaiting(event.task.taskId)
      }
      break
    }
    case 'task_completed': {
      if (event.task?.parentTaskId) {
        notify({
          title: `子任务完成 · ${event.task.title}`,
          message: event.payload.summary || '产出已写入主任务档案。',
          type: 'success',
          duration: 4200,
          onClick: () => {
            const taskId = event.task?.taskId
            if (taskId) {
              focusTask(taskId)
            }
          },
        })
      }
      break
    }
    case 'task_failed': {
      if (event.task) {
        closeWaiting(event.task.taskId)
      }
      if (event.payload.retryable) {
        notify({
          title: `子任务重试中 · ${event.task?.title ?? '子任务'}`,
          message: event.payload.error,
          type: 'warning',
          duration: 4500,
        })
      } else {
        notify({
          title: `子任务失败 · ${event.task?.title ?? '子任务'}`,
          message: event.payload.error,
          type: 'error',
          duration: 0,
          onClick: () => {
            if (event.task) {
              focusTask(event.task.taskId)
            }
          },
        })
      }
      break
    }
    case 'run_completed': {
      closeAllWaiting()
      notify({
        title: '主任务完成',
        message: event.payload.summary || '所有子任务已结束，点击查看最终结果。',
        type: 'success',
        duration: 6000,
        onClick: () => {
          mode.value = 'task'
        },
      })
      break
    }
    case 'error': {
      closeAllWaiting()
      notify({
        title: '运行失败',
        message: `${event.payload.code}：${event.payload.message}`,
        type: 'error',
        duration: 0,
      })
      break
    }
    default:
      break
  }
}

watch(
  () => store.mainTaskId,
  () => {
    eventPointer = 0
    notified.clear()
    nextNotificationAt = 0
    closeAllWaiting()
  },
)

watch(
  () => store.events.length,
  (length) => {
    if (length < eventPointer) {
      eventPointer = 0
    }
    while (eventPointer < length) {
      const event = store.events[eventPointer]
      eventPointer += 1
      if (event) {
        handleEvent(event)
      }
    }
  },
)

watch(
  () => store.archiveMode,
  (archive) => {
    if (archive) {
      closeAllWaiting()
    }
  },
)

watch(
  () => store.runTerminal,
  (terminal) => {
    if (terminal) {
      closeAllWaiting()
      void store.loadMainTasks()
    }
  },
)

watch(
  () => store.globalError,
  (message) => {
    if (message) {
      ElMessage.error(message)
      store.dismissError()
    }
  },
)

watch(
  () => store.archiveError,
  (message) => {
    if (message) {
      ElMessage.error(message)
    }
  },
)

async function selectTask(id: string): Promise<void> {
  mode.value = 'task'
  if (id === store.mainTaskId) {
    return
  }
  pendingId.value = id
  try {
    await store.openMainTask(id)
  } finally {
    pendingId.value = null
  }
}

function startCreate(): void {
  mode.value = 'create'
  logOpen.value = false
  focusTaskId.value = null
}

async function confirmCreate(draft: PlanDraftView): Promise<void> {
  const ok = await store.startRun(draft.mockScript, draft.goal, draft)
  if (!ok) {
    return
  }
  mode.value = 'task'
  pendingId.value = null
  focusTaskId.value = null
  notify({
    title: '主任务已创建',
    message: `「${draft.title}」已按计划开始运行。`,
    type: 'success',
    duration: 4500,
  })
  void store.loadMainTasks()
}

function handleFocusHandled(): void {
  focusTaskId.value = null
}

onMounted(() => {
  void store.loadMainTasks()
  if (store.mainTaskId) {
    mode.value = 'task'
  }
})

onBeforeUnmount(() => {
  closeAllWaiting()
  store.closeConnection()
})
</script>

<template>
  <div class="app-shell">
    <AppHeader
      :total="entries.length"
      :active="activeCount"
      :archive-mode="store.archiveMode"
    />

    <div class="app-body">
      <TaskDirectory
        :tasks="entries"
        :selected-id="selectedId"
        :loading="store.libraryLoading"
        :error="store.libraryError"
        @select="selectTask"
        @create="startCreate"
        @reload="store.loadMainTasks()"
      />

      <main class="workspace">
        <CreateTaskPanel
          v-if="mode === 'create'"
          :starting="store.starting"
          @confirm="confirmCreate"
        />
        <TaskWorkspace
          v-else
          :entry="selectedEntry"
          :focus-task-id="focusTaskId"
          @open-log="logOpen = true"
          @focus-handled="handleFocusHandled"
        />
      </main>
    </div>

    <EventLogDialog
      v-model="logOpen"
      :title="logTitle"
      :main-task-id="store.mainTaskId"
    />
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  flex-direction: column;
  overflow: hidden;
}

.app-body {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: 302px minmax(0, 1fr);
}

.workspace {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.24) 0, rgba(255, 255, 255, 0) 180px),
    var(--paper);
}

@media (max-width: 1180px) {
  .app-body {
    grid-template-columns: 268px minmax(0, 1fr);
  }
}

@media (max-width: 820px) {
  .app-shell {
    height: auto;
    min-height: 100vh;
    overflow: visible;
  }

  .app-body {
    display: block;
  }

  .workspace {
    overflow: visible;
  }
}
</style>
