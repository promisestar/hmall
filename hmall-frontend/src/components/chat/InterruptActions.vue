<template>
  <div class="interrupt-card mx-auto max-w-[90%] bg-gradient-to-br from-amber-50 to-orange-50 border border-amber-200 rounded-2xl p-4 shadow-md animate-pulse-soft">
    <!-- 图标 + 消息 -->
    <div class="flex items-start gap-3">
      <div class="flex-shrink-0 w-8 h-8 rounded-full bg-amber-400 flex items-center justify-center text-white">
        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
        </svg>
      </div>
      <div class="flex-1 min-w-0">
        <p class="text-[13px] text-[#666] font-medium leading-relaxed whitespace-pre-wrap">{{ data.message }}</p>
        <p v-if="data.expected_response" class="text-[12px] text-amber-600 mt-1.5">
          回复"{{ data.expected_response }}"以确认
        </p>
      </div>
    </div>

    <!-- 操作按钮 -->
    <div class="flex items-center gap-2 mt-3 pl-11">
      <button
        @click="$emit('confirm')"
        :disabled="loading"
        class="px-4 py-1.5 bg-gradient-to-r from-[#E4393C] to-[#C81623] text-white text-[13px] font-medium rounded-lg hover:shadow-lg hover:shadow-red-200 transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
      >
        {{ confirmText }}
      </button>
      <button
        @click="$emit('cancel')"
        :disabled="loading"
        class="px-4 py-1.5 bg-white text-[#666] text-[13px] rounded-lg border border-gray-200 hover:bg-gray-50 transition-all duration-200 disabled:opacity-50"
      >
        取消
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { InterruptData } from '@/composables/useLangGraph'

const props = defineProps<{
  data: InterruptData
  loading?: boolean
}>()

defineEmits<{
  confirm: []
  cancel: []
}>()

// 根据类型决定按钮文本
const confirmText = computed(() => {
  switch (props.data.type) {
    case 'confirmation':
      return props.data.expected_response || '确认'
    case 'field_selection':
    case 'value_input':
    case 'address_input':
      return '输入'
    default:
      return '确认'
  }
})
</script>

<style scoped>
@keyframes pulse-soft {
  0%, 100% {
    box-shadow: 0 4px 12px rgba(245, 158, 11, 0.1);
  }
  50% {
    box-shadow: 0 4px 16px rgba(245, 158, 11, 0.2);
  }
}

.animate-pulse-soft {
  animation: pulse-soft 2s ease-in-out infinite;
  font-family: 'PingFang SC', -apple-system, sans-serif;
}
</style>
