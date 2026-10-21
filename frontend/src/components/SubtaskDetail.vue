<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { SubtaskNodeView, ToolCallView } from '@/stores/taskTree'
import {
  formatDuration,
  isTerminalTaskStatus,
  pad2,
  SUBTASK_STATUS_LABEL,
  TOOL_STATUS_LABEL,
} from '@/ui/labels'

const props = defineProps<{
  node: SubtaskNodeView
  index: number
  siblings: SubtaskNodeView[]
  archiveMode: boolean
  submitting: boolean
  decisionError: string | null
}>()

const emit = defineEmits<{
  decide: [optionId: string]
}>()

const promptIndex = ref(0)
const selectedOption = ref('')

const statusLabel = computed(() => SUBTASK_STATUS_LABEL[props.node.status])

const activePrompt = computed(() => {
  const prompts = props.node.prompts
  if (!prompts.length) {
    return null
  }
  const index = Math.min(Math.max(promptIndex.value, 0), prompts.length - 1)
  return prompts[index] ?? null
})

watch(
  () => [props.node.taskId, props.node.prompts.length] as const,
  () => {
    promptIndex.value = Math.max(0, props.node.prompts.length - 1)
  },
  { immediate: true },
)

watch(
  () => props.node.waiting?.question,
  () => {
    selectedOption.value = ''
  },
)

const dependency = computed(() => {
  if (!props.node.dependsOn.length) {
    return props.node.order === 0 ? '无依赖' : `依赖第 ${props.node.order} 步`
  }
  const indexes = props.node.dependsOn
    .map((id) => props.siblings.findIndex((item) => item.taskId === id))
    .filter((index) => index >= 0)
  return indexes.length
    ? `依赖第 ${indexes.map((index) => index + 1).join('、')} 步`
    : '无依赖'
})

const thoughtEmpty = computed(() => {
  switch (props.node.status) {
    case 'PENDING':
      return '任务尚未开始。'
    case 'RUNNING':
      return '等待首条思考增量…'
    case 'RETRYING':
      return '上一次尝试失败，正在重新执行。'
    case 'WAITING_INPUT':
      return '任务已暂停，等待你在下方做出选择。'
    default:
      return '该子任务没有记录思考轨迹。'
  }
})

function toolState(tool: ToolCallView): string {
  if (tool.orphan) {
    return '孤立结果'
  }
  if (tool.result) {
    return TOOL_STATUS_LABEL[tool.result.status]
  }
  return isTerminalTaskStatus(props.node.status) ? '未返回' : '调用中'
}

function toolTagType(tool: ToolCallView): 'primary' | 'success' | 'warning' | 'danger' {
  if (tool.orphan) {
    return 'danger'
  }
  if (!tool.result) {
    return isTerminalTaskStatus(props.node.status) ? 'danger' : 'primary'
  }
  return tool.result.status === 'success' ? 'success' : 'danger'
}

function toolOutput(tool: ToolCallView): string {
  if (tool.orphan) {
    return '孤立结果：没有匹配到对应的 tool_call。'
  }
  if (!tool.result) {
    return isTerminalTaskStatus(props.node.status)
      ? '任务已结束，但工具没有返回结果。'
      : '等待工具返回…'
  }
  return formatJson(tool.result.result)
}

function formatJson(value: unknown): string {
  if (typeof value === 'string') {
    return value
  }
  try {
    return JSON.stringify(value, null, 2) ?? String(value)
  } catch {
    return String(value)
  }
}

function submitDecision(): void {
  if (selectedOption.value && !props.submitting) {
    emit('decide', selectedOption.value)
  }
}
</script>

