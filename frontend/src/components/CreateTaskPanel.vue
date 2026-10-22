<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CircleCheck,
  CircleClose,
  FolderOpened,
  MagicStick,
  Promotion,
  RefreshLeft,
  VideoPlay,
} from '@element-plus/icons-vue'
import { useTaskTreeStore } from '@/stores/taskTree'
import type { TaskNestEvent } from '@/api/events'
import {
  EVENT_TYPE_LABEL,
  PLAN_SESSION_STATUS_LABEL,
} from '@/ui/labels'
import DirectoryPickerDialog from './DirectoryPickerDialog.vue'

const emit = defineEmits<{
  started: []
}>()

const store = useTaskTreeStore()

const goal = ref('')
const workingDir = ref('')
const pickerOpen = ref(false)
const instruction = ref('')
const selectedOption = ref('')
const customInput = ref('')

const examples = [
  '为现有 Vue 工作台增加一个可筛选的任务事件账本，并补上单元测试',
  '排查后端任务偶发卡在 RUNNING 的问题，修复后跑通全部测试',
  '把本地目录选择器改为支持键盘导航和最近目录',
]

const planRoot = computed(() => (
  store.planningPlan?.tasks.find((task) => task.parentTaskId === null) ?? null
))

const planRows = computed(() => {
  const root = planRoot.value
  if (!root) {
    return []
  }
  return store.planningPlan?.tasks
    .filter((task) => task.parentTaskId === root.taskId)
    .map((task, index) => ({ ...task, index: index + 1 })) ?? []
})

const planningLog = computed(() => store.planningEvents
  .filter((event) => event.type !== 'heartbeat')
  .slice(-160)
  .map((event) => ({
    key: `${event.seq}-${event.eventId}`,
    seq: event.seq,
    label: EVENT_TYPE_LABEL[event.type],
    text: describeEvent(event),
  })))

const canConfirm = computed(() => (
  store.planningStatus === 'READY' && Boolean(store.planningPlan)
))

const canSubmitDecision = computed(() => (
  Boolean(selectedOption.value) || customInput.value.trim().length > 0
))

watch(
  () => store.planningWaiting?.question,
  () => {
    selectedOption.value = ''
    customInput.value = ''
  },
)

function describeEvent(event: TaskNestEvent): string {
  switch (event.type) {
    case 'run_started':
      return event.payload.goal
    case 'plan':
      return `计划版本 v${event.payload.revision ?? 1} · ${event.payload.tasks.length} 个节点`
    case 'task_thought':
      return event.payload.delta.trim() || '…'
    case 'prompt_assembled':
      return event.payload.userPrompt
    case 'tool_call':
      return `${event.payload.toolName} ${JSON.stringify(event.payload.arguments).slice(0, 240)}`
    case 'tool_result':
      return `${event.payload.toolName} · ${event.payload.status === 'success' ? '成功' : '失败'}`
    case 'waiting_input':
      return event.payload.question
    case 'input_received':
      return event.payload.customInput ?? event.payload.selectedOptionId ?? '已提交'
    case 'task_failed':
    case 'error':
      return 'error' in event.payload ? event.payload.error : event.payload.message
    case 'run_completed':
      return event.payload.summary
    default:
      return ''
  }
}

async function beginPlanning(): Promise<void> {
  const goalText = goal.value.trim()
  if (!goalText) {
    ElMessage.warning('先用一段话描述这次要达成的目标。')
    return
  }
  if (!workingDir.value.trim()) {
    ElMessage.warning('规划与执行都需要一个 Git 仓库根目录作为工作目录。')
    return
  }
  await store.startPlanning(goalText, workingDir.value.trim())
}

async function applyRevision(): Promise<void> {
  const text = instruction.value.trim()
  if (!text) {
    ElMessage.warning('先输入一条修改提示。')
    return
  }
  const ok = await store.revisePlanning(text)
  if (ok) {
    instruction.value = ''
    ElMessage.success('已提交修订，规划 Agent 正在重新生成计划。')
  }
}

async function submitDecision(): Promise<void> {
  const taskId = store.planningWaitingTaskId
  if (!taskId || !canSubmitDecision.value) {
    return
  }
  await store.submitPlanningDecision(
    taskId,
    selectedOption.value || null,
    customInput.value.trim() || null,
  )
}

