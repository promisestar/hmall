<template>
  <div class="flex flex-col h-full bg-[#f5f5f5]">
    <!-- 头部 -->
    <div
      class="flex items-center justify-between px-6 py-3.5 text-white shadow-sm"
      :class="theme.headerClass"
    >
      <div class="flex items-center gap-3">
        <div
          class="w-9 h-9 rounded-xl flex items-center justify-center text-sm font-bold shadow-sm"
          :class="theme.iconBgClass"
        >
          <svg v-if="theme.icon === 'shopping'" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
          </svg>
          <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
          </svg>
        </div>
        <div>
          <h3 class="text-[17px] font-semibold leading-tight">{{ title }}</h3>
          <p class="text-[12px] opacity-80 mt-0.5">{{ isLoading ? '正在回复...' : '在线' }}</p>
        </div>
      </div>
      <div class="flex items-center gap-2">
        <!-- 返回按钮（仅 portal 模式显示） -->
        <button
          v-if="showBack"
          @click="$emit('back')"
          class="p-2 hover:bg-white/20 rounded-lg transition-colors"
          title="返回"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
          </svg>
        </button>
        <button @click="handleClear" class="p-2 hover:bg-white/20 rounded-lg transition-colors" title="清空对话">
          <svg class="w-4.5 h-4.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6M1 7h22M9 7V4a1 1 0 011-1h4a1 1 0 011 1v3" />
          </svg>
        </button>
      </div>
    </div>

    <!-- 快捷操作（仅 admin 模式且无消息时显示） -->
    <div v-if="shortcuts.length > 0 && messages.length === 0" class="px-6 py-3 bg-white border-b border-gray-200">
      <p class="text-[12px] text-gray-500 mb-2.5">快捷操作</p>
      <div class="flex flex-wrap gap-2">
        <button
          v-for="shortcut in shortcuts"
          :key="shortcut"
          @click="inputText = shortcut; handleSend()"
          class="px-3.5 py-1.5 text-[13px] rounded-lg border transition-all duration-200"
          :class="theme.shortcutClass"
        >
          {{ shortcut }}
        </button>
      </div>
    </div>

    <!-- 消息区域 -->
    <div ref="messagesContainer" class="flex-1 overflow-y-auto px-6 py-4 space-y-4 scroll-smooth">
      <!-- 欢迎消息 -->
      <div v-if="messages.length === 0" class="flex flex-col items-center justify-center h-full text-center px-4">
        <div
          class="w-16 h-16 rounded-2xl flex items-center justify-center text-white text-2xl font-bold mb-4 shadow-lg"
          :class="theme.welcomeBgClass"
        >
          AI
        </div>
        <h4 class="text-[18px] font-semibold text-gray-800 mb-1.5">{{ title }}</h4>
        <p class="text-[14px] text-gray-500 max-w-md">{{ welcomeText }}</p>
      </div>

      <!-- 消息列表 -->
      <div class="max-w-4xl mx-auto space-y-4">
        <MessageBubble
          v-for="msg in messages"
          :key="msg.id"
          :msg="msg"
          :is-loading="isLoading && msg.id === messages[messages.length - 1]?.id && msg.type === 'ai'"
        />

        <!-- Interrupt 确认卡片 -->
        <InterruptActions
          v-if="interruptData"
          :data="interruptData"
          :loading="isLoading"
          @confirm="handleInterruptConfirm"
          @cancel="handleInterruptCancel"
        />
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="border-t border-gray-200 bg-white px-6 py-3.5">
      <div class="max-w-4xl mx-auto flex items-end gap-3">
        <el-input
          v-model="inputText"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 4 }"
          :placeholder="inputPlaceholder"
          class="flex-1 chat-input"
          @keyup.enter.exact.prevent="handleSend"
          :disabled="isLoading || !!interruptData"
        />
        <button
          @click="handleSend"
          :disabled="!inputText.trim() || isLoading || !!interruptData"
          class="flex-shrink-0 w-11 h-11 rounded-xl text-white flex items-center justify-center transition-all duration-200 disabled:opacity-40 disabled:cursor-not-allowed"
          :class="theme.sendBtnClass"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch, computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useLangGraph } from '@/composables/useLangGraph'
