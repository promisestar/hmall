<template>
  <div class="h-screen flex flex-col bg-[#f5f5f5]">
    <ChatPanel
      assistant-id="customer_agent"
      title="枫叶客服"
      welcome-text="我可以帮您浏览商品、秒杀下单、管理购物车和订单"
      input-placeholder="输入消息..."
      token-key="token"
      agent-type="customer"
      show-back
      @back="goBack"
    />
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import ChatPanel from '@/components/chat/ChatPanel.vue'

const router = useRouter()

function goBack() {
  router.back()
}
</script>
