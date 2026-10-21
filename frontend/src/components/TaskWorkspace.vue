<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Document, FolderOpened, RefreshLeft } from '@element-plus/icons-vue'
import { useTaskTreeStore } from '@/stores/taskTree'
import {
  CONNECTION_LABEL,
  formatDateTime,
  formatDuration,
  MAIN_TASK_STATUS_LABEL,
  mainTagType,
  scriptName,
  subtaskTagType,
  SUBTASK_STATUS_LABEL,
} from '@/ui/labels'
import type { DirectoryTask } from '@/ui/directory'
import PlanSheet from './PlanSheet.vue'
import SubtaskDetail from './SubtaskDetail.vue'

const props = defineProps<{
  entry: DirectoryTask | null
  focusTaskId: string | null
}>()

const emit = defineEmits<{
  openLog: []
  focusHandled: []
}>()

const store = useTaskTreeStore()

const activeTab = ref('subtasks')
const expanded = ref<string[]>([])

const main = computed(() => store.mainNode)
const title = computed(() => main.value?.title ?? props.entry?.title ?? '主任务')
const goal = computed(() => store.goal ?? props.entry?.goal ?? '')
const workingDir = computed(() => store.workingDir ?? props.entry?.workingDir ?? null)
const status = computed(() => main.value?.status ?? props.entry?.status ?? 'PENDING')
const total = computed(() => store.subtaskCount || props.entry?.subtaskCount || 0)
const completed = computed(() => store.successCount || props.entry?.completedCount || 0)
const planVersion = computed(() => (
  store.mainNode?.planRevision ?? store.plan?.version ?? props.entry?.planVersion ?? 0
))
const replanned = computed(() => (
  (store.mainNode?.planRevision ?? 0) > 1
    || (store.mainNode?.planChangedTaskIds.length ?? 0) > 0
))
const percentage = computed(() => (
  total.value === 0 ? 0 : Math.round((completed.value / total.value) * 100)
))
const waitingSubtask = computed(() => (
  store.subtaskList.find((node) => node.status === 'WAITING_INPUT') ?? null
))
const connectionLabel = computed(() => (
  store.archiveMode ? '只读档案' : CONNECTION_LABEL[store.connection]
))

watch(
  () => store.mainTaskId,
  () => {
    activeTab.value = 'subtasks'
    expanded.value = []
  },
)

watch(
  () => props.focusTaskId,
  (taskId) => {
    if (!taskId) {
      return
    }
    const node = store.subtaskNodes[taskId]
    if (!node) {
      return
    }
    activeTab.value = 'subtasks'
    if (!expanded.value.includes(taskId)) {
      expanded.value = [...expanded.value, taskId]
    }
    store.selectTask(taskId)
    emit('focusHandled')
  },
  { immediate: true },
)

watch(
  () => waitingSubtask.value?.taskId,
  (taskId) => {
    if (taskId && !store.archiveMode && !expanded.value.includes(taskId)) {
      expanded.value = [...expanded.value, taskId]
    }
  },
)

function durationOf(taskId: string): string {
  const node = store.subtaskNodes[taskId]
  if (!node?.startedAt) {
    return '—'
  }
  const start = new Date(node.startedAt).getTime()
  const end = node.completedAt ? new Date(node.completedAt).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return '—'
  }
  return formatDuration(Math.max(0, end - start))
}

function dependencyLabel(taskId: string): string {
  const node = store.subtaskNodes[taskId]
  if (!node) {
    return '—'
  }
  if (!node.dependsOn.length) {
    return node.order === 0 ? '无依赖' : `依赖第 ${node.order} 步`
  }
  const indexes = node.dependsOn
    .map((id) => store.subtaskList.findIndex((item) => item.taskId === id))
    .filter((index) => index >= 0)
  return indexes.length
    ? `依赖第 ${indexes.map((index) => index + 1).join('、')} 步`
    : '无依赖'
}

function decide(
  taskId: string,
  optionId: string | null,
  customInput: string | null,
): void {
  void store.submitDecision(taskId, optionId, customInput)
}

