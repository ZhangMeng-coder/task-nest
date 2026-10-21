<script setup lang="ts">
import { computed, ref } from 'vue'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { MAIN_TASK_STATUS_LABEL, mainTagType, pad2 } from '@/ui/labels'
import type { DirectoryTask } from '@/ui/directory'

const props = defineProps<{
  tasks: DirectoryTask[]
  selectedId: string | null
  loading: boolean
  error: string | null
}>()

const emit = defineEmits<{
  select: [id: string]
  create: []
  reload: []
}>()

const search = ref('')

const filtered = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) {
    return props.tasks
  }
  return props.tasks.filter((task) =>
    `${task.title} ${task.goal ?? ''}`.toLowerCase().includes(keyword),
  )
})

function progressOf(task: DirectoryTask): number {
  if (task.subtaskCount === 0) {
    return 0
  }
  return Math.round((task.completedCount / task.subtaskCount) * 100)
}
</script>

<template>
  <aside class="directory">
    <div class="directory-head">
      <div>
        <p class="eyebrow mono">DIRECTORY</p>
        <h2>主任务目录</h2>
      </div>
      <span class="directory-count mono">{{ filtered.length }}/{{ tasks.length }}</span>
    </div>

    <el-input
      v-model="search"
      :prefix-icon="Search"
      placeholder="筛选主任务"
      clearable
      class="directory-search"
    />
    <el-button class="new-task-button" type="primary" :icon="Plus" @click="emit('create')">
      新建主任务
    </el-button>

    <nav class="task-nav" aria-label="主任务目录">
      <el-skeleton v-if="loading && !tasks.length" :rows="4" animated class="nav-loading" />

      <el-alert
        v-else-if="error"
        type="error"
        :title="error"
        :closable="false"
        show-icon
        class="nav-error"
      >
        <el-button size="small" text :icon="Refresh" @click="emit('reload')">重试</el-button>
      </el-alert>

      <template v-else>
        <button
          v-for="(task, index) in filtered"
          :key="task.id"
          type="button"
          class="task-nav-item"
          :class="{ 'is-active': task.id === selectedId }"
          @click="emit('select', task.id)"
        >
          <span class="nav-index mono">{{ pad2(index + 1) }}</span>
          <span class="nav-content">
            <span class="nav-top">
              <span class="nav-title">{{ task.title }}</span>
              <el-tag size="small" effect="plain" :type="mainTagType(task.status)">
                {{ MAIN_TASK_STATUS_LABEL[task.status] }}
              </el-tag>
            </span>
            <span class="nav-meta mono">
              <template v-if="task.live">实时 · </template>
              {{ task.completedCount }}/{{ task.subtaskCount }} 子任务 · 计划 v{{ task.planVersion }}
            </span>
            <el-progress
              :percentage="progressOf(task)"
              :show-text="false"
              :stroke-width="2"
              class="nav-progress"
            />
          </span>
        </button>
        <el-empty
          v-if="!filtered.length"
          :description="tasks.length ? '没有匹配的主任务' : '还没有主任务，先创建一个'"
          :image-size="64"
        />
      </template>
    </nav>

    <div class="directory-foot">
      <span class="mono">MYSQL ARCHIVE</span>
      <p>主任务、子任务、提示词、中间结果与事件原文均写入 MySQL。</p>
    </div>
  </aside>
</template>

<style scoped>
.directory {
  display: flex;
  min-height: 0;
  flex-direction: column;
  padding: 17px 14px 12px;
  border-right: 1px solid var(--line-strong);
  background: rgba(245, 242, 234, 0.78);
}

.eyebrow {
  color: var(--signal-deep);
  font-size: 9.5px;
  letter-spacing: 0.11em;
}

.directory-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.directory-head h2 {
  margin-top: 3px;
  font-size: 16px;
}

.directory-count {
  color: var(--ink-faint);
  font-size: 10px;
}

.directory-search {
  margin-top: 13px;
}

.new-task-button {
  width: 100%;
  margin-top: 8px;
}

.task-nav {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  margin-top: 13px;
  overflow: auto;
  padding-right: 2px;
}

.nav-loading,
.nav-error {
  margin-top: 4px;
}

.task-nav-item {
  display: flex;
  width: 100%;
  gap: 9px;
  padding: 9px 10px;
  border: 1px solid transparent;
  background: transparent;
  text-align: left;
  transition: background 120ms ease, border-color 120ms ease;
}

.task-nav-item:hover {
  border-color: var(--line);
  background: var(--paper-raised);
}

.task-nav-item.is-active {
  border-color: var(--line-strong);
  background: var(--paper-raised);
  box-shadow: inset 3px 0 0 var(--signal);
}

.nav-index {
  flex: 0 0 auto;
  padding-top: 2px;
  color: var(--ink-faint);
  font-size: 9px;
}

.nav-content {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.nav-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 7px;
}

.nav-title {
  overflow: hidden;
  color: var(--ink);
  font-family: var(--font-display);
  font-size: 12.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-top :deep(.el-tag) {
  flex: 0 0 auto;
  padding: 0 4px;
  font-size: 9px;
}

.nav-meta {
  margin-top: 3px;
  overflow: hidden;
  color: var(--ink-faint);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-progress {
  margin-top: 6px;
}

.nav-progress :deep(.el-progress-bar__outer),
.nav-progress :deep(.el-progress-bar__inner) {
  border-radius: 0;
}

.nav-progress :deep(.el-progress-bar__outer) {
  background: var(--paper-sunk);
}

.directory-foot {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
  color: var(--ink-faint);
  font-size: 10px;
  line-height: 1.55;
}

.directory-foot span {
  display: block;
  margin-bottom: 3px;
  color: var(--ink-soft);
  font-size: 8.5px;
  letter-spacing: 0.12em;
}
</style>
