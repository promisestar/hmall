<template>
  <!-- 浮动触发按钮 → 跳转独立聊天页 -->
  <router-link to="/portal/chat" class="chat-fab fixed bottom-6 right-6 w-14 h-14 rounded-full bg-gradient-to-br from-[#FF6B35] to-[#E4393C] shadow-xl shadow-red-300/50 flex items-center justify-center cursor-pointer z-50 hover:scale-110 transition-transform duration-300">
    <svg class="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
    </svg>
    <span class="absolute -top-1 -right-1 w-3 h-3 bg-green-400 rounded-full border-2 border-white"></span>
  </router-link>
</template>

<script setup lang="ts">
</script>

<style scoped>
.chat-fab {
  backdrop-filter: blur(10px);
}
</style>