function leaveArchive(): void {
  store.backToLive()
}
</script>

<template>
  <section v-if="store.archiveLoading" class="workspace-state">
    <el-skeleton :rows="8" animated />
  </section>

  <section v-else-if="!main" class="workspace-state is-empty">
    <el-empty description="从左侧目录选择一个主任务，或新建一个">
      <p class="empty-note">
        新建主任务会先生成 Markdown 计划表，确认后写入 MySQL 并开始运行。
      </p>
    </el-empty>
  </section>

  <section v-else class="task-panel">
    <el-alert
      v-if="store.archiveMode"
      type="info"
      :closable="false"
      show-icon
      class="archive-banner"
    >
      <template #title>
        <span class="archive-title">
          只读档案 · <span class="mono">{{ store.mainTaskId }}</span>
        </span>
      </template>
      <div class="archive-body">
        <span>主任务、子任务、提示词与中间结果来自 MySQL，不建立 SSE，也不能提交抉择。</span>
        <el-button size="small" :icon="RefreshLeft" @click="leaveArchive">退出档案</el-button>
      </div>
    </el-alert>

    <header class="task-head">
      <div class="task-identity">
        <p class="eyebrow mono">MAIN TASK · {{ store.mainTaskId ?? '—' }}</p>
        <div class="task-title-row">
          <h1>{{ title }}</h1>
          <el-tag :type="mainTagType(status)" effect="plain">
            {{ MAIN_TASK_STATUS_LABEL[status] }}
          </el-tag>
          <el-tag v-if="!store.archiveMode" size="small" effect="plain" type="info">
            {{ connectionLabel }}
          </el-tag>
        </div>
        <p v-if="goal" class="task-goal">{{ goal }}</p>
        <p class="task-workdir" :class="{ 'is-empty': !workingDir }">
          <el-icon><FolderOpened /></el-icon>
          <span class="mono" :title="workingDir ?? undefined">
            {{ workingDir ?? '未指定工作目录' }}
          </span>
        </p>
      </div>
      <div class="task-actions">
        <el-button :icon="Document" @click="emit('openLog')">查询日志</el-button>
      </div>
    </header>

    <el-alert
      v-if="status === 'WAITING_INPUT' && waitingSubtask"
      type="warning"
      :closable="false"
      show-icon
      class="wait-banner"
      title="有子任务在等待你的抉择"
      :description="`${waitingSubtask.title}：${waitingSubtask.waiting?.question ?? ''}`"
    />

    <el-alert
      v-if="status === 'FAILED'"
      type="error"
      :closable="false"
      show-icon
      class="wait-banner"
      title="运行已终止"
      :description="main.error ?? store.runSummary ?? '主任务执行失败。'"
    />

    <el-alert
      v-if="replanned"
      type="info"
      :closable="false"
      show-icon
      class="wait-banner"
      :title="`计划已动态重排至 v${planVersion}`"
      :description="[
        store.mainNode?.planReason ?? '主 Agent 根据执行结果调整了剩余子任务。',
        store.mainNode?.planChangedTaskIds.length
          ? `涉及 ${store.mainNode.planChangedTaskIds.length} 个任务；已开始的子任务不受影响。`
          : '',
      ].filter(Boolean).join(' ')"
    />

    <div class="task-strip">
      <div class="strip-cell">
        <span class="strip-label mono">SCRIPT</span>
        <span class="strip-value mono">{{ scriptName(store.mockScript ?? entry?.mockScript ?? null) }}</span>
      </div>
      <div class="strip-cell">
        <span class="strip-label mono">CREATED</span>
        <span class="strip-value mono">{{ formatDateTime(entry?.createdAt ?? null) }}</span>
      </div>
      <div class="strip-cell">
        <span class="strip-label mono">UPDATED</span>
        <span class="strip-value mono">{{ formatDateTime(entry?.updatedAt ?? null) }}</span>
      </div>
      <div class="strip-cell">
        <span class="strip-label mono">PLAN</span>
        <span class="strip-value">v{{ planVersion }} · {{ store.plan?.rows.length ?? total }} 步</span>
      </div>
      <div class="strip-progress">
        <div class="strip-progress-head">
          <span class="strip-label mono">PROGRESS</span>
          <span class="mono">{{ completed }}/{{ total }} · {{ percentage }}%</span>
        </div>
        <el-progress :percentage="percentage" :show-text="false" :stroke-width="6" />
      </div>
    </div>

    <el-tabs v-model="activeTab" class="workspace-tabs">
      <el-tab-pane name="subtasks">
        <template #label>
          <span class="tab-label">
            子任务
            <span class="tab-count mono">{{ total }}</span>
          </span>
        </template>

        <div class="subtask-toolbar">
          <p>按计划顺序执行。展开任意子任务，查看思考、提示词、工具调用与结论。</p>
          <el-tag v-if="status === 'WAITING_INPUT'" type="warning" effect="plain" size="small">
            等待你的抉择
          </el-tag>
        </div>

        <el-collapse v-if="store.subtaskList.length" v-model="expanded" class="subtask-collapse">
          <el-collapse-item
            v-for="(node, index) in store.subtaskList"
            :key="node.taskId"
            :name="node.taskId"
          >
            <template #title>
              <div class="subtask-head">
                <span
                  class="subtask-order mono"
                  :class="`is-${node.status.toLowerCase()}`"
                >
                  {{ String(index + 1).padStart(2, '0') }}
                </span>
                <span class="subtask-head-copy">
                  <span class="subtask-title">{{ node.title }}</span>
                  <span class="subtask-meta mono">
                    {{ node.agentName }} · {{ dependencyLabel(node.taskId) }} · {{ durationOf(node.taskId) }}
                  </span>
                </span>
                <el-tag size="small" effect="plain" :type="subtaskTagType(node.status)">
                  {{ SUBTASK_STATUS_LABEL[node.status] }}
                </el-tag>
              </div>
            </template>

            <SubtaskDetail
              :node="node"
              :index="index"
              :siblings="store.subtaskList"
              :archive-mode="store.archiveMode"
              :submitting="store.decisionSubmitting"
              :decision-error="node.status === 'WAITING_INPUT' ? store.decisionError : null"
              @decide="(optionId, customInput) => decide(node.taskId, optionId, customInput)"
            />
          </el-collapse-item>
        </el-collapse>

        <el-empty
          v-else
          description="规划事件尚未到达，子任务还没有建立"
          :image-size="72"
        />
      </el-tab-pane>

      <el-tab-pane name="plan">
        <template #label>
          <span class="tab-label">
            计划表
            <span class="tab-count mono">v{{ planVersion }}</span>
          </span>
        </template>

        <PlanSheet :archive-mode="store.archiveMode" />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style scoped>
