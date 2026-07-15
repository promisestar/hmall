<template>
  <div class="flex w-full" :class="msg.type === 'human' ? 'justify-end' : 'justify-start'">
    <div
      class="message-bubble max-w-[80%] px-4 py-2.5 rounded-2xl text-[13px] leading-relaxed whitespace-pre-wrap break-words transition-all duration-300"
      :class="msg.type === 'human'
        ? 'bg-gradient-to-br from-[#E4393C] to-[#C81623] text-white rounded-br-md shadow-lg shadow-red-200'
        : 'bg-white text-[#333] rounded-bl-md shadow-sm border border-gray-100'"
    >
      <!-- AI 头像 -->
      <div v-if="msg.type === 'ai'" class="flex items-start gap-2">
        <div class="flex-shrink-0 w-7 h-7 rounded-full bg-gradient-to-br from-[#FF6B35] to-[#E4393C] flex items-center justify-center text-white text-xs font-bold mt-0.5">
          AI
        </div>
        <div class="flex-1">
          <p class="message-content" v-html="formattedContent"></p>
        </div>
      </div>

      <!-- 人类消息 -->
      <p v-else class="message-content" v-html="formattedContent"></p>

      <!-- 加载动画 -->
      <div v-if="isLoading && msg.type === 'ai'" class="flex items-center gap-1 mt-1">
        <span class="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style="animation-delay: 0ms"></span>
        <span class="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style="animation-delay: 150ms"></span>
        <span class="w-1.5 h-1.5 bg-gray-400 rounded-full animate-bounce" style="animation-delay: 300ms"></span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '@/composables/useLangGraph'

const props = defineProps<{
  msg: ChatMessage
  isLoading?: boolean
}>()

// 格式化内容：将 \n 转换为 <br>，保留 emoji
const formattedContent = computed(() => {
  const content = props.msg.content || ''
  // 转义 HTML 特殊字符
  const escaped = content
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  // 恢复换行
  return escaped.replace(/\n/g, '<br>')
})
</script>

<style scoped>
.message-bubble {
  font-family: 'PingFang SC', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  animation: messageAppear 0.3s ease-out;
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

.message-content {
  margin: 0;
  line-height: 1.6;
}
</style>
