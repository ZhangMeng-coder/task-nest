<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { EVENT_TYPES, type EventType, type TaskNestEvent } from '@/api/events'
import { EVENT_TYPE_LABEL, formatClock, pad2 } from '@/ui/labels'
import { useTaskTreeStore } from '@/stores/taskTree'

const props = defineProps<{
  modelValue: boolean
  title: string
  mainTaskId: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const store = useTaskTreeStore()
const selectedTypes = ref<EventType[]>([])

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const defaultTypes = (): EventType[] => EVENT_TYPES.filter((type) => type !== 'heartbeat')

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      selectedTypes.value = defaultTypes()
    }
  },
)

watch(
  () => props.mainTaskId,
  () => {
    if (props.modelValue) {
      selectedTypes.value = defaultTypes()
    }
  },
)

const options = computed<EventType[]>(() => {
  const found = new Set<EventType>()
  for (const event of store.events) {
    found.add(event.type)
  }
  return EVENT_TYPES.filter((type) => found.has(type))
})

const filtered = computed(() => {
  const allowed = new Set(selectedTypes.value)
  return store.events.filter((event) => allowed.has(event.type))
})

function typeTag(type: EventType): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (type === 'task_completed' || type === 'run_completed') return 'success'
  if (type === 'task_failed' || type === 'error') return 'danger'
  if (type === 'waiting_input' || type === 'review_started') return 'warning'
  if (
    type === 'task_started'
    || type === 'plan'
    || type === 'run_started'
    || type === 'review_completed'
    || type === 'git_checkpoint'
  ) {
    return 'primary'
  }
  return 'info'
}

function messageOf(event: TaskNestEvent): string {
  switch (event.type) {
    case 'run_started':
      return `目标：${event.payload.goal}`
    case 'plan':
      return `计划 v${event.payload.revision ?? 1}：${event.payload.tasks.length} 个任务节点`
        + (event.payload.reason ? ` · ${event.payload.reason}` : '')
    case 'task_started':
      return `任务开始：${event.task?.title ?? '—'}`
    case 'task_thought':
      return `思考增量 ${event.payload.delta.length} 字`
    case 'prompt_assembled':
      return `提示词拼接完成，共 ${event.payload.finalPrompt.length} 字`
    case 'tool_call':
      return `调用工具 ${event.payload.toolName}`
    case 'tool_result':
      return `${event.payload.toolName} · ${event.payload.status} · ${event.payload.durationMs}ms`
    case 'waiting_input':
      return event.payload.question
    case 'input_received':
      return event.payload.customInput
        ? `自定义输入：${event.payload.customInput}`
        : `已提交选项 ${event.payload.selectedOptionId ?? '—'}`
    case 'task_completed':
      return event.payload.summary || '子任务完成'
    case 'task_failed':
      return `${event.payload.error}${event.payload.retryable ? '（可重试）' : '（不可重试）'}`
    case 'review_started':
      return `审核 Agent 开始第 ${event.payload.attempt} 次复核`
    case 'review_completed':
      return `审核 ${event.payload.verdict} · 测试 ${event.payload.testStatus}`
        + ` · ${event.payload.issues.length} 个问题 · ${event.payload.durationMs}ms`
    case 'git_checkpoint':
      return `${event.payload.action} · ${event.payload.branch}`
        + (event.payload.targetBranch ? ` → ${event.payload.targetBranch}` : '')
        + (event.payload.commit ? ` · ${event.payload.commit.slice(0, 8)}` : '')
        + (event.payload.noChanges ? ' · 无文件变化' : '')
    case 'run_completed':
      return event.payload.summary || '运行完成'
    case 'error':
      return `${event.payload.code}：${event.payload.message}`
    case 'heartbeat':
      return '心跳'
    default:
      return '—'
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    width="min(860px, 94vw)"
    append-to-body
    destroy-on-close
    class="log-dialog"
  >
    <template #header>
      <div class="log-head">
        <div>
          <p class="log-kicker mono">EVENT LEDGER · {{ mainTaskId ?? '—' }}</p>
          <h3>运行日志</h3>
        </div>
        <el-select
          v-model="selectedTypes"
          multiple
          collapse-tags
          collapse-tags-tooltip
          size="small"
          class="log-filter"
          placeholder="选择事件类型"
          aria-label="按事件类型过滤"
        >
          <el-option
            v-for="type in options"
            :key="type"
            :label="`${EVENT_TYPE_LABEL[type]} · ${type}`"
            :value="type"
          />
        </el-select>
      </div>
    </template>

    <div class="log-meta mono">
      <span>{{ title }}</span>
      <span>{{ filtered.length }} / {{ store.events.length }} 条</span>
      <span>seq 1–{{ store.lastSeq }}</span>
      <span>重复 {{ store.duplicates.length }} 条</span>
      <span>解析失败 {{ store.parseErrors.length }} 条</span>
    </div>

    <el-alert
      v-if="store.parseErrors.length"
      type="warning"
      :closable="false"
      show-icon
      class="parse-alert"
      title="有事件字段不合法，已跳过并保留原始片段"
      :description="store.parseErrors.map((item) => `${item.at} · ${item.reason}`).join('；')"
    />

    <el-scrollbar max-height="56vh">
      <div v-if="filtered.length" class="log-list">
        <div v-for="item in filtered" :key="item.eventId" class="log-row">
          <span class="log-seq mono">#{{ pad2(item.seq) }}</span>
          <el-tag size="small" effect="plain" :type="typeTag(item.type)">
            {{ EVENT_TYPE_LABEL[item.type] }}
          </el-tag>
          <span class="log-time mono">{{ formatClock(item.timestamp) }}</span>
          <span class="log-task mono">{{ item.task?.title ?? 'run' }}</span>
          <span class="log-message">{{ messageOf(item) }}</span>
        </div>
      </div>
      <el-empty v-else description="没有匹配的事件" :image-size="72" />
    </el-scrollbar>

    <template #footer>
      <span class="log-foot">事件按 seq 排序；默认隐藏 heartbeat，重复行保留用于核对去重。</span>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.log-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}

.log-kicker {
  color: var(--ledger-dim);
  font-size: 9.5px;
  letter-spacing: 0.08em;
}

.log-head h3 {
  margin-top: 4px;
  color: #f0f1e9;
  font-size: 19px;
}

.log-filter {
  width: 280px;
}

.log-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
  color: var(--ink-faint);
  font-size: 10px;
}

.parse-alert {
  margin-top: 10px;
}

.log-list {
  padding-top: 4px;
}

.log-row {
  display: grid;
  grid-template-columns: 46px 104px 82px 130px minmax(0, 1fr);
  align-items: start;
  gap: 10px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
  line-height: 1.55;
}

.log-seq {
  color: var(--ink-faint);
  font-size: 10.5px;
}

.log-time,
.log-task {
  overflow: hidden;
  color: var(--ink-faint);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-message {
  overflow-wrap: anywhere;
  color: var(--ink-soft);
}

.log-foot {
  margin-right: auto;
  color: var(--ink-faint);
  font-size: 10.5px;
}

:deep(.el-dialog__footer) {
  display: flex;
  align-items: center;
}

@media (max-width: 760px) {
  .log-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
  }

  .log-filter {
    width: 100%;
  }

  .log-row {
    grid-template-columns: 40px 92px 74px minmax(0, 1fr);
  }

  .log-task {
    display: none;
  }
}
</style>
