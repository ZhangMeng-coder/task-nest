<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowUp, Folder, FolderOpened, Refresh, Select } from '@element-plus/icons-vue'
import { browseDirectories, describeError, type DirectoryListing } from '@/api/client'

const props = defineProps<{
  modelValue: boolean
  initialPath?: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  select: [path: string]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const listing = ref<DirectoryListing | null>(null)
const address = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const showHidden = ref(false)
let requestId = 0

async function load(path?: string | null): Promise<void> {
  const current = ++requestId
  loading.value = true
  error.value = null
  try {
    const result = await browseDirectories(path, showHidden.value)
    if (current !== requestId) {
      return
    }
    listing.value = result
    address.value = result.path
  } catch (caught) {
    if (current !== requestId) {
      return
    }
    error.value = describeError(caught)
  } finally {
    if (current === requestId) {
      loading.value = false
    }
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    requestId += 1
    listing.value = null
    error.value = null
    address.value = props.initialPath?.trim() ?? ''
    void load(address.value || undefined)
  },
)

function enter(path: string): void {
  void load(path)
}

function goUp(): void {
  if (listing.value?.parent) {
    void load(listing.value.parent)
  }
}

function goRoot(root: string): void {
  void load(root)
}

function reload(): void {
  void load(listing.value?.path ?? address.value)
}

function submitAddress(): void {
  void load(address.value)
}

function choose(): void {
  if (!listing.value) {
    return
  }
  emit('select', listing.value.path)
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="选择工作目录"
    width="min(760px, 94vw)"
    append-to-body
    destroy-on-close
    class="directory-dialog"
  >
    <div class="picker-lead">
      <p>浏览的是后端进程所在的机器。单击文件夹进入，底部按钮选择当前所在目录。</p>
      <el-checkbox v-model="showHidden" size="small" @change="reload">显示隐藏目录</el-checkbox>
    </div>

    <div class="picker-toolbar">
      <el-button
        :icon="ArrowUp"
        :disabled="!listing?.parent || loading"
        title="上一级"
        aria-label="上一级"
        @click="goUp"
      />
      <el-input
        v-model="address"
        class="address-input"
        placeholder="输入绝对路径后按 Enter"
        aria-label="目录路径"
        @keydown.enter.prevent="submitAddress"
      >
        <template #prefix>
          <el-icon><FolderOpened /></el-icon>
        </template>
      </el-input>
      <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
    </div>

    <div v-if="listing?.roots.length" class="picker-roots">
      <span class="roots-label mono">ROOTS</span>
      <button
        v-for="root in listing.roots"
        :key="root"
        type="button"
        class="root-chip mono"
        @click="goRoot(root)"
      >
        {{ root }}
      </button>
    </div>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      closable
      class="picker-error"
      @close="error = null"
    />

    <div v-loading="loading" class="picker-list">
      <button
        v-for="entry in listing?.directories ?? []"
        :key="entry.path"
        type="button"
        class="dir-row"
        :title="entry.path"
        @click="enter(entry.path)"
      >
        <el-icon class="dir-icon"><Folder /></el-icon>
        <span class="dir-name">{{ entry.name }}</span>
        <span class="dir-path mono">{{ entry.path }}</span>
      </button>
      <el-empty
        v-if="!loading && listing && !listing.directories.length"
        description="这个目录下没有子文件夹"
        :image-size="56"
      />
      <p v-if="listing?.truncated" class="picker-note">
        文件夹过多，仅显示前 500 个；可直接输入路径跳转。
      </p>
    </div>

    <template #footer>
      <div class="picker-footer">
        <div class="picker-current">
          <span class="current-label mono">CURRENT</span>
          <code class="current-path" :title="listing?.path ?? ''">{{ listing?.path ?? '—' }}</code>
        </div>
        <div class="picker-actions">
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" :icon="Select" :disabled="!listing" @click="choose">
            选择此目录
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.picker-lead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 12px;
}

.picker-lead p {
  color: var(--ink-faint);
  font-size: 11.5px;
  line-height: 1.6;
}

.picker-toolbar {
  display: flex;
  gap: 8px;
}

.address-input {
  flex: 1;
}

.picker-roots {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 9px;
}

.roots-label {
  color: var(--ink-faint);
  font-size: 9px;
  letter-spacing: 0.1em;
}

.root-chip {
  padding: 3px 7px;
  border: 1px solid var(--line);
  background: var(--paper-raised);
  color: var(--ink-soft);
  font-size: 10px;
  line-height: 1.5;
}

.root-chip:hover,
.root-chip:focus-visible {
  border-color: var(--signal);
  color: var(--signal-deep);
}

.picker-error {
  margin-top: 10px;
}

.picker-list {
  min-height: 280px;
  max-height: 46vh;
  margin-top: 10px;
  overflow: auto;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.dir-row {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 9px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--ink);
  text-align: left;
  transition: background 120ms ease;
}

.dir-row:last-of-type {
  border-bottom: 0;
}

.dir-row:hover,
.dir-row:focus-visible {
  background: rgba(238, 90, 42, 0.06);
  outline: none;
}

.dir-icon {
  flex: 0 0 auto;
  color: var(--signal-deep);
}

.dir-name {
  flex: 0 0 auto;
  font-size: 12.5px;
  font-weight: 600;
}

.dir-path {
  overflow: hidden;
  flex: 1;
  color: var(--ink-faint);
  font-size: 9.5px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker-note {
  padding: 9px 12px;
  color: var(--ink-faint);
  font-size: 10.5px;
}

.picker-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.picker-current {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  text-align: left;
}

.current-label {
  color: var(--ink-faint);
  font-size: 8.5px;
  letter-spacing: 0.1em;
}

.current-path {
  overflow: hidden;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

@media (max-width: 760px) {
  .picker-lead,
  .picker-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .dir-path {
    display: none;
  }
}
</style>
