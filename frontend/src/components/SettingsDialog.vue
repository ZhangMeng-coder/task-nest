<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  describeError,
  getAgentSettings,
  getLlmSettings,
  updateAgentSettings,
  updateLlmSettings,
  type ApiKeyType,
  type LlmSettingsView,
} from '@/api/client'
import type { AgentSettingsView } from '@/api/events'
import { formatDateTime } from '@/ui/labels'

const props = defineProps<{ modelValue: boolean }>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const activeTab = ref<'llm' | 'agent'>('llm')
const baseUrl = ref('')
const modelName = ref('')
const apiKeyType = ref<ApiKeyType>('ENV')
const apiKeyInput = ref('')
const loaded = ref<LlmSettingsView | null>(null)
const agentLoaded = ref<AgentSettingsView | null>(null)
const reviewEnabled = ref(true)
const reviewMaxReworks = ref(2)
const loading = ref(false)
const saving = ref(false)
const agentSaving = ref(false)
const error = ref<string | null>(null)

const hasStoredLiteral = computed(() => (
  loaded.value?.apiKeyType === 'LITERAL' && loaded.value.apiKeyConfigured
))

const envStatus = computed<'resolved' | 'missing' | null>(() => {
  const current = loaded.value
  if (
    apiKeyType.value !== 'ENV'
    || !current
    || current.apiKeyType !== 'ENV'
    || current.apiKeyValue !== apiKeyInput.value.trim()
  ) {
    return null
  }
  return current.envResolved ? 'resolved' : 'missing'
})

function apply(view: LlmSettingsView): void {
  loaded.value = view
  baseUrl.value = view.baseUrl
  modelName.value = view.modelName
  apiKeyType.value = view.apiKeyType
  apiKeyInput.value = view.apiKeyType === 'ENV' ? view.apiKeyValue ?? '' : ''
}

function applyAgent(view: AgentSettingsView): void {
  agentLoaded.value = view
  reviewEnabled.value = view.reviewEnabled
  reviewMaxReworks.value = view.reviewMaxReworks
}

async function load(): Promise<void> {
  if (loading.value) {
    return
  }
  loading.value = true
  error.value = null
  try {
    const [llm, agent] = await Promise.all([getLlmSettings(), getAgentSettings()])
    apply(llm)
    applyAgent(agent)
  } catch (caught) {
    error.value = describeError(caught)
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      void load()
    }
  },
)

function onTypeChange(value: string | number | boolean | undefined): void {
  const next = value === 'LITERAL' ? 'LITERAL' : 'ENV'
  apiKeyInput.value = next === 'ENV' && loaded.value?.apiKeyType === 'ENV'
    ? loaded.value.apiKeyValue ?? ''
    : ''
}

async function saveAgent(): Promise<void> {
  if (agentSaving.value) {
    return
  }
  if (reviewMaxReworks.value < 0 || reviewMaxReworks.value > 5) {
    error.value = '返工次数必须在 0 到 5 之间。'
    return
  }
  agentSaving.value = true
  error.value = null
  try {
    applyAgent(await updateAgentSettings(reviewEnabled.value, reviewMaxReworks.value))
    ElMessage.success('审核设置已保存并立即生效。')
    visible.value = false
  } catch (caught) {
    error.value = describeError(caught)
  } finally {
    agentSaving.value = false
  }
}

function saveCurrent(): void {
  if (activeTab.value === 'agent') {
    void saveAgent()
    return
  }
  void saveLlm()
}

