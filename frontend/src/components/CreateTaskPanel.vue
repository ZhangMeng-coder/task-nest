<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick, Promotion, RefreshLeft } from '@element-plus/icons-vue'
import { describeError, generatePlanDraft, revisePlanDraft } from '@/api/client'
import type { PlanDraftView } from '@/api/events'
import { SCRIPT_OPTIONS } from '@/ui/labels'
import MarkdownView from './MarkdownView.vue'

const props = defineProps<{ starting: boolean }>()

const emit = defineEmits<{
  confirm: [draft: PlanDraftView]
}>()

const script = ref(SCRIPT_OPTIONS[0]?.id ?? 'happy-path')
const goal = ref('')
const draft = ref<PlanDraftView | null>(null)
const generating = ref(false)
const revising = ref(false)
const instruction = ref('')
const error = ref<string | null>(null)

const examples = [
  '为开发者工具做一份竞品分析，并给出差异化建议',
  '设计 CLI 的离线缓存方案，输出第一版实现计划',
  '汇总本周仓库动态，生成团队周报',
]

async function parseGoal(): Promise<void> {
  const goalText = goal.value.trim()
  if (!goalText) {
    ElMessage.warning('先用一段话描述这次要达成的目标。')
    return
  }
  if (generating.value) {
    return
  }
  generating.value = true
  error.value = null
  try {
    draft.value = await generatePlanDraft(script.value, goalText)
    ElMessage.success('计划表已生成，可以确认或继续用提示词修改。')
  } catch (caught) {
    error.value = describeError(caught)
  } finally {
    generating.value = false
  }
}

async function modifyDraft(): Promise<void> {
  const current = draft.value
  const text = instruction.value.trim()
  if (!current) {
    return
  }
  if (!text) {
    ElMessage.warning('先输入一条修改提示。')
    return
  }
  if (revising.value) {
    return
  }
  revising.value = true
  error.value = null
  try {
    draft.value = await revisePlanDraft(current, text)
    instruction.value = ''
    ElMessage.success('已按提示更新计划表。')
  } catch (caught) {
    error.value = describeError(caught)
  } finally {
    revising.value = false
  }
}

function backToGoal(): void {
  draft.value = null
  instruction.value = ''
  error.value = null
}

function confirm(): void {
  if (draft.value && !props.starting) {
    emit('confirm', draft.value)
  }
}
</script>