.eyebrow {
  color: var(--signal-deep);
  font-size: 9.5px;
  letter-spacing: 0.11em;
}

.workspace-state {
  padding: 28px 30px;
}

.workspace-state.is-empty {
  display: grid;
  min-height: 62vh;
  place-items: center;
}

.empty-note {
  color: var(--ink-faint);
  font-size: 11.5px;
}

.task-panel {
  padding: 25px 30px 40px;
}

.archive-banner {
  margin-bottom: 16px;
}

.archive-title {
  font-weight: 600;
}

.archive-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  font-size: 11.5px;
  line-height: 1.55;
}

.task-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 17px;
  border-bottom: 1px solid var(--line);
}

.task-identity {
  min-width: 0;
}

.task-title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.task-title-row h1 {
  font-size: 28px;
  letter-spacing: -0.015em;
  line-height: 1.2;
}

.task-goal {
  max-width: 760px;
  margin-top: 7px;
  color: var(--ink-soft);
  font-size: 12.5px;
  line-height: 1.65;
}

.task-workdir {
  display: flex;
  max-width: 760px;
  align-items: center;
  gap: 6px;
  margin-top: 7px;
  overflow: hidden;
  color: var(--ink-soft);
  font-size: 10.5px;
  line-height: 1.5;
}

.task-workdir .el-icon {
  flex: 0 0 auto;
  color: var(--signal-deep);
}

