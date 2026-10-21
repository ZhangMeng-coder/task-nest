<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps<{ source: string }>()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
})

const html = computed(() => md.render(props.source))
</script>

<template>
  <!-- markdown-it 已关闭原始 HTML，渲染结果只包含受控 Markdown 结构。 -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="md-body" v-html="html"></div>
</template>