<template>
  <section class="create-panel">
    <header class="create-head">
      <p class="eyebrow mono">CREATE MAIN TASK</p>
      <h1>描述目标，先生成计划</h1>
      <p class="create-lead">
        主任务不会被立刻执行。系统先强制产出一份 Markdown 计划表，确认后才会创建任务并开始运行。
      </p>
      <el-steps :active="draft ? 1 : 0" finish-status="success" align-center class="create-steps">
        <el-step title="描述目标" description="一句话说清要达成什么" />
        <el-step title="确认计划表" description="用提示词继续修改" />
        <el-step title="运行与观察" description="子任务逐个回报" />
      </el-steps>
    </header>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      closable
      class="create-error"
      @close="error = null"
    />

    <div v-if="!draft" class="create-form">
      <div class="script-field">
        <span class="script-label mono">MOCK SCRIPT</span>
        <el-select v-model="script" :disabled="generating" class="script-select">
          <el-option
            v-for="option in SCRIPT_OPTIONS"
            :key="option.id"
            :label="`${option.name} · ${option.id}`"
            :value="option.id"
          >
            <span class="script-option">
              <span>{{ option.name }}</span>
              <span class="script-option-id mono">{{ option.id }}</span>
            </span>
          </el-option>
        </el-select>
        <span class="script-summary">
          {{ SCRIPT_OPTIONS.find((option) => option.id === script)?.summary }}
        </span>
      </div>

      <el-input
        v-model="goal"
        type="textarea"
        :rows="9"
        resize="none"
        maxlength="2000"
        show-word-limit
        placeholder="例如：为面向开发者的 AI 编程助手产出一页竞品分析，包含三条可追溯事实、两个判断和一个差异化建议。"
        class="goal-textarea"
        @keydown.ctrl.enter.prevent="parseGoal"
        @keydown.meta.enter.prevent="parseGoal"
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
        <span class="create-hint mono">Ctrl / ⌘ + Enter 直接解析</span>
        <el-button
          type="primary"
          size="large"
          :icon="MagicStick"
          :loading="generating"
          native-type="button"
          @click="parseGoal"
        >
          {{ generating ? '正在解析并生成计划…' : '解析目标并生成计划表' }}
        </el-button>
      </div>
    </div>

    <div v-else class="plan-stage">
      <div class="plan-grid">
        <el-card shadow="never" class="plan-card">
          <template #header>
            <div class="card-head">
              <div>
                <span class="card-title">计划表草案</span>
                <span class="card-sub mono">MARKDOWN · {{ draft.rows.length }} STEPS</span>
              </div>
              <el-tag type="warning" effect="plain">尚未保存</el-tag>
            </div>
          </template>
          <MarkdownView :source="draft.markdown" />
        </el-card>

        <aside class="revise-rail">
          <div class="revise-block">
            <p class="eyebrow mono">PROMPT REVISION</p>
            <h3>用提示词修改计划</h3>
            <p class="revise-help">
              不必重新描述目标。直接告诉系统要增加、删除或强调什么，计划表会重新生成。
            </p>
            <el-input
              v-model="instruction"
              type="textarea"
              :rows="4"
              resize="none"
              placeholder="例如：增加一步风险复核；或：精简为三步"
              @keydown.ctrl.enter.prevent="modifyDraft"
              @keydown.meta.enter.prevent="modifyDraft"
            />
            <el-button
              class="revise-button"
              :icon="Promotion"
              :loading="revising"
              @click="modifyDraft"
            >
              按提示修改计划
            </el-button>
          </div>

          <div v-if="draft.revisions.length" class="revision-list">
            <p class="eyebrow mono">REVISIONS</p>
            <div v-for="(item, index) in draft.revisions" :key="`${index}-${item}`" class="revision-row">
              <span class="revision-index mono">v{{ index + 1 }}</span>
              <span>{{ item }}</span>
            </div>
          </div>

          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="计划表会随主任务保存"
            description="创建后可在主任务详情中继续查看，并通过提示词修订。"
          />
        </aside>
      </div>

      <div class="plan-actions">
        <el-button :icon="RefreshLeft" :disabled="starting" @click="backToGoal">
          返回修改目标
        </el-button>
        <el-button
          type="primary"
          size="large"
          :icon="Promotion"
          :loading="starting"
          @click="confirm"
        >
          {{ starting ? '正在创建主任务…' : '确认计划并创建主任务' }}
        </el-button>
      </div>
    </div>
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
  max-width: 680px;
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

.script-field {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.script-label {
  color: var(--ink-faint);
  font-size: 9px;
  letter-spacing: 0.1em;
}

.script-select {
  width: 220px;
}

.script-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.script-option-id {
  color: var(--ink-faint);
  font-size: 10px;
}

.script-summary {
  color: var(--ink-faint);
  font-size: 11px;
  line-height: 1.5;
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

.plan-stage {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 16px;
}

.plan-grid {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
}

.plan-card {
  max-height: 56vh;
  overflow: auto;
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

.revise-rail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.revise-block {
  padding: 16px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.revise-block h3 {
  margin-top: 5px;
  font-size: 15px;
}

.revise-help {
  margin: 7px 0 11px;
  color: var(--ink-soft);
  font-size: 11.5px;
  line-height: 1.65;
}

.revise-button {
  width: 100%;
  margin-top: 9px;
}

.revision-list {
  padding: 13px 14px;
  border: 1px dashed var(--line-strong);
  background: rgba(245, 242, 234, 0.6);
}

.revision-row {
  display: flex;
  gap: 8px;
  margin-top: 7px;
  color: var(--ink-soft);
  font-size: 11px;
  line-height: 1.55;
}

.revision-index {
  flex: 0 0 auto;
  color: var(--signal-deep);
  font-size: 9.5px;
}

.plan-actions {
  position: sticky;
  bottom: 0;
  z-index: 2;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 0 4px;
  background: linear-gradient(to bottom, rgba(238, 235, 226, 0), var(--paper) 38%);
}

@media (max-width: 1080px) {
  .plan-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .revise-rail {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 240px;
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

  .create-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .revise-rail {
    grid-template-columns: minmax(0, 1fr);
  }

  .script-select {
    width: 100%;
  }
}
</style>