async function saveLlm(): Promise<void> {
  if (saving.value) {
    return
  }
  const url = baseUrl.value.trim()
  const model = modelName.value.trim()
  const key = apiKeyInput.value.trim()
  if (!url) {
    error.value = '请填写 LLM 请求地址，例如 https://api.openai.com/v1。'
    return
  }
  if (!model) {
    error.value = '请填写模型名称，例如 gpt-4o。'
    return
  }
  if (model.length > 128) {
    error.value = '模型名称最多 128 字。'
    return
  }
  if (apiKeyType.value === 'ENV') {
    if (!key) {
      error.value = '请填写环境变量名，例如 OPENAI_API_KEY。'
      return
    }
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) {
      error.value = '环境变量名只能包含字母、数字和下划线，且不能以数字开头。'
      return
    }
  } else if (!key && !hasStoredLiteral.value) {
    error.value = '请填写 API Key，或切换为环境变量模式。'
    return
  }

  saving.value = true
  error.value = null
  try {
    const view = await updateLlmSettings({
      baseUrl: url,
      modelName: model,
      apiKeyType: apiKeyType.value,
      apiKeyValue: key || undefined,
    })
    apply(view)
    ElMessage.success('LLM 设置已保存，运行时配置已立即更新。')
    visible.value = false
  } catch (caught) {
    error.value = describeError(caught)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="设置"
    width="min(620px, 94vw)"
    append-to-body
    destroy-on-close
    class="settings-dialog"
  >
    <div v-loading="loading" class="settings-body">
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        show-icon
        closable
        class="settings-error"
        @close="error = null"
      />

      <el-tabs v-model="activeTab" class="settings-tabs">
        <el-tab-pane label="LLM 连接" name="llm">
          <el-form label-position="top" class="settings-form" @submit.prevent="saveLlm">
            <el-form-item label="请求地址（OpenAI 兼容）" required>
              <el-input
                v-model="baseUrl"
                placeholder="https://api.openai.com/v1"
                autocomplete="off"
              />
              <p class="field-help">
                填写 OpenAI 兼容接口的 Base URL，包含版本路径，例如
                <code>https://api.openai.com/v1</code> 或本地网关地址。
              </p>
            </el-form-item>

            <el-form-item label="模型名称" required>
              <el-input
                v-model="modelName"
                placeholder="gpt-4o"
                maxlength="128"
                show-word-limit
                autocomplete="off"
                class="model-input"
              />
              <p class="field-help">
                填写服务商提供的模型 ID，例如 <code>gpt-4o</code>、<code>qwen-plus</code>。
                自由文本输入，最多 128 字。
              </p>
            </el-form-item>

            <el-form-item label="API Key 来源" required>
              <el-radio-group v-model="apiKeyType" @change="onTypeChange">
                <el-radio value="LITERAL">直接写入</el-radio>
                <el-radio value="ENV">读取环境变量</el-radio>
              </el-radio-group>
            </el-form-item>

            <el-form-item
              v-if="apiKeyType === 'LITERAL'"
              label="API Key"
              :required="!hasStoredLiteral"
            >
              <el-input
                v-model="apiKeyInput"
                type="password"
                show-password
                autocomplete="new-password"
                :placeholder="hasStoredLiteral ? '留空则沿用已保存的 Key' : '输入 API Key'"
              />
              <p class="field-help">
                直接写入会以明文保存在本机 MySQL；查询接口只返回掩码。
                <template v-if="hasStoredLiteral">
                  当前已保存 <code>{{ loaded?.apiKeyPreview }}</code>，留空不会覆盖。
                </template>
              </p>
            </el-form-item>

            <template v-else>
              <el-form-item label="环境变量名" required>
                <el-input
                  v-model="apiKeyInput"
                  placeholder="OPENAI_API_KEY"
                  autocomplete="off"
                  class="env-input"
                />
                <p class="field-help">
                  数据库只保存变量名。后端进程每次读取配置时会从自己的环境变量中解析实际 Key。
                </p>
              </el-form-item>
              <el-alert
                v-if="envStatus"
                :type="envStatus === 'resolved' ? 'success' : 'warning'"
                :closable="false"
                show-icon
                class="env-status"
                :title="envStatus === 'resolved'
                  ? '当前后端进程已能读取该环境变量'
                  : '当前后端进程读不到该环境变量，请检查启动环境'"
              />
            </template>

            <el-alert
              type="info"
              :closable="false"
              show-icon
              title="P3 会使用该连接发起真实模型请求"
              description="Base URL 指向 OpenAI 兼容的 /chat/completions；保存后写入 MySQL 并立即替换运行时配置。密钥不会出现在接口响应与日志中。"
            />
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="Agent 审核" name="agent">
          <el-form label-position="top" class="settings-form" @submit.prevent="saveAgent">
            <el-form-item label="子任务审核 Agent">
              <el-switch
                v-model="reviewEnabled"
                active-text="启用独立审核"
                inactive-text="关闭审核"
              />
              <p class="field-help">
                启用后，每个子任务实现完成会交给独立的审核 Agent：只读复核改动、自行运行测试，
                通过后才合并分支；需要返工时由原子任务继续修改。
              </p>
            </el-form-item>

            <el-form-item label="最大返工次数">
              <el-input-number
                v-model="reviewMaxReworks"
                :min="0"
                :max="5"
                :disabled="!reviewEnabled"
              />
              <p class="field-help">
                审核判定 REWORK 后最多重新执行并复核的次数，范围 0–5。超过后子任务失败并回滚分支。
                关闭审核时该值不生效。
              </p>
            </el-form-item>

            <el-alert
              type="info"
              :closable="false"
              show-icon
              title="审核证据以平台执行为准"
              description="审核 Agent 没有文件写入工具，shell 仅限测试 / 构建 / lint / 只读 Git。"
            />
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>

    <template #footer>
      <div class="settings-footer">
        <span class="settings-updated">
          <template v-if="activeTab === 'agent'">
            {{ agentLoaded?.updatedAt
              ? `上次保存 ${formatDateTime(agentLoaded.updatedAt)}`
              : '尚未保存，使用默认审核配置' }}
          </template>
          <template v-else>
            {{ loaded?.updatedAt
              ? `上次保存 ${formatDateTime(loaded.updatedAt)}`
              : '尚未保存，使用内置默认配置' }}
          </template>
        </span>
        <div class="settings-actions">
          <el-button :icon="Refresh" :loading="loading" @click="load">重新加载</el-button>
          <el-button @click="visible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="activeTab === 'agent' ? agentSaving : saving"
            @click="saveCurrent"
          >
            保存并应用
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.settings-body {
  min-height: 180px;
}

.settings-error {
  margin-bottom: 14px;
}

.settings-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.settings-tabs :deep(.el-tabs__item) {
  font-size: 12.5px;
}

.settings-form :deep(.el-form-item__label) {
  color: var(--ink);
  font-size: 12px;
  font-weight: 600;
}

.field-help {
  margin-top: 5px;
  color: var(--ink-faint);
  font-size: 10.5px;
  line-height: 1.6;
}

.field-help code {
  color: var(--signal-deep);
  font-family: var(--font-mono);
  font-size: 10px;
}

.env-input :deep(.el-input__inner),
.model-input :deep(.el-input__inner) {
  font-family: var(--font-mono);
  font-size: 11.5px;
}

.env-status {
  margin-top: -8px;
  margin-bottom: 16px;
}

.settings-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.settings-updated {
  color: var(--ink-faint);
  font-size: 10px;
}

.settings-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

@media (max-width: 760px) {
  .settings-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .settings-actions {
    width: 100%;
    flex-wrap: wrap;
  }
}
</style>
