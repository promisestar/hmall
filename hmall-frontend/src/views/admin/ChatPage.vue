<template>
  <ChatPanel
    assistant-id="admin_agent"
    title="管理助手"
    welcome-text="支持运营日报、订单查询、商品管理、秒杀监控等只读操作"
    input-placeholder="输入指令..."
    :shortcuts="shortcuts"
    token-key="admin-token"
    agent-type="admin"
  />
</template>

<script setup lang="ts">
import ChatPanel from '@/components/chat/ChatPanel.vue'

const shortcuts = [
  '运营日报',
  '查看订单',
  '商品列表',
  '秒杀活动',
  '用户列表',
]
</script>