import MessageBubble from './MessageBubble.vue'
import InterruptActions from './InterruptActions.vue'

interface Theme {
  headerClass: string
  iconBgClass: string
  icon: 'shopping' | 'admin'
  welcomeBgClass: string
  sendBtnClass: string
  shortcutClass: string
}

const props = withDefaults(defineProps<{
  assistantId: 'customer_agent' | 'admin_agent'
  title: string
  welcomeText: string
  inputPlaceholder?: string
  shortcuts?: string[]
  tokenKey: string
  agentType: 'customer' | 'admin'
  showBack?: boolean
}>(), {
  inputPlaceholder: '输入消息...',
  shortcuts: () => [],
  showBack: false,
})

defineEmits<{ back: [] }>()

const theme = computed<Theme>(() => {
  if (props.agentType === 'customer') {
    return {
      headerClass: 'bg-gradient-to-r from-[#E4393C] to-[#C81623]',
      iconBgClass: 'bg-white/20',
      icon: 'shopping',
      welcomeBgClass: 'bg-gradient-to-br from-[#FF6B35] to-[#E4393C]',
      sendBtnClass: 'bg-gradient-to-br from-[#E4393C] to-[#C81623] hover:shadow-lg hover:shadow-red-200',
      shortcutClass: 'text-[#E4393C] bg-red-50 border-red-200 hover:border-[#E4393C] hover:bg-red-100',
    }
  }
  return {
    headerClass: 'bg-gradient-to-r from-[#304156] to-[#2a3a52]',
    iconBgClass: 'bg-white/10',
    icon: 'admin',
    welcomeBgClass: 'bg-gradient-to-br from-[#304156] to-[#409EFF]',
    sendBtnClass: 'bg-gradient-to-br from-[#304156] to-[#409EFF] hover:shadow-lg hover:shadow-blue-200',
    shortcutClass: 'text-[#304156] bg-[#f0f2f5] border-gray-200 hover:border-[#409EFF] hover:text-[#409EFF]',
  }
})

const inputText = ref('')
const messagesContainer = ref<HTMLElement | null>(null)

const {
  messages,
  isLoading,
  interruptData,
  sendMessage,
  resume,
  rejectInterrupt,
  clearHistory,
} = useLangGraph({ assistantId: props.assistantId })

// 发送消息
async function handleSend() {
  const text = inputText.value.trim()
  if (!text || isLoading.value || interruptData.value) return

  inputText.value = ''
  await sendMessage(text, {
    agent_type: props.agentType,
    user_token: sessionStorage.getItem(props.tokenKey) || '',
  })
}

// Interrupt 确认
async function handleInterruptConfirm() {
  if (!interruptData.value) return
  const expected = interruptData.value.expected_response || '确认'
  await resume(expected)
}

// Interrupt 取消
async function handleInterruptCancel() {
  await rejectInterrupt()
}

// 清空对话
async function handleClear() {
  try {
    await ElMessageBox.confirm('确定清空所有对话记录？', '提示', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
    await clearHistory()
  } catch {
    // 用户取消
  }
}

// 自动滚动
watch(
  () => messages.value.length,
  async () => {
    await nextTick()
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  }
)

watch(
  () => messages.value[messages.value.length - 1]?.content,
  async () => {
    await nextTick()
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  }
)
</script>

<style scoped>
:deep(.chat-input .el-textarea__inner) {
  border: 1px solid #e5e7eb;
  box-shadow: none;
  background: #f9fafb;
  border-radius: 12px;
  padding: 10px 14px;
  font-size: 14px;
  resize: none;
  line-height: 1.6;
}

:deep(.chat-input .el-textarea__inner:focus) {
  border-color: #d1d5db;
  box-shadow: 0 0 0 3px rgba(0, 0, 0, 0.04);
}
</style>
