<template>
  <!-- 浮动触发按钮 → 跳转独立聊天页 -->
  <router-link to="/portal/chat" class="chat-fab group fixed bottom-6 right-6 z-50">
    <!-- 脉冲光环 -->
    <span class="absolute inset-0 rounded-full bg-[#E4393C]/40 animate-ping"></span>

    <!-- 按钮主体 -->
    <span class="relative w-14 h-14 rounded-full bg-gradient-to-br from-[#FF6B35] to-[#E4393C] shadow-xl shadow-red-300/50 flex items-center justify-center cursor-pointer group-hover:scale-110 transition-transform duration-300">
      <Sparkles class="w-6 h-6 text-white" />
      <span class="absolute -top-1 -right-1 w-3.5 h-3.5 bg-green-400 rounded-full border-2 border-white"></span>
    </span>

    <!-- 悬停提示 -->
    <span class="absolute right-full top-1/2 -translate-y-1/2 mr-3 px-3 py-1.5 rounded-lg bg-gray-900 text-white text-xs whitespace-nowrap opacity-0 translate-x-1 group-hover:opacity-100 group-hover:translate-x-0 transition-all duration-300 pointer-events-none">
      AI 智能客服
    </span>
  </router-link>
</template>

<script setup lang="ts">
import { Sparkles } from 'lucide-vue-next'
</script>

<style scoped>
.chat-fab {
  backdrop-filter: blur(10px);
}

/* 脉冲动画降速，避免过度抢眼 */
.chat-fab :first-child {
  animation-duration: 2.4s;
}
</style>