.task-workdir span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-workdir.is-empty {
  color: var(--ink-faint);
}

.task-workdir.is-empty .el-icon {
  color: var(--ink-faint);
}

.task-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  gap: 8px;
}

.wait-banner {
  margin-top: 14px;
}

.task-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(92px, auto)) minmax(180px, 1fr);
  align-items: center;
  gap: 0;
  padding: 13px 0;
  border-bottom: 1px solid var(--line);
}

.strip-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
  padding: 0 16px;
  border-right: 1px solid var(--line);
}

.strip-cell:first-child {
  padding-left: 0;
}

.strip-label {
  color: var(--ink-faint);
  font-size: 8px;
  letter-spacing: 0.11em;
}

.strip-value {
  overflow: hidden;
  color: var(--ink-soft);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.strip-progress {
  min-width: 0;
  padding-left: 18px;
}

.strip-progress-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 5px;
  color: var(--ink-faint);
  font-size: 9.5px;
}

.strip-progress :deep(.el-progress-bar__outer),
.strip-progress :deep(.el-progress-bar__inner) {
  border-radius: 0;
}

.strip-progress :deep(.el-progress-bar__outer) {
  background: var(--paper-sunk);
}

.workspace-tabs {
  margin-top: 8px;
}

.workspace-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}

.workspace-tabs :deep(.el-tabs__item) {
  padding: 0 16px;
  font-size: 12.5px;
}

.workspace-tabs :deep(.el-tabs__item:first-child) {
  padding-left: 0;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tab-count {
  padding: 0 4px;
  border: 1px solid var(--line);
  color: var(--ink-faint);
  font-size: 8.5px;
  line-height: 1.5;
}

.subtask-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.subtask-toolbar p {
  color: var(--ink-faint);
  font-size: 11px;
  line-height: 1.6;
}

.subtask-collapse {
  border-top: 0;
  border-bottom: 0;
}

.subtask-collapse :deep(.el-collapse-item) {
  margin-bottom: 8px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.subtask-collapse :deep(.el-collapse-item__header) {
  height: auto;
  min-height: 54px;
  padding: 0 14px;
  border-bottom-color: var(--line);
  background: transparent;
}

.subtask-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: 0;
  background: transparent;
}

.subtask-collapse :deep(.el-collapse-item__content) {
  padding: 0 16px 16px;
}

.subtask-head {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  gap: 11px;
  padding: 9px 0;
}

.subtask-order {
  display: grid;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--line-strong);
  background: var(--paper-sunk);
  color: var(--ink-faint);
  font-size: 9px;
}

.subtask-order.is-running {
  border-color: var(--signal);
  background: rgba(238, 90, 42, 0.09);
  color: var(--signal-deep);
}

.subtask-order.is-success {
  border-color: rgba(45, 106, 74, 0.45);
  background: rgba(45, 106, 74, 0.06);
  color: var(--success);
}

.subtask-order.is-failed {
  border-color: rgba(169, 58, 43, 0.45);
  background: rgba(169, 58, 43, 0.06);
  color: var(--danger);
}

.subtask-order.is-waiting_input,
.subtask-order.is-retrying {
  border-color: rgba(149, 104, 26, 0.45);
  background: rgba(149, 104, 26, 0.07);
  color: var(--retry);
}

.subtask-head-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.subtask-title {
  overflow: hidden;
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.subtask-meta {
  overflow: hidden;
  color: var(--ink-faint);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1180px) {
  .task-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    row-gap: 13px;
  }

  .strip-cell {
    padding-left: 0;
    border-right: 0;
  }

  .strip-progress {
    grid-column: 1 / -1;
    padding-left: 0;
  }
}

@media (max-width: 820px) {
  .task-panel {
    padding: 20px 16px 32px;
  }

  .task-head {
    flex-direction: column;
    gap: 14px;
  }

  .task-title-row h1 {
    font-size: 23px;
  }

  .archive-body {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
