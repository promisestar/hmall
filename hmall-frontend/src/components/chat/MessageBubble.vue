<template>
  <div class="flex w-full" :class="msg.type === 'human' ? 'justify-end' : 'justify-start'">
    <div
      class="message-bubble px-4 py-3 rounded-2xl text-[14px] leading-relaxed transition-all duration-300 overflow-hidden"
      :class="msg.type === 'human'
        ? 'max-w-[70%] bg-gradient-to-br from-[#E4393C] to-[#C81623] text-white rounded-br-md shadow-lg shadow-red-200/50'
        : 'max-w-[85%] bg-white text-[#333] rounded-bl-md shadow-sm border border-gray-100'"
    >
      <!-- AI 消息：头像 + Markdown 内容 -->
      <div v-if="msg.type === 'ai'" class="flex items-start gap-2.5">
        <div class="flex-shrink-0 w-8 h-8 rounded-full bg-gradient-to-br from-[#FF6B35] to-[#E4393C] flex items-center justify-center text-white text-xs font-bold mt-0.5 shadow-sm">
          AI
        </div>
        <div class="flex-1 min-w-0 overflow-hidden">
          <div class="markdown-body" v-html="renderedContent"></div>
          <!-- 流式加载动画 -->
          <div v-if="isLoading" class="flex items-center gap-1 mt-1.5">
            <span class="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style="animation-delay: 0ms"></span>
            <span class="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style="animation-delay: 150ms"></span>
            <span class="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style="animation-delay: 300ms"></span>
          </div>
        </div>
      </div>

      <!-- 人类消息：纯文本 -->
      <p v-else class="whitespace-pre-wrap break-words overflow-wrap-anywhere">{{ msg.content }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import type { ChatMessage } from '@/composables/useLangGraph'

const props = defineProps<{
  msg: ChatMessage
  isLoading?: boolean
}>()

// 配置 marked
marked.setOptions({
  breaks: true,       // 单换行转 <br>
  gfm: true,          // GitHub Flavored Markdown（表格、删除线等）
})

const renderedContent = computed(() => {
  const content = props.msg.content || ''
  if (!content) return '<span class="text-gray-400">...</span>'
  return marked.parse(content) as string
})
</script>

<style scoped>
.message-bubble {
  font-family: 'PingFang SC', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  animation: messageAppear 0.3s ease-out;
  overflow-wrap: break-word;
  word-break: break-word;
}

@keyframes messageAppear {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.overflow-wrap-anywhere {
  overflow-wrap: anywhere;
}

/* Markdown 样式 */
.markdown-body {
  line-height: 1.7;
  word-wrap: break-word;
  overflow-wrap: anywhere;
}

.markdown-body :deep(p) {
  margin: 0.5em 0;
}

.markdown-body :deep(p:first-child) {
  margin-top: 0;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 0.8em 0 0.4em;
  font-weight: 600;
  line-height: 1.4;
}

.markdown-body :deep(h1) { font-size: 1.4em; }
.markdown-body :deep(h2) { font-size: 1.25em; }
.markdown-body :deep(h3) { font-size: 1.1em; }
.markdown-body :deep(h4) { font-size: 1em; }

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 0.4em 0;
  padding-left: 1.5em;
}

.markdown-body :deep(li) {
  margin: 0.2em 0;
}

.markdown-body :deep(code) {
  background: #f4f4f5;
  border-radius: 4px;
  padding: 0.15em 0.4em;
  font-size: 0.88em;
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
}

.markdown-body :deep(pre) {
  background: #1e1e2e;
  color: #cdd6f4;
  border-radius: 8px;
  padding: 0.8em 1em;
  overflow-x: auto;
  margin: 0.6em 0;
}

.markdown-body :deep(pre code) {
  background: none;
  padding: 0;
  font-size: 0.85em;
  color: inherit;
}

.markdown-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 0.6em 0;
  font-size: 0.9em;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 0.4em 0.7em;
  text-align: left;
}

.markdown-body :deep(th) {
  background: #f9fafb;
  font-weight: 600;
}

.markdown-body :deep(blockquote) {
  border-left: 3px solid #d1d5db;
  padding-left: 1em;
  margin: 0.6em 0;
  color: #6b7280;
}

.markdown-body :deep(hr) {
  border: none;
  border-top: 1px solid #e5e7eb;
  margin: 0.8em 0;
}

.markdown-body :deep(a) {
  color: #2563eb;
  text-decoration: underline;
}

.markdown-body :deep(strong) {
  font-weight: 600;
}
</style>
