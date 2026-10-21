<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useTaskTreeStore } from '@/stores/taskTree'
import MarkdownView from './MarkdownView.vue'

defineProps<{ archiveMode: boolean }>()

const store = useTaskTreeStore()
const instruction = ref('')

async function applyRevision(): Promise<void> {
  const text = instruction.value.trim()
  if (!text) {
    ElMessage.warning('先输入一条修改提示。')
    return
  }
  const ok = await store.revisePlan(text)
  if (ok) {
    instruction.value = ''
    ElMessage.success(`计划表已更新到 v${store.plan?.version ?? '—'}。`)
  }
}
</script>

<template>
  <div class="plan-sheet">
    <template v-if="store.plan">
      <div class="plan-sheet-head">
        <div>
          <p class="eyebrow mono">PLAN · SAVED WITH MAIN TASK</p>
          <h2>执行计划表</h2>
          <p>计划表随主任务档案保存，是本次运行的执行依据。</p>
        </div>
        <el-tag effect="plain">Markdown · v{{ store.plan.version }}</el-tag>
      </div>

      <div class="plan-sheet-body">
        <MarkdownView :source="store.plan.markdown" />
      </div>

      <div v-if="store.plan.revisions.length" class="revision-list">
        <p class="eyebrow mono">REVISIONS</p>
        <div
          v-for="(item, index) in store.plan.revisions"
          :key="`${index}-${item}`"
          class="revision-row"
        >
          <span class="revision-index mono">v{{ index + 1 }}</span>
          <span>{{ item }}</span>
        </div>
      </div>

      <div v-if="!archiveMode" class="plan-revise">
        <div class="plan-revise-head">
          <div>
            <p class="detail-label mono">PROMPT REVISION</p>
            <h3>用提示词修改计划</h3>
          </div>
          <span class="plan-revise-hint">
            修订只更新档案中的计划表；已经开始的子任务不会因此改变。
          </span>
        </div>
        <el-input
          v-model="instruction"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="500"
          placeholder="例如：增加一步风险复核；或：精简为三步"
          @keydown.ctrl.enter.prevent="applyRevision"
          @keydown.meta.enter.prevent="applyRevision"
        />
        <el-alert
          v-if="store.planError"
          :title="store.planError"
          type="error"
          :closable="false"
          show-icon
          class="plan-error"
        />
        <div class="plan-revise-actions">
          <span class="plan-revise-note mono">计划 v{{ store.plan.version }} · {{ store.plan.rows.length }} 步</span>
          <el-button
            type="primary"
            :loading="store.planRevising"
            :disabled="store.planRevising"
            @click="applyRevision"
          >
            应用修改
          </el-button>
        </div>
      </div>

      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        title="只读档案，计划表不可修改"
        description="历史计划表随主任务一起保存，用于回看当时的执行依据。"
      />
    </template>

    <el-empty v-else description="该主任务没有保存计划表" :image-size="84" />
  </div>
</template>

<style scoped>
.eyebrow {
  color: var(--signal-deep);
  font-size: 9.5px;
  letter-spacing: 0.11em;
}

.detail-label {
  color: var(--ink-faint);
  font-size: 8.5px;
  letter-spacing: 0.1em;
}

.plan-sheet {
  max-width: 940px;
}

.plan-sheet-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
}

.plan-sheet-head h2 {
  margin-top: 4px;
  font-size: 20px;
}

.plan-sheet-head p {
  margin-top: 5px;
  color: var(--ink-soft);
  font-size: 11.5px;
  line-height: 1.6;
}

.plan-sheet-body {
  margin-top: 16px;
  padding: 22px 24px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.revision-list {
  margin-top: 14px;
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

.plan-revise {
  margin-top: 16px;
  padding: 15px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.plan-revise-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
}

.plan-revise-head h3 {
  margin-top: 3px;
  font-size: 14px;
}

.plan-revise-hint {
  max-width: 320px;
  color: var(--ink-faint);
  font-size: 10.5px;
  line-height: 1.55;
  text-align: right;
}

.plan-error {
  margin-top: 10px;
}

.plan-revise-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.plan-revise-note {
  color: var(--ink-faint);
  font-size: 10px;
}

.plan-sheet > :deep(.el-alert) {
  margin-top: 16px;
}

@media (max-width: 760px) {
  .plan-sheet-head,
  .plan-revise-head {
    flex-direction: column;
  }

  .plan-revise-hint {
    max-width: none;
    text-align: left;
  }
}
</style>
