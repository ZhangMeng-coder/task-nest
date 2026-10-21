<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  total: number
  active: number
  archiveMode: boolean
}>()

const modeLabel = computed(() => (props.archiveMode ? '只读档案' : '实时工作台'))
</script>

<template>
  <header class="app-head">
    <div class="brand">
      <span class="brand-mark mono" aria-hidden="true">TN</span>
      <div class="brand-copy">
        <p class="brand-kicker mono">TASKNEST / 任务巢</p>
        <h1>本地 Agent 任务工作台</h1>
      </div>
    </div>
    <div class="head-side">
      <div class="head-stat">
        <span class="head-stat-value mono">{{ String(total).padStart(2, '0') }}</span>
        <span class="head-stat-label">主任务</span>
      </div>
      <div class="head-stat">
        <span class="head-stat-value mono">{{ String(active).padStart(2, '0') }}</span>
        <span class="head-stat-label">进行中</span>
      </div>
      <span class="mode-badge mono">{{ modeLabel }}</span>
    </div>
  </header>
</template>

<style scoped>
.app-head {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 13px 22px;
  border-bottom: 3px solid var(--signal);
  background: var(--ledger);
  color: #f0f1e9;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid var(--signal);
  color: var(--signal);
  font-size: 11px;
  letter-spacing: 0.06em;
}

.brand-kicker {
  color: var(--ledger-dim);
  font-size: 9px;
  letter-spacing: 0.14em;
}

.brand-copy h1 {
  margin-top: 2px;
  color: #f0f1e9;
  font-size: 17px;
  letter-spacing: 0.01em;
}

.head-side {
  display: flex;
  align-items: center;
  gap: 20px;
}

.head-stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding-right: 20px;
  border-right: 1px solid var(--ledger-line);
}

.head-stat-value {
  color: var(--signal);
  font-size: 18px;
  line-height: 1;
}

.head-stat-label {
  color: var(--ledger-dim);
  font-size: 10px;
}

.mode-badge {
  padding: 4px 8px;
  border: 1px solid var(--ledger-line);
  color: var(--ledger-dim);
  font-size: 9px;
  letter-spacing: 0.08em;
}

@media (max-width: 820px) {
  .app-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
}
</style>
