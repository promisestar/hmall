<template>
  <!-- 触发按钮 -->
  <el-button
    @click="visible = true"
    class="admin-chat-trigger"
    type="primary"
    text
    size="small"
  >
    <el-icon class="mr-1"><ChatDotRound /></el-icon>
    AI助手
  </el-button>

  <!-- 侧边滑出面板 -->
  <el-drawer
    v-model="visible"
    title="管理助手"
    direction="rtl"
    size="420px"
    :with-header="false"
    class="admin-chat-drawer"
  >
    <div class="flex flex-col h-full bg-[#f0f2f5]">
      <!-- 头部 -->
      <div class="bg-gradient-to-r from-[#304156] to-[#2a3a52] text-white px-4 py-3 flex items-center justify-between">
        <div class="flex items-center gap-2">
          <div class="w-8 h-8 rounded-lg bg-white/10 flex items-center justify-center">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
            </svg>
          </div>
          <div>
            <h3 class="text-[16px] font-semibold leading-tight">管理助手</h3>
            <p class="text-[11px] opacity-70">{{ isLoading ? '处理中...' : '只读模式 · 在线' }}</p>
          </div>
        </div>
        <div class="flex items-center gap-1">
          <button @click="handleClear" class="p-1.5 hover:bg-white/10 rounded transition-colors" title="清空">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6M1 7h22M9 7V4a1 1 0 011-1h4a1 1 0 011 1v3" />
            </svg>
          </button>
          <button @click="visible = false" class="p-1.5 hover:bg-white/10 rounded transition-colors">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      </div>

      <!-- 快捷操作 -->
      <div v-if="messages.length === 0" class="px-4 py-3 bg-white border-b border-gray-200">
        <p class="text-[12px] text-[#999] mb-2">快捷操作</p>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="shortcut in shortcuts"
            :key="shortcut"
            @click="inputText = shortcut; handleSend()"
            class="px-3 py-1.5 text-[12px] text-[#304156] bg-[#f0f2f5] rounded-lg border border-gray-200 hover:border-[#409EFF] hover:text-[#409EFF] transition-all duration-200"
          >
            {{ shortcut }}
          </button>
        </div>
      </div>

      <!-- 消息区域 -->
      <div ref="messagesContainer" class="flex-1 overflow-y-auto px-4 py-3 space-y-3 scroll-smooth">
        <!-- 欢迎消息 -->
        <div v-if="messages.length === 0" class="flex flex-col items-center justify-center h-full text-center px-4">
          <div class="w-16 h-16 rounded-2xl bg-gradient-to-br from-[#304156] to-[#409EFF] flex items-center justify-center text-white text-2xl font-bold mb-3 shadow-lg">
            AI
          </div>
          <h4 class="text-[16px] font-semibold text-[#333] mb-1">管理助手</h4>
          <p class="text-[13px] text-[#999]">支持运营日报、订单查询、商品管理、秒杀监控等只读操作</p>
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
            placeholder="输入指令..."
            class="flex-1"
            @keyup.enter.exact.prevent="handleSend"
            :disabled="isLoading || !!interruptData"
          />
          <button
            @click="handleSend"
            :disabled="!inputText.trim() || isLoading || !!interruptData"
            class="flex-shrink-0 w-10 h-10 rounded-xl bg-gradient-to-br from-[#304156] to-[#409EFF] text-white flex items-center justify-center hover:shadow-lg hover:shadow-blue-200 transition-all duration-200 disabled:opacity-40 disabled:cursor-not-allowed"
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
import { ChatDotRound } from '@element-plus/icons-vue'
import { useLangGraph } from '@/composables/useLangGraph'
import MessageBubble from './MessageBubble.vue'
import InterruptActions from './InterruptActions.vue'

const visible = ref(false)
const inputText = ref('')
const messagesContainer = ref<HTMLElement | null>(null)

const shortcuts = [
  '运营日报',
  '查看订单',
  '商品列表',
  '秒杀活动',
  '用户列表',
]

const {
  messages,
  isLoading,
  interruptData,
  sendMessage,
  resume,
  rejectInterrupt,
  clearHistory,
} = useLangGraph({ assistantId: 'admin_agent' })

// 发送消息
async function handleSend() {
  const text = inputText.value.trim()
  if (!text || isLoading.value || interruptData.value) return

  inputText.value = ''
  await sendMessage(text, {
    agent_type: 'admin',
    user_token: sessionStorage.getItem('admin-token') || '',
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
:deep(.admin-chat-drawer .el-drawer__body) {
  padding: 0;
  overflow: hidden;
}

:deep(.admin-chat-drawer .el-textarea__inner) {
  border: none;
  box-shadow: none;
  background: #f5f7fa;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
  resize: none;
}

:deep(.admin-chat-drawer .el-textarea__inner:focus) {
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}
</style>