<template>
  <div class="subtask-body">
    <el-alert
      v-if="node.status === 'SUCCESS' && node.summary"
      :title="node.summary"
      type="success"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="node.status === 'FAILED' && node.error"
      :title="node.error"
      type="error"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="node.status === 'RETRYING'"
      title="上一次尝试失败，子任务正在重试。"
      type="warning"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="node.status === 'WAITING_INPUT'"
      :title="node.waiting?.question ?? '等待抉择'"
      type="warning"
      :closable="false"
      show-icon
    />

    <div class="detail-section">
      <p class="detail-label mono">THOUGHT · 思考增量</p>
      <p v-if="node.thought" class="thought-text">{{ node.thought }}</p>
      <p v-else class="detail-empty">{{ thoughtEmpty }}</p>
    </div>

    <div class="detail-split">
      <div class="detail-section">
        <div class="section-inline-head">
          <p class="detail-label mono">PROMPT · 提示词</p>
          <el-select
            v-if="node.prompts.length > 1"
            v-model.number="promptIndex"
            size="small"
            class="prompt-switch"
            aria-label="选择提示词拼接版本"
          >
            <el-option
              v-for="(_, promptOptionIndex) in node.prompts"
              :key="promptOptionIndex"
              :label="`第 ${promptOptionIndex + 1} 次拼接`"
              :value="promptOptionIndex"
            />
          </el-select>
        </div>
        <el-descriptions v-if="activePrompt" :column="1" border size="small" class="prompt-desc">
          <el-descriptions-item label="System">
            <span class="prompt-text">{{ activePrompt.systemPrompt }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="User">
            <span class="prompt-text">{{ activePrompt.userPrompt }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="activePrompt.contextSnippets.length" label="Context">
            <div class="snippet-list">
              <div
                v-for="(snippet, snippetIndex) in activePrompt.contextSnippets"
                :key="`${snippet.source}-${snippetIndex}`"
                class="snippet"
              >
                <span class="snippet-source mono">{{ snippet.source }}</span>
                <span class="prompt-text">{{ snippet.content }}</span>
              </div>
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="Final">
            <pre class="prompt-final">{{ activePrompt.finalPrompt }}</pre>
          </el-descriptions-item>
        </el-descriptions>
        <p v-else class="detail-empty">
          {{ node.status === 'PENDING' ? '任务尚未开始，还没有拼接提示词。' : '该子任务没有提示词拼装记录。' }}
        </p>
      </div>

      <div class="detail-section">
        <p class="detail-label mono">TOOLS · 工具调用</p>
        <el-table v-if="node.toolCalls.length" :data="node.toolCalls" size="small" class="tool-table">
          <el-table-column prop="toolName" label="工具" width="104" />
          <el-table-column label="参数" min-width="140">
            <template #default="{ row }">
              <span class="tool-cell mono">{{ row.args ? formatJson(row.args) : '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="返回" min-width="160">
            <template #default="{ row }">
              <span class="tool-cell mono">{{ toolOutput(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="86">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="toolTagType(row)">
                {{ toolState(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="72">
            <template #default="{ row }">
              <span class="mono">{{ formatDuration(row.result?.durationMs ?? null) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <p v-else class="detail-empty">
          {{ node.status === 'PENDING' ? '任务尚未开始，还没有工具调用。' : '该子任务没有工具调用。' }}
        </p>
      </div>
    </div>

    <div v-if="node.status === 'WAITING_INPUT' && node.waiting" class="decision-box">
      <template v-if="archiveMode">
        <div class="decision-head">
          <div>
            <p class="detail-label mono">ARCHIVED DECISION</p>
            <h3>{{ node.waiting.question }}</h3>
          </div>
          <el-tag type="info" effect="plain" size="small">历史等待记录</el-tag>
        </div>
        <p class="archive-note">只读档案，不能提交抉择。</p>
      </template>
      <template v-else>
        <div class="decision-head">
          <div>
            <p class="detail-label mono">DECISION REQUIRED</p>
            <h3>{{ node.waiting.question }}</h3>
          </div>
          <el-tag type="warning" effect="dark" size="small">等待抉择</el-tag>
        </div>

        <el-radio-group v-model="selectedOption" class="decision-group">
          <el-radio
            v-for="option in node.waiting.options"
            :key="option.id"
            :value="option.id"
            border
            class="decision-radio"
          >
            <span class="decision-label">{{ option.label }}</span>
            <span class="decision-desc">{{ option.description }}</span>
          </el-radio>
        </el-radio-group>

        <el-alert
          v-if="decisionError"
          :title="decisionError"
          type="error"
          :closable="false"
          show-icon
          class="decision-error"
        />

        <div class="decision-actions">
          <el-button
            type="primary"
            :loading="submitting"
            :disabled="!selectedOption"
            @click="submitDecision"
          >
            提交选择并继续
          </el-button>
          <span class="decision-note">提交后编排器会从暂停处继续。</span>
        </div>
      </template>
    </div>

    <p v-if="node.selectedOptionId && node.status !== 'WAITING_INPUT'" class="decision-done">
      已提交选择 <span class="mono">{{ node.selectedOptionId }}</span>
      <template v-if="archiveMode">，这是历史运行中的选择记录。</template>
      <template v-else>，编排器已继续执行。</template>
    </p>

    <div class="subtask-foot mono">
      <span>{{ pad2(index + 1) }} · {{ node.agentName }}</span>
      <span>{{ dependency }}</span>
      <span>{{ statusLabel }}</span>
    </div>
  </div>
</template>

<style scoped>
.subtask-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 2px;
}

.subtask-body :deep(.el-alert) {
  align-items: flex-start;
}

.detail-section {
  min-width: 0;
}

.detail-label {
  margin-bottom: 6px;
  color: var(--ink-faint);
  font-size: 8.5px;
  letter-spacing: 0.1em;
}

.section-inline-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.prompt-switch {
  width: 120px;
  margin-bottom: 6px;
}

.thought-text {
  padding: 10px 12px;
  border-left: 2px solid var(--line-strong);
  background: rgba(229, 225, 213, 0.45);
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.prompt-desc :deep(.el-descriptions__label) {
  width: 62px;
  background: var(--paper-sunk);
  color: var(--ink-faint);
  font-family: var(--font-mono);
  font-size: 9.5px;
}

.prompt-desc :deep(.el-descriptions__content) {
  font-size: 11px;
  line-height: 1.6;
}

.prompt-text {
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-word;
}

.prompt-final {
  max-height: 150px;
  overflow: auto;
  margin: 0;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.snippet-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.snippet {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 5px 7px;
  border-left: 2px solid var(--line);
  background: var(--paper-sunk);
}

.snippet-source {
  color: var(--signal-deep);
  font-size: 9px;
}

.tool-table :deep(.el-table__cell) {
  padding: 5px 0;
}

.tool-table :deep(th.el-table__cell) {
  background: var(--paper-sunk);
  color: var(--ink-faint);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 500;
}

.tool-table :deep(td.el-table__cell) {
  color: var(--ink-soft);
  font-size: 10.5px;
}

.tool-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-empty {
  padding: 14px 12px;
  border: 1px dashed var(--line);
  color: var(--ink-faint);
  font-size: 10.5px;
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
  gap: 12px;
}

.decision-head .detail-label {
  color: var(--retry);
}

.decision-head h3 {
  margin-top: 3px;
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

.decision-error {
  margin-top: 10px;
}

.decision-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.decision-note,
.archive-note {
  color: var(--ink-faint);
  font-size: 10.5px;
}

.archive-note {
  margin-top: 8px;
}

.decision-done {
  padding: 9px 12px;
  border-left: 3px solid var(--success);
  background: rgba(45, 106, 74, 0.06);
  color: var(--ink-soft);
  font-size: 12px;
}

.decision-done .mono {
  color: var(--success);
}

.subtask-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  padding-top: 8px;
  border-top: 1px solid var(--line);
  color: var(--ink-faint);
  font-size: 9px;
}

@media (max-width: 1180px) {
  .detail-split {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 760px) {
  .decision-head {
    flex-direction: column;
    gap: 8px;
  }
}
</style>