async function confirmPlan(): Promise<void> {
  const ok = await store.confirmPlanning()
  if (ok) {
    emit('started')
  }
}

async function cancelPlanning(): Promise<void> {
  const ok = await store.cancelPlanning()
  if (ok) {
    ElMessage.info('规划会话已取消，工作区锁已释放。')
  }
}

function restartPlanning(): void {
  store.clearPlanningState()
  instruction.value = ''
  selectedOption.value = ''
  customInput.value = ''
}
</script>

<template>
  <section class="create-panel">
    <header class="create-head">
      <p class="eyebrow mono">CREATE MAIN TASK</p>
      <h1>{{ store.planSessionId ? '规划 Agent 正在拆解目标' : '描述目标，让 Agent 先规划' }}</h1>
      <p class="create-lead">
        主 Agent 会读取仓库、拆出两步以内的执行计划。确认计划后才会创建主任务、开分支并开始执行；
        遇到歧义会暂停，等你在现场选择或补充说明。
      </p>
      <el-steps
        :active="store.planSessionId ? (canConfirm ? 1 : 1) : 0"
        finish-status="success"
        align-center
        class="create-steps"
      >
        <el-step title="描述目标" description="选择 Git 工作目录" />
        <el-step title="规划与澄清" description="Agent 读仓库、问问题" />
        <el-step title="确认并执行" description="创建主任务与分支" />
      </el-steps>
    </header>

    <el-alert
      v-if="store.planningError"
      :title="store.planningError"
      type="error"
      show-icon
      closable
      class="create-error"
      @close="store.planningError = null"
    />

    <div v-if="!store.planSessionId" class="create-form">
      <div class="field-row">
        <div class="field-block">
          <span class="field-label mono">GIT WORKSPACE</span>
          <div class="workdir-picker">
            <el-input
              v-model="workingDir"
              readonly
              placeholder="选择 Git 仓库根目录（必填）"
              aria-label="工作目录"
            >
              <template #prefix>
                <el-icon><FolderOpened /></el-icon>
              </template>
            </el-input>
            <el-button :icon="FolderOpened" @click="pickerOpen = true">选择目录</el-button>
            <el-button v-if="workingDir" text @click="workingDir = ''">清除</el-button>
          </div>
          <span class="field-hint">
            必须是 Git 仓库根目录且工作区干净；规划与执行期间该工作区会被锁定。
          </span>
        </div>
      </div>

      <el-input
        v-model="goal"
        type="textarea"
        :rows="8"
        resize="none"
        maxlength="2000"
        show-word-limit
        placeholder="例如：为现有 Vue 工作台增加可筛选的事件账本，补齐后端事件契约与测试，最后跑通构建和 lint。"
        class="goal-textarea"
        @keydown.ctrl.enter.prevent="beginPlanning"
        @keydown.meta.enter.prevent="beginPlanning"
      />
      <div class="example-row">
        <span class="example-label">试试：</span>
        <button
          v-for="example in examples"
          :key="example"
          type="button"
          class="example-chip"
          @click="goal = example"
        >
          {{ example }}
        </button>
      </div>
      <div class="create-actions">
        <span class="create-hint mono">Ctrl / ⌘ + Enter 开始规划</span>
        <el-button
          type="primary"
          size="large"
          :icon="MagicStick"
          :loading="store.planningStarting"
          :disabled="store.planningStarting"
          @click="beginPlanning"
        >
          {{ store.planningStarting ? '正在创建规划会话…' : '开始规划' }}
        </el-button>
      </div>
    </div>

    <div v-else class="planning-stage">
      <div class="planning-grid">
        <div class="planning-main">
          <div class="planning-status-bar">
            <div class="status-copy">
              <span class="field-label mono">PLAN SESSION</span>
              <span class="status-line">
                <el-tag
                  :type="store.planningStatus === 'FAILED'
                    ? 'danger'
                    : store.planningStatus === 'READY' ? 'success' : 'warning'"
                  effect="plain"
                >
                  {{ store.planningStatus
                    ? PLAN_SESSION_STATUS_LABEL[store.planningStatus]
                    : '连接中' }}
                </el-tag>
                <span class="mono session-id">{{ store.planSessionId }}</span>
              </span>
            </div>
            <div class="status-meta mono">
              <span>{{ workingDir }}</span>
              <span>seq {{ store.planningLastSeq }}</span>
              <span>v{{ store.planningPlan?.revision ?? 1 }}</span>
            </div>
          </div>

          <el-card v-if="store.planningPlan" shadow="never" class="plan-card">
            <template #header>
              <div class="card-head">
                <div>
                  <span class="card-title">{{ planRoot?.title ?? '执行计划' }}</span>
                  <span class="card-sub mono">
                    {{ planRows.length }} STEPS
                    <template v-if="store.planningPlan.reason"> · {{ store.planningPlan.reason }}</template>
                  </span>
                </div>
                <el-tag type="success" effect="plain">待确认</el-tag>
              </div>
            </template>
            <el-table :data="planRows" size="small" class="plan-table">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="plan-expand">
                    <p v-if="row.description">{{ row.description }}</p>
                    <ul v-if="row.acceptanceCriteria?.length">
                      <li v-for="item in row.acceptanceCriteria" :key="item">{{ item }}</li>
                    </ul>
                    <p v-if="row.allowedTools?.length" class="mono">
                      tools: {{ row.allowedTools.join(', ') }}
                    </p>
                    <p v-if="row.testCommand" class="mono">test: {{ row.testCommand }}</p>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="#" width="52">
                <template #default="{ row }">
                  <span class="mono">{{ String(row.index).padStart(2, '0') }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="title" label="子任务" min-width="180" />
              <el-table-column prop="agentName" label="Agent" width="120" />
              <el-table-column label="依赖" width="130">
                <template #default="{ row }">
                  <span class="mono plan-cell">{{ row.dependsOn.join('、') || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="验收" min-width="180">
                <template #default="{ row }">
                  <span class="plan-cell">
                    {{ row.acceptanceCriteria?.[0] ?? row.description ?? '—' }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-card v-else shadow="never" class="plan-card plan-card-empty">
            <el-empty
              :description="store.planningStatus === 'WAITING_INPUT'
                ? '规划 Agent 提出了一个问题，请先在右侧处理。'
                : '规划 Agent 正在读取仓库并生成计划…'"
              :image-size="72"
            />
          </el-card>

          <div class="planning-log">
            <div class="log-head">
              <span class="field-label mono">PLANNING LOG</span>
              <span class="mono">{{ planningLog.length }} 条</span>
            </div>
            <div v-if="planningLog.length" class="log-body">
              <div v-for="entry in planningLog" :key="entry.key" class="log-row">
                <span class="log-seq mono">{{ entry.seq }}</span>
                <span class="log-type">{{ entry.label }}</span>
                <span class="log-text">{{ entry.text }}</span>
              </div>
            </div>
            <p v-else class="log-empty">等待规划事件…</p>
          </div>
        </div>

        <aside class="planning-rail">
          <div v-if="store.planningWaiting" class="decision-box">
            <div class="decision-head">
              <div>
                <p class="field-label mono">DECISION REQUIRED</p>
                <h3>{{ store.planningWaiting.question }}</h3>
              </div>
              <el-tag type="warning" effect="dark" size="small">等待抉择</el-tag>
            </div>
            <el-radio-group v-model="selectedOption" class="decision-group">
              <el-radio
                v-for="option in store.planningWaiting.options"
                :key="option.id"
                :value="option.id"
                border
                class="decision-radio"
              >
                <span class="decision-label">{{ option.label }}</span>
                <span class="decision-desc">{{ option.description }}</span>
              </el-radio>
            </el-radio-group>
            <el-input
              v-if="store.planningWaiting.allowCustom"
              v-model="customInput"
              type="textarea"
              :rows="3"
              resize="none"
              :placeholder="store.planningWaiting.customPlaceholder ?? '也可以直接输入你的要求'"
              class="decision-custom"
            />
            <el-button
              type="primary"
              class="decision-submit"
              :loading="store.planningDecisionSubmitting"
              :disabled="!canSubmitDecision || store.planningDecisionSubmitting"
              @click="submitDecision"
            >
              提交并继续
            </el-button>
          </div>

          <div v-if="canConfirm" class="revise-block">
            <p class="field-label mono">REVISE PLAN</p>
            <h3>用提示词修改计划</h3>
            <el-input
              v-model="instruction"
              type="textarea"
              :rows="4"
              resize="none"
              maxlength="500"
              placeholder="例如：增加一步回归测试；或：把文档更新拆成独立子任务"
              @keydown.ctrl.enter.prevent="applyRevision"
              @keydown.meta.enter.prevent="applyRevision"
            />
            <el-button
              class="revise-button"
              :icon="Promotion"
              :loading="store.planningRevising"
              :disabled="store.planningRevising"
              @click="applyRevision"
            >
              提交修订
            </el-button>
          </div>

          <div class="rail-actions">
            <template v-if="canConfirm">
              <el-button
                type="primary"
                size="large"
                :icon="CircleCheck"
                :loading="store.planningConfirming"
                :disabled="store.planningConfirming"
                @click="confirmPlan"
              >
                {{ store.planningConfirming ? '正在创建主任务…' : '确认计划并开始执行' }}
              </el-button>
            </template>
            <template v-else-if="store.planningTerminal">
              <el-button
                :icon="RefreshLeft"
                @click="restartPlanning"
              >
                重新开始规划
              </el-button>
            </template>
            <el-button
              v-if="!store.planningTerminal"
              :icon="CircleClose"
              :loading="store.planningCancelling"
              :disabled="store.planningCancelling || store.planningConfirming"
              @click="cancelPlanning"
            >
              取消规划
            </el-button>
          </div>

          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="确认后才会创建主任务"
            description="规划阶段持有工作区锁；取消或确认后释放。执行阶段会创建主任务分支与每次尝试的子任务分支。"
          />
        </aside>
      </div>

      <div v-if="canConfirm" class="plan-actions">
        <span class="mono plan-foot-note">
          {{ planRows.length }} 个子任务 · 计划 v{{ store.planningPlan?.revision ?? 1 }}
        </span>
        <el-button
          type="primary"
          size="large"
          :icon="VideoPlay"
          :loading="store.planningConfirming"
          :disabled="store.planningConfirming"
          @click="confirmPlan"
        >
          确认计划并开始执行
        </el-button>
      </div>
    </div>

    <DirectoryPickerDialog
      v-model="pickerOpen"
      :initial-path="workingDir || null"
      @select="workingDir = $event"
    />
  </section>
</template>

<style scoped>
.create-panel {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  padding: 28px 32px 32px;
}

.eyebrow {
  color: var(--signal-deep);
  font-size: 10px;
  letter-spacing: 0.11em;
}

.create-head {
  max-width: 920px;
}

.create-head h1 {
  margin-top: 6px;
  font-size: 28px;
  letter-spacing: -0.01em;
}

.create-lead {
  max-width: 720px;
  margin-top: 8px;
  color: var(--ink-soft);
  font-size: 13px;
  line-height: 1.7;
}

.create-steps {
  margin: 24px 0 26px;
  padding: 16px 14px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.create-error {
  max-width: 920px;
  margin-bottom: 16px;
}

.create-form {
  max-width: 920px;
}

.field-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 14px;
}

.field-block {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.field-label {
  color: var(--ink-faint);
  font-size: 9px;
  letter-spacing: 0.1em;
}

.field-hint {
  color: var(--ink-faint);
  font-size: 10.5px;
  line-height: 1.5;
}

.workdir-picker {
  display: flex;
  align-items: center;
  gap: 8px;
}

.workdir-picker :deep(.el-input) {
  min-width: 0;
  flex: 1;
}

.workdir-picker :deep(.el-input__inner) {
  font-family: var(--font-mono);
  font-size: 10.5px;
}

.goal-textarea :deep(.el-textarea__inner) {
  padding: 18px 20px;
  font-size: 15px;
  line-height: 1.8;
}

.example-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  margin-top: 12px;
}

.example-label {
  color: var(--ink-faint);
  font-size: 11px;
}

.example-chip {
  padding: 4px 9px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
  color: var(--ink-soft);
  font-size: 11px;
  line-height: 1.5;
  transition: border-color 120ms ease, color 120ms ease;
}

.example-chip:hover {
  border-color: var(--signal);
  color: var(--signal-deep);
}

.create-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 22px;
}

.create-hint {
  color: var(--ink-faint);
  font-size: 10px;
}

.planning-stage {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 16px;
}

.planning-grid {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: 16px;
}

.planning-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 14px;
}

.planning-status-bar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.status-copy {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.status-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.session-id {
  color: var(--ink-faint);
  font-size: 9.5px;
}

.status-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px 14px;
  color: var(--ink-faint);
  font-size: 9.5px;
}

.plan-card {
  max-height: 46vh;
  overflow: auto;
}

.plan-card-empty :deep(.el-card__body) {
  padding: 8px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-title {
  display: block;
  color: var(--ink);
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 600;
}

.card-sub {
  display: block;
  margin-top: 2px;
  color: var(--ink-faint);
  font-size: 9px;
  letter-spacing: 0.04em;
}

.plan-table :deep(th.el-table__cell) {
  background: var(--paper-sunk);
  color: var(--ink-faint);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 500;
}

.plan-table :deep(td.el-table__cell) {
  color: var(--ink-soft);
  font-size: 11px;
}

.plan-cell {
  color: var(--ink-soft);
  font-size: 10.5px;
}

.plan-expand {
  padding: 4px 14px 8px;
  color: var(--ink-soft);
  font-size: 11px;
  line-height: 1.65;
}

.plan-expand ul {
  margin: 6px 0 0;
  padding-left: 18px;
}

.plan-expand .mono {
  margin-top: 6px;
  color: var(--ink-faint);
  font-size: 10px;
}

.planning-log {
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.log-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 12px;
  border-bottom: 1px solid var(--line);
  color: var(--ink-faint);
  font-size: 9.5px;
}

.log-body {
  max-height: 220px;
  overflow: auto;
}

.log-row {
  display: grid;
  grid-template-columns: 44px 88px minmax(0, 1fr);
  gap: 8px;
  padding: 5px 12px;
  border-bottom: 1px solid var(--line);
  font-size: 10.5px;
  line-height: 1.55;
}

.log-row:last-child {
  border-bottom: none;
}

.log-seq {
  color: var(--ink-faint);
  font-size: 9px;
}

.log-type {
  color: var(--signal-deep);
  font-size: 9.5px;
}

.log-text {
  color: var(--ink-soft);
  word-break: break-word;
  white-space: pre-wrap;
}

.log-empty {
  padding: 14px 12px;
  color: var(--ink-faint);
  font-size: 10.5px;
}

.planning-rail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.decision-box {
  padding: 14px;
  border: 1px solid rgba(149, 104, 26, 0.45);
  background: rgba(246, 239, 223, 0.72);
}

.decision-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.decision-head h3 {
  margin-top: 4px;
  font-size: 14px;
  line-height: 1.45;
}

.decision-group {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 7px;
  margin-top: 12px;
}

.decision-radio {
  width: 100%;
  height: auto;
  margin-right: 0;
  padding: 9px 12px;
  align-items: flex-start;
}

.decision-radio :deep(.el-radio__input) {
  margin-top: 2px;
}

.decision-radio :deep(.el-radio__label) {
  display: flex;
  flex-direction: column;
  gap: 2px;
  white-space: normal;
}

.decision-label {
  color: var(--ink);
  font-family: var(--font-display);
  font-size: 12.5px;
  font-weight: 600;
}

.decision-desc {
  color: var(--ink-soft);
  font-size: 11px;
  line-height: 1.5;
}

.decision-custom {
  margin-top: 10px;
}

.decision-submit {
  width: 100%;
  margin-top: 10px;
}

.revise-block {
  padding: 14px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.revise-block h3 {
  margin: 5px 0 9px;
  font-size: 14px;
}

.revise-button {
  width: 100%;
  margin-top: 9px;
}

.rail-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rail-actions :deep(.el-button) {
  width: 100%;
  margin-left: 0;
}

.plan-actions {
  position: sticky;
  bottom: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0 4px;
  background: linear-gradient(to bottom, rgba(238, 235, 226, 0), var(--paper) 38%);
}

.plan-foot-note {
  color: var(--ink-faint);
  font-size: 10px;
}

@media (max-width: 1180px) {
  .planning-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .planning-rail {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    align-items: start;
  }
}

@media (max-width: 760px) {
  .create-panel {
    padding: 20px 16px 28px;
  }

  .create-head h1 {
    font-size: 23px;
  }

  .create-steps {
    display: none;
  }

  .field-row {
    grid-template-columns: minmax(0, 1fr);
  }

  .create-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .planning-rail {
    grid-template-columns: minmax(0, 1fr);
  }

  .planning-status-bar {
    flex-direction: column;
  }

  .status-meta {
    justify-content: flex-start;
  }
}
</style>
