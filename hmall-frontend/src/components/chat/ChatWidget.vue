<template>
  <!-- 浮动触发按钮 -->
  <div
    v-if="!visible"
    class="chat-fab fixed bottom-6 right-6 w-14 h-14 rounded-full bg-gradient-to-br from-[#FF6B35] to-[#E4393C] shadow-xl shadow-red-300/50 flex items-center justify-center cursor-pointer z-50 hover:scale-110 transition-transform duration-300"
    @click="visible = true"
  >
    <svg class="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
    </svg>
    <span class="absolute -top-1 -right-1 w-3 h-3 bg-green-400 rounded-full border-2 border-white"></span>
  </div>

  <!-- 对话抽屉 -->
  <el-drawer
    v-model="visible"
    title="枫叶客服"
    direction="rtl"
    size="400px"
    :with-header="false"
    class="chat-drawer"
  >
    <div class="flex flex-col h-full bg-[#f5f5f5]">
      <!-- 头部 -->
      <div class="chat-header bg-gradient-to-r from-[#E4393C] to-[#C81623] text-white px-4 py-3 flex items-center justify-between">
        <div class="flex items-center gap-2">
          <div class="w-8 h-8 rounded-full bg-white/20 flex items-center justify-center text-sm font-bold">AI</div>
          <div>
            <h3 class="text-[16px] font-semibold leading-tight">枫叶客服</h3>
            <p class="text-[11px] opacity-80">{{ isLoading ? '正在回复...' : '在线' }}</p>
          </div>
        </div>
        <div class="flex items-center gap-2">
          <button @click="handleClear" class="p-1.5 hover:bg-white/20 rounded-lg transition-colors" title="清空对话">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6M1 7h22M9 7V4a1 1 0 011-1h4a1 1 0 011 1v3" />
            </svg>
          </button>
          <button @click="visible = false" class="p-1.5 hover:bg-white/20 rounded-lg transition-colors">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      </div>

      <!-- 消息区域 -->
      <div ref="messagesContainer" class="flex-1 overflow-y-auto px-4 py-3 space-y-3 scroll-smooth">
        <!-- 欢迎消息 -->
        <div v-if="messages.length === 0" class="flex flex-col items-center justify-center h-full text-center px-4">
          <div class="w-16 h-16 rounded-full bg-gradient-to-br from-[#FF6B35] to-[#E4393C] flex items-center justify-center text-white text-2xl font-bold mb-3 shadow-lg">
            AI
          </div>
          <h4 class="text-[16px] font-semibold text-[#333] mb-1">欢迎使用枫叶客服</h4>
          <p class="text-[13px] text-[#999]">我可以帮您浏览商品、秒杀下单、管理购物车和订单</p>
        </div>

        <!-- 消息列表 -->
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

      <!-- 输入区域 -->
      <div class="border-t border-gray-200 bg-white px-3 py-2.5">
        <div class="flex items-end gap-2">
          <el-input
            v-model="inputText"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 3 }"
            placeholder="输入消息..."
            class="flex-1"
            @keyup.enter.exact.prevent="handleSend"
            :disabled="isLoading || !!interruptData"
          />
          <button
            @click="handleSend"
            :disabled="!inputText.trim() || isLoading || !!interruptData"
            class="flex-shrink-0 w-10 h-10 rounded-xl bg-gradient-to-br from-[#E4393C] to-[#C81623] text-white flex items-center justify-center hover:shadow-lg hover:shadow-red-200 transition-all duration-200 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useLangGraph } from '@/composables/useLangGraph'
import MessageBubble from './MessageBubble.vue'
import InterruptActions from './InterruptActions.vue'

const visible = ref(false)
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
} = useLangGraph({ assistantId: 'customer_agent' })

// 发送消息
async function handleSend() {
  const text = inputText.value.trim()
  if (!text || isLoading.value || interruptData.value) return

  inputText.value = ''
  await sendMessage(text, {
    agent_type: 'customer',
    user_token: sessionStorage.getItem('token') || '',
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

// 自动滚动到底部
watch(
  () => messages.value.length,
  async () => {
    await nextTick()
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  }
)

// 流式消息更新时也滚动
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
.chat-fab {
  backdrop-filter: blur(10px);
}

:deep(.chat-drawer .el-drawer__body) {
  padding: 0;
  overflow: hidden;
}

:deep(.chat-drawer .el-textarea__inner) {
  border: none;
  box-shadow: none;
  background: #f9f9f9;
  border-radius: 12px;
  padding: 8px 12px;
  font-size: 13px;
  resize: none;
}

:deep(.chat-drawer .el-textarea__inner:focus) {
  box-shadow: 0 0 0 2px rgba(228, 57, 60, 0.1);
}
</style>
