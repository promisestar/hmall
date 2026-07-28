<template>
  <div class="interrupt-card mx-auto max-w-[90%] bg-gradient-to-br from-amber-50 to-orange-50 border border-amber-200 rounded-2xl p-4 shadow-md animate-pulse-soft">
    <!-- 图标 + 消息 -->
    <div class="flex items-start gap-3">
      <div class="flex-shrink-0 w-8 h-8 rounded-full bg-gradient-to-br from-amber-400 to-orange-500 flex items-center justify-center text-white shadow-md shadow-amber-200">
        <TriangleAlert class="w-[18px] h-[18px]" />
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
import { TriangleAlert } from 'lucide-vue-next'
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
